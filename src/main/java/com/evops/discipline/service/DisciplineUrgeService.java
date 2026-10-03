package com.evops.discipline.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.common.BusinessException;
import com.evops.discipline.common.DisciplineConstants;
import com.evops.discipline.entity.DisciplineCase;
import com.evops.discipline.entity.DisciplineUrge;
import com.evops.discipline.mapper.DisciplineCaseMapper;
import com.evops.discipline.mapper.DisciplineUrgeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 超时催办：超过处理决定期限仍 REPORTED 的案件，按配置间隔自动催办承办人（服务器钟点）。
 * 每轮催办只追加一条 t_discipline_urge（站内），可人工 POST /api/discipline/urge/sweep 立即执行。
 * 每个案件在独立事务内处理，单案异常不影响其他案件。
 */
@Service
public class DisciplineUrgeService {

    private static final Logger log = LoggerFactory.getLogger(DisciplineUrgeService.class);

    private final DisciplineCaseMapper caseMapper;
    private final DisciplineUrgeMapper urgeMapper;
    private final DisciplineConfigService configService;
    /** 自注入代理：逐案 REQUIRES_NEW 独立事务必须经代理调用，同类内直接调用事务切面不生效。 */
    private final DisciplineUrgeService self;

    public DisciplineUrgeService(DisciplineCaseMapper caseMapper,
                                 DisciplineUrgeMapper urgeMapper,
                                 DisciplineConfigService configService,
                                 @Lazy DisciplineUrgeService self) {
        this.caseMapper = caseMapper;
        this.urgeMapper = urgeMapper;
        this.configService = configService;
        this.self = self;
    }

    /** 扫描所有超时案件，逐案独立事务催办。返回催办轮次统计。 */
    public Map<String, Object> sweep() {
        LocalDateTime now = LocalDateTime.now();
        List<DisciplineCase> overdue = caseMapper.selectList(new QueryWrapper<DisciplineCase>()
                .eq("status", DisciplineConstants.CASE_REPORTED)
                .lt("decision_deadline", now)
                .orderByAsc("decision_deadline"));
        int urgedCases = 0;
        int urgeRounds = 0;
        for (DisciplineCase c : overdue) {
            try {
                int rounds = self.urgeOneCase(c.getId());
                if (rounds > 0) {
                    urgedCases++;
                    urgeRounds += rounds;
                }
            } catch (Exception e) {
                // 单案失败不阻塞其他案件催办
                log.error("违纪案件 {} 催办失败：{}", c.getCaseNo(), e.getMessage(), e);
            }
        }
        Map<String, Object> result = new HashMap<>();
        result.put("overdueCount", overdue.size());
        result.put("urgedCases", urgedCases);
        result.put("urgeRounds", urgeRounds);
        result.put("sweptAt", now);
        return result;
    }

    /**
     * 单个案件催办（独立事务）：首轮超时即催；其后每过一个催办间隔催一轮。
     * 一次扫描最多补一轮（每分钟扫描，间隔远大于扫描频率）。返回本次新增催办条数。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int urgeOneCase(Long caseId) {
        LocalDateTime now = LocalDateTime.now();
        DisciplineCase c = caseMapper.selectById(caseId);
        if (c == null || !DisciplineConstants.CASE_REPORTED.equals(c.getStatus())) {
            return 0;
        }
        if (!now.isAfter(c.getDecisionDeadline())) {
            return 0;
        }
        int intervalHours = configService.getUrgeIntervalHours();
        int currentCount = c.getUrgedCount() == null ? 0 : c.getUrgedCount();
        LocalDateTime dueNext = currentCount == 0
                ? c.getDecisionDeadline()
                : (c.getLastUrgedAt() == null ? c.getDecisionDeadline()
                        : c.getLastUrgedAt().plusHours(intervalHours));
        if (now.isBefore(dueNext)) {
            return 0;
        }

        int round = currentCount + 1;
        String recipient = c.getAssignee() == null || c.getAssignee().trim().isEmpty()
                ? configService.getDefaultAssignee() : c.getAssignee();
        String title = String.format("违纪处理催办（第 %d 轮）：案件 %s 已超处理期限", round, c.getCaseNo());
        String content = String.format(
                "案件号 %s（学员 %s，%s）于 %s 立案，处理决定期限 %s 已到，请尽快作出处理决定。本催办由系统按期限自动发出（第 %d 轮）。",
                c.getCaseNo(), c.getName(), DisciplineCaseService.violationLabel(c.getViolationType()),
                c.getReportedAt(), c.getDecisionDeadline(), round);

        DisciplineUrge urge = new DisciplineUrge();
        urge.setCaseId(c.getId());
        urge.setCaseNo(c.getCaseNo());
        urge.setUrgeRound(round);
        urge.setRecipient(recipient);
        urge.setTitle(title);
        urge.setContent(content);
        urge.setDeadline(c.getDecisionDeadline());
        urge.setChannel(DisciplineConstants.URGE_CHANNEL_IN_SITE);
        urge.setStatus(DisciplineConstants.URGE_NEW);
        urge.setUrgedBy("system");
        urge.setUrgedAt(now);
        urgeMapper.insert(urge);

        c.setUrgedCount(round);
        c.setLastUrgedAt(now);
        caseMapper.updateById(c);
        log.info("违纪案件 {} 第 {} 轮自动催办，承办人 {}", c.getCaseNo(), round, recipient);
        return 1;
    }

    @Transactional(readOnly = true)
    public List<DisciplineUrge> listUrges(Long caseId, String status, String recipient) {
        QueryWrapper<DisciplineUrge> wrapper = new QueryWrapper<DisciplineUrge>()
                .orderByDesc("urged_at", "id");
        if (caseId != null) {
            wrapper.eq("case_id", caseId);
        }
        if (status != null && !status.trim().isEmpty()) {
            wrapper.eq("status", status.trim().toUpperCase());
        }
        if (recipient != null && !recipient.trim().isEmpty()) {
            wrapper.eq("recipient", recipient.trim());
        }
        return urgeMapper.selectList(wrapper);
    }

    @Transactional
    public DisciplineUrge markRead(Long id) {
        DisciplineUrge urge = urgeMapper.selectById(id);
        if (urge == null) {
            throw new BusinessException("URGE_NOT_FOUND", "催办记录不存在：id=" + id);
        }
        if (DisciplineConstants.URGE_NEW.equals(urge.getStatus())) {
            urge.setStatus(DisciplineConstants.URGE_READ);
            urge.setReadAt(LocalDateTime.now());
            urgeMapper.updateById(urge);
        }
        return urge;
    }
}
