package com.evops.capacity.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.capacity.common.CapacityConstants;
import com.evops.capacity.dto.WaitlistJoinRequest;
import com.evops.capacity.entity.CapacityRule;
import com.evops.capacity.entity.CapacityRuleVersion;
import com.evops.capacity.entity.CapacityState;
import com.evops.capacity.entity.CapacityNotification;
import com.evops.capacity.entity.CapacityWaitlist;
import com.evops.capacity.entity.CapacityWaitlistEvent;
import com.evops.capacity.mapper.CapacityStateMapper;
import com.evops.capacity.mapper.CapacityWaitlistEventMapper;
import com.evops.capacity.mapper.CapacityWaitlistMapper;
import com.evops.capacity.vo.CapacityStateView;
import com.evops.capacity.vo.WaitlistPositionView;
import com.evops.common.BusinessException;
import com.evops.common.OperatorHolder;
import com.evops.exam.entity.ExamBatch;
import com.evops.exam.entity.Registration;
import com.evops.exam.mapper.RegistrationMapper;
import com.evops.exam.service.ExamBatchService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 候补队列状态机（满员入队 → 退位递补 → 限时确认 → 锁座 / 超时顺延）：
 * - 所有变更先取 t_capacity_state 行锁（FOR UPDATE），同规则串行，杜绝并发超卖与重复递补；
 * - 超时一律以服务器钟点判定，不用客户端时间；
 * - 超时者与被顺延者双方通知都发，顺延在同一事务内接着递补下一位，不漏发；
 * - 一人一规则一条，重复请求只回原位置；超时/退出后再排只能到队尾，绝不插队；
 * - 递补确认成功才回写报名名册并锁座；候补窗口关闭后不再发新递补。
 */
@Service
public class WaitlistService {

    /** 报名名册中“已退位”状态：退位释放名额但不删名册记录。 */
    public static final String REG_WITHDRAWN = "WITHDRAWN";

    private final CapacityStateMapper stateMapper;
    private final CapacityWaitlistMapper waitlistMapper;
    private final CapacityWaitlistEventMapper eventMapper;
    private final RegistrationMapper registrationMapper;
    private final CapacityRuleService ruleService;
    private final ExamBatchService batchService;
    private final CapacityNotificationService notificationService;
    private final WaitlistService self;

    public WaitlistService(CapacityStateMapper stateMapper,
                           CapacityWaitlistMapper waitlistMapper,
                           CapacityWaitlistEventMapper eventMapper,
                           RegistrationMapper registrationMapper,
                           CapacityRuleService ruleService,
                           ExamBatchService batchService,
                           CapacityNotificationService notificationService,
                           @Lazy WaitlistService self) {
        this.stateMapper = stateMapper;
        this.waitlistMapper = waitlistMapper;
        this.eventMapper = eventMapper;
        this.registrationMapper = registrationMapper;
        this.ruleService = ruleService;
        this.batchService = batchService;
        this.notificationService = notificationService;
        this.self = self;
    }

    // ---------- 入队（满员才进；重复请求回原位置不插队） ----------

    @Transactional
    public WaitlistPositionView join(WaitlistJoinRequest request) {
        CapacityRule rule = resolveRule(request.getRuleId(), request.getBatchId());
        LocalDateTime now = LocalDateTime.now();
        String operator = OperatorHolder.current();

        CapacityState state = lockState(rule.getId());
        requireActiveAndOpen(rule.getId(), state, now);

        String regNo = request.getRegNo().trim();
        if (isEnrolled(rule.getBatchId(), regNo)) {
            throw new BusinessException("CAP_ALREADY_ENROLLED",
                    "报名号 " + regNo + " 已在批次名册中，无需候补");
        }

        CapacityWaitlist row = waitlistMapper.selectOne(new QueryWrapper<CapacityWaitlist>()
                .eq("rule_id", rule.getId())
                .eq("reg_no", regNo));
        if (row != null) {
            return handleRepeatedJoin(rule, state, row, request, now, operator);
        }

        // 满员才进队列；仍有空位不候补（避免占着队列）
        recomputeCounts(state);
        if (state.getOccupiedCount() < state.getCapacity()) {
            throw new BusinessException("CAP_NOT_FULL",
                    "当前仍有空余座位（容量 " + state.getCapacity() + "，已占 "
                            + state.getOccupiedCount() + "），无需候补");
        }
        int joinPosition = countByStatus(rule.getId(), CapacityConstants.WAIT_WAITING) + 1;

        row = new CapacityWaitlist();
        row.setRuleId(rule.getId());
        row.setSiteId(rule.getSiteId());
        row.setBatchId(rule.getBatchId());
        row.setRegNo(regNo);
        row.setName(request.getName().trim());
        row.setPhone(request.getPhone());
        row.setSeq(nextSeq(rule.getId()));
        row.setStatus(CapacityConstants.WAIT_WAITING);
        row.setOfferRound(0);
        waitlistMapper.insert(row);

        recordEvent(row, CapacityConstants.EVENT_JOIN, null, CapacityConstants.WAIT_WAITING,
                "满员进入候补队列（第 " + joinPosition + " 位）", operator, now);
        notificationService.notify(rule.getId(), row.getId(), rule.getBatchId(), regNo, row.getName(),
                CapacityConstants.NOTIFY_QUEUED, "候补入队成功",
                String.format("您已进入 %s 候补队列，当前第 %d 位；有人退位将按序递补，递补通知带确认时限，请留意。",
                        rule.getName(), joinPosition),
                null, operator);

        recomputeCounts(state, operator, now);
        return toPositionView(row, state, now);
    }

    /** 重复请求：候补中/待确认只回原位置；已锁座报错；超时/退出后再排一律到队尾，不插队。 */
    private WaitlistPositionView handleRepeatedJoin(CapacityRule rule, CapacityState state,
                                                    CapacityWaitlist row, WaitlistJoinRequest request,
                                                    LocalDateTime now, String operator) {
        String status = row.getStatus();
        if (CapacityConstants.WAIT_WAITING.equals(status) || CapacityConstants.WAIT_OFFERED.equals(status)) {
            // 原通知已发，不重复发、不换序号
            if (request.getPhone() != null) {
                row.setPhone(request.getPhone());
            }
            row.setName(request.getName().trim());
            waitlistMapper.updateById(row);
            recomputeCounts(state, operator, now);
            return toPositionView(row, state, now);
        }
        if (CapacityConstants.WAIT_SEATED.equals(status)) {
            throw new BusinessException("CAP_ALREADY_SEATED", "报名号 " + row.getRegNo() + " 已递补锁座，无需重复候补");
        }
        if (CapacityConstants.WAIT_REJECTED.equals(status)) {
            throw new BusinessException("CAP_REJECTED", "您的候补已被拒收（规则停用等），不能重新入队：" + row.getRegNo());
        }
        // EXPIRED / CANCELLED：重新排队只能到队尾
        recomputeCounts(state);
        if (state.getOccupiedCount() < state.getCapacity()) {
            throw new BusinessException("CAP_NOT_FULL",
                    "当前仍有空余座位（容量 " + state.getCapacity() + "，已占 "
                            + state.getOccupiedCount() + "），无需候补");
        }
        String reason = CapacityConstants.WAIT_EXPIRED.equals(status)
                ? "上次递补超时未确认，重新排队至队尾" : "退出候补后重新排队至队尾";
        row.setName(request.getName().trim());
        row.setPhone(request.getPhone());
        row.setSeq(nextSeq(rule.getId()));
        row.setStatus(CapacityConstants.WAIT_WAITING);
        row.setOfferRound(0);
        row.setOfferedAt(null);
        row.setOfferDeadline(null);
        row.setConfirmedAt(null);
        row.setRegistrationId(null);
        row.setFinishedAt(null);
        row.setFinishReason(null);
        waitlistMapper.updateById(row);
        recordEvent(row, CapacityConstants.EVENT_JOIN, status, CapacityConstants.WAIT_WAITING,
                reason, operator, now);
        recomputeCounts(state, operator, now);
        return toPositionView(row, state, now);
    }

    // ---------- 确认递补（限时；成功锁座回写名册） ----------

    @Transactional
    public CapacityWaitlist confirm(Long waitlistId, Long ruleId, Long batchId, String regNo) {
        LocalDateTime now = LocalDateTime.now();

        // 先用普通读做状态/超时判断，不持有状态行锁：撞超时点时要开独立事务顺延，
        // 若此处先锁状态行再开 REQUIRES_NEW 取同一行锁会自锁。
        CapacityWaitlist row = locate(waitlistId, ruleId, batchId, regNo);
        if (CapacityConstants.WAIT_SEATED.equals(row.getStatus())) {
            throw new BusinessException("CAP_ALREADY_SEATED", "已确认锁座，请勿重复确认：" + row.getRegNo());
        }
        if (!CapacityConstants.WAIT_OFFERED.equals(row.getStatus())) {
            throw new BusinessException("CAP_NOT_OFFERED",
                    "当前不在递补待确认状态（" + row.getStatus() + "），不能确认：" + row.getRegNo());
        }
        if (row.getOfferDeadline() != null && now.isAfter(row.getOfferDeadline())) {
            // 独立事务完成超时顺延并落库，再拒收本次确认
            self.expireAndPromoteInNewTx(row.getRuleId());
            throw new BusinessException("CAP_OFFER_EXPIRED",
                    "已超过确认时限（" + row.getOfferDeadline() + "），名额已顺给下一位：" + row.getRegNo());
        }

        // 正式确认：锁状态行后重读二次校验，防与定时超时扫描竞争（重复锁座）
        CapacityState state = lockState(row.getRuleId());
        row = waitlistMapper.selectById(row.getId());
        String operator = OperatorHolder.current();
        CapacityRule rule = ruleService.getRule(row.getRuleId());

        if (CapacityConstants.WAIT_SEATED.equals(row.getStatus())) {
            throw new BusinessException("CAP_ALREADY_SEATED", "已确认锁座，请勿重复确认：" + row.getRegNo());
        }
        if (!CapacityConstants.WAIT_OFFERED.equals(row.getStatus())) {
            throw new BusinessException("CAP_NOT_OFFERED",
                    "当前不在递补待确认状态（" + row.getStatus() + "），不能确认：" + row.getRegNo());
        }
        if (row.getOfferDeadline() != null && LocalDateTime.now().isAfter(row.getOfferDeadline())) {
            // 锁等待期间已被定时扫描超时：顺延已由扫描事务完成，这里直接拒收
            throw new BusinessException("CAP_OFFER_EXPIRED",
                    "已超过确认时限（" + row.getOfferDeadline() + "），名额已顺给下一位：" + row.getRegNo());
        }

        // 锁座：回写报名名册（一人一批次一条）
        Registration reg = findRegistration(row.getBatchId(), row.getRegNo());
        if (reg == null) {
            ExamBatch batch = batchService.getBatch(row.getBatchId());
            reg = new Registration();
            reg.setRegNo(row.getRegNo());
            reg.setName(row.getName());
            reg.setPhone(row.getPhone());
            reg.setBatchId(batch.getId());
            reg.setSubject(batch.getSubject());
            reg.setStatus(com.evops.exam.common.ExamConstants.REG_REGISTERED);
            reg.setCorrectionCount(0);
            reg.setRemark("容量候补递补锁座：规则 " + rule.getCode());
            registrationMapper.insert(reg);
        } else if (REG_WITHDRAWN.equals(reg.getStatus())) {
            // 同名册记录曾退位，重新占座
            reg.setStatus(com.evops.exam.common.ExamConstants.REG_REGISTERED);
            registrationMapper.updateById(reg);
        }

        String from = row.getStatus();
        row.setStatus(CapacityConstants.WAIT_SEATED);
        row.setConfirmedAt(now);
        row.setRegistrationId(reg.getId());
        row.setFinishedAt(now);
        row.setFinishReason("第 " + row.getOfferRound() + " 轮递补在时限内确认，锁座成功");
        waitlistMapper.updateById(row);
        recordEvent(row, CapacityConstants.EVENT_CONFIRM, from, CapacityConstants.WAIT_SEATED,
                "递补确认成功，锁定座位（报名名册 id=" + reg.getId() + "）", operator, now);
        notificationService.notify(row.getRuleId(), row.getId(), row.getBatchId(), row.getRegNo(), row.getName(),
                CapacityConstants.NOTIFY_SEATED, "递补确认成功，座位已锁定",
                String.format("您已于 %s 确认递补，%s 的座位已锁定。", now, rule.getName()),
                null, operator);

        // 待确认占座转为在册占座，占用总数不变
        recomputeCounts(state, operator, now);
        return row;
    }

    // ---------- 退出候补（候补中退出不释放座位；待确认退出会释放并顺延） ----------

    @Transactional
    public CapacityWaitlist cancel(Long waitlistId, Long ruleId, Long batchId, String regNo, String reason) {
        CapacityWaitlist row = locate(waitlistId, ruleId, batchId, regNo);
        LocalDateTime now = LocalDateTime.now();
        String operator = OperatorHolder.current();

        // 同 confirm：先锁状态行再重读，避免与超时扫描竞争
        CapacityState state = lockState(row.getRuleId());
        row = waitlistMapper.selectById(row.getId());
        String status = row.getStatus();
        if (CapacityConstants.WAIT_SEATED.equals(status)) {
            throw new BusinessException("CAP_SEATED_USE_RESIGN", "已锁座人员退位请走退位接口（resign）");
        }
        if (CapacityConstants.WAIT_EXPIRED.equals(status)
                || CapacityConstants.WAIT_CANCELLED.equals(status)
                || CapacityConstants.WAIT_REJECTED.equals(status)) {
            throw new BusinessException("CAP_NOT_ACTIVE_WAITING",
                    "候补已结束（" + status + "），无需退出：" + row.getRegNo());
        }
        String from = status;
        row.setStatus(CapacityConstants.WAIT_CANCELLED);
        row.setFinishedAt(now);
        row.setFinishReason(reason == null || reason.trim().isEmpty() ? "主动退出候补" : reason.trim());
        waitlistMapper.updateById(row);
        recordEvent(row, CapacityConstants.EVENT_CANCEL, from, CapacityConstants.WAIT_CANCELLED,
                row.getFinishReason(), operator, now);
        notificationService.notify(row.getRuleId(), row.getId(), row.getBatchId(), row.getRegNo(), row.getName(),
                CapacityConstants.NOTIFY_CANCELLED, "已退出候补",
                "您已退出候补队列。", null, operator);

        // 待确认退出腾出一个占座，立即顺延
        if (CapacityConstants.WAIT_OFFERED.equals(from)) {
            promote(state, operator, now);
        }
        recomputeCounts(state, operator, now);
        return row;
    }

    // ---------- 退位（已锁座/在册人员释放名额，按序递补） ----------

    @Transactional
    public Map<String, Object> resign(Long ruleId, Long batchId, String regNo, String reason) {
        CapacityRule rule = resolveRule(ruleId, batchId);
        LocalDateTime now = LocalDateTime.now();
        String operator = OperatorHolder.current();

        CapacityState state = lockState(rule.getId());
        if (regNo == null || regNo.trim().isEmpty()) {
            throw new BusinessException("CAP_REG_NO_REQUIRED", "退位报名号不能为空");
        }
        String personNo = regNo.trim();
        Registration reg = findRegistration(rule.getBatchId(), personNo);
        CapacityWaitlist row = waitlistMapper.selectOne(new QueryWrapper<CapacityWaitlist>()
                .eq("rule_id", rule.getId())
                .eq("reg_no", personNo));

        boolean released = false;
        if (reg != null && !REG_WITHDRAWN.equals(reg.getStatus())) {
            reg.setStatus(REG_WITHDRAWN);
            reg.setRemark(appendRemark(reg.getRemark(), "退位释放名额："
                    + (reason == null || reason.trim().isEmpty() ? "未填写原因" : reason.trim())));
            registrationMapper.updateById(reg);
            released = true;
        }

        if (row != null && CapacityConstants.WAIT_SEATED.equals(row.getStatus())) {
            String from = row.getStatus();
            row.setStatus(CapacityConstants.WAIT_CANCELLED);
            row.setFinishedAt(now);
            row.setFinishReason("锁座后退位：" + (reason == null || reason.trim().isEmpty() ? "未填写原因" : reason.trim()));
            waitlistMapper.updateById(row);
            recordEvent(row, CapacityConstants.EVENT_CANCEL, from, CapacityConstants.WAIT_CANCELLED,
                    row.getFinishReason(), operator, now);
            notificationService.notify(row.getRuleId(), row.getId(), row.getBatchId(), row.getRegNo(),
                    row.getName(), CapacityConstants.NOTIFY_CANCELLED, "退位登记成功",
                    "您的退位已登记，名额按候补顺序递补。", null, operator);
            released = true;
        }

        if (!released) {
            throw new BusinessException("CAP_NOT_ENROLLED",
                    "报名号 " + personNo + " 不在该批次在册名单中，无可退位名额");
        }

        // 腾出名额立即按序递补（窗口仍开放才发新递补）
        int offered = promote(state, operator, now);
        recomputeCounts(state, operator, now);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ruleId", rule.getId());
        result.put("batchId", rule.getBatchId());
        result.put("regNo", personNo);
        result.put("released", true);
        result.put("offeredCount", offered);
        return result;
    }

    /** 发布新配置等场景的外部递补入口：锁状态行后按序递补空位。 */
    @Transactional
    public int promote(Long ruleId) {
        CapacityState state = lockState(ruleId);
        String operator = OperatorHolder.current();
        LocalDateTime now = LocalDateTime.now();
        int offered = promote(state, operator, now);
        recomputeCounts(state, operator, now);
        return offered;
    }

    // ---------- 超时扫描（定时任务 / 手动接口共用；顺延通知不漏发） ----------

    /** 跨规则扫描：每条规则独立事务，互不影响。 */
    public Map<String, Object> sweepExpired() {
        LocalDateTime now = LocalDateTime.now();
        List<CapacityState> states = stateMapper.selectList(new QueryWrapper<CapacityState>()
                .orderByAsc("id"));
        List<Long> processed = new ArrayList<>();
        int expiredTotal = 0;
        int offeredTotal = 0;
        int rejectedTotal = 0;
        for (CapacityState s : states) {
            Map<String, Object> r = self.processExpiry(s.getRuleId());
            processed.add(s.getRuleId());
            expiredTotal += (Integer) r.get("expiredCount");
            offeredTotal += (Integer) r.get("offeredCount");
            rejectedTotal += (Integer) r.get("rejectedCount");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sweptAt", now);
        result.put("ruleCount", processed.size());
        result.put("ruleIds", processed);
        result.put("expiredCount", expiredTotal);
        result.put("offeredCount", offeredTotal);
        result.put("rejectedCount", rejectedTotal);
        return result;
    }

    /**
     * 独立事务执行超时顺延：超时候补人在确认接口撞超时点时，顺延必须先落库再拒收确认，
     * 否则确认异常会把顺延一起回滚。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Map<String, Object> expireAndPromoteInNewTx(Long ruleId) {
        return processExpiry(ruleId);
    }

    /** 单规则超时处理：超时未确认置 EXPIRED（通知本人）→ 窗口关则拒收剩余候补 → 否则按序递补（通知下一位）。 */
    @Transactional
    public Map<String, Object> processExpiry(Long ruleId) {
        LocalDateTime now = LocalDateTime.now();
        String operator = "system";
        CapacityState state = lockState(ruleId);
        CapacityRuleVersion active = ruleService.findActiveVersion(ruleId);

        int expiredCount = 0;
        int rejectedCount = 0;

        if (active != null) {
            List<CapacityWaitlist> offered = waitlistMapper.selectList(new QueryWrapper<CapacityWaitlist>()
                    .eq("rule_id", ruleId)
                    .eq("status", CapacityConstants.WAIT_OFFERED)
                    .lt("offer_deadline", now)
                    .orderByAsc("offer_deadline", "id"));
            for (CapacityWaitlist row : offered) {
                expireOffer(row, state, operator, now);
                expiredCount++;
            }
        }

        int offeredCount = 0;
        String windowStatus = ruleService.windowStatus(state, now);
        if (active == null || CapacityConstants.WINDOW_CLOSED.equals(windowStatus)) {
            // 窗口关闭（或规则停用）：不再发新递补，仍在候补的人统一拒收并通知
            List<CapacityWaitlist> waiting = waitlistMapper.selectList(new QueryWrapper<CapacityWaitlist>()
                    .eq("rule_id", ruleId)
                    .eq("status", CapacityConstants.WAIT_WAITING)
                    .orderByAsc("seq"));
            String reason = active == null ? "容量规则已停用，候补终止"
                    : "候补窗口已于 " + state.getWindowEnd() + " 关闭";
            for (CapacityWaitlist row : waiting) {
                String from = row.getStatus();
                row.setStatus(CapacityConstants.WAIT_REJECTED);
                row.setFinishedAt(now);
                row.setFinishReason(reason);
                waitlistMapper.updateById(row);
                recordEvent(row, CapacityConstants.EVENT_REJECT, from, CapacityConstants.WAIT_REJECTED,
                        reason, operator, now);
                notificationService.notify(row.getRuleId(), row.getId(), row.getBatchId(), row.getRegNo(),
                        row.getName(), CapacityConstants.NOTIFY_REJECTED, "候补终止",
                        reason + "，感谢等候。", null, operator);
                rejectedCount++;
            }
        } else {
            offeredCount = promote(state, operator, now);
        }

        recomputeCounts(state, operator, now);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ruleId", ruleId);
        result.put("expiredCount", expiredCount);
        result.put("offeredCount", offeredCount);
        result.put("rejectedCount", rejectedCount);
        return result;
    }

    // ---------- 查询 / 视图 ----------

    @Transactional(readOnly = true)
    public WaitlistPositionView position(Long ruleId, Long batchId, String regNo) {
        CapacityRule rule = resolveRule(ruleId, batchId);
        if (regNo == null || regNo.trim().isEmpty()) {
            throw new BusinessException("CAP_REG_NO_REQUIRED", "报名号不能为空");
        }
        CapacityWaitlist row = waitlistMapper.selectOne(new QueryWrapper<CapacityWaitlist>()
                .eq("rule_id", rule.getId())
                .eq("reg_no", regNo.trim()));
        if (row == null) {
            throw new BusinessException("CAP_WAITLIST_NOT_FOUND",
                    "未找到候补记录：" + regNo.trim());
        }
        CapacityState state = ruleService.findState(rule.getId());
        return toPositionView(row, state, LocalDateTime.now());
    }

    @Transactional(readOnly = true)
    public List<CapacityWaitlist> list(Long ruleId, Long batchId, String status) {
        CapacityRule rule = resolveRule(ruleId, batchId);
        QueryWrapper<CapacityWaitlist> wrapper = new QueryWrapper<CapacityWaitlist>()
                .eq("rule_id", rule.getId())
                .orderByAsc("seq");
        if (status != null && !status.trim().isEmpty()) {
            wrapper.eq("status", status.trim().toUpperCase());
        }
        return waitlistMapper.selectList(wrapper);
    }

    @Transactional(readOnly = true)
    public List<CapacityWaitlistEvent> events(Long waitlistId) {
        CapacityWaitlist row = waitlistMapper.selectById(waitlistId);
        if (row == null) {
            throw new BusinessException("CAP_WAITLIST_NOT_FOUND", "候补记录不存在：id=" + waitlistId);
        }
        return eventMapper.selectList(new QueryWrapper<CapacityWaitlistEvent>()
                .eq("waitlist_id", waitlistId)
                .orderByAsc("event_at", "id"));
    }

    @Transactional(readOnly = true)
    public List<CapacityNotification> notifications(String regNo, Long waitlistId, boolean unreadOnly) {
        return notificationService.list(regNo, waitlistId, unreadOnly);
    }

    @Transactional(readOnly = true)
    public CapacityStateView stateView(Long ruleId) {
        CapacityRule rule = ruleService.getRule(ruleId);
        CapacityState state = ruleService.findState(ruleId);
        LocalDateTime now = LocalDateTime.now();
        ExamBatch batch = batchService.getBatch(rule.getBatchId());

        CapacityStateView view = new CapacityStateView();
        view.setRuleId(rule.getId());
        view.setRuleCode(rule.getCode());
        view.setRuleName(rule.getName());
        view.setSiteId(rule.getSiteId());
        view.setBatchId(rule.getBatchId());
        view.setBatchCode(batch.getCode());
        view.setNow(now);
        if (state == null) {
            view.setActive(false);
            view.setWindowStatus(CapacityConstants.WINDOW_NOT_CONFIGURED);
            view.setOccupiedCount(0);
            view.setOfferedCount(0);
            view.setWaitingCount(0);
            view.setSeatedCount(0);
            view.setAvailableCount(null);
            return view;
        }
        CapacityRuleVersion active = ruleService.findActiveVersion(ruleId);
        view.setCurrentVersionId(state.getCurrentVersionId());
        view.setCurrentVersionNo(state.getCurrentVersionNo());
        view.setVersionGeneration(state.getVersionGeneration());
        view.setActive(active != null);
        view.setWindowStart(state.getWindowStart());
        view.setWindowEnd(state.getWindowEnd());
        view.setWindowStatus(ruleService.windowStatus(state, now));
        view.setOfferTimeoutMinutes(state.getOfferTimeoutMinutes());
        view.setCapacity(state.getCapacity());
        int enrolled = enrolledCount(state.getBatchId());
        int offered = countByStatus(ruleId, CapacityConstants.WAIT_OFFERED);
        int waiting = countByStatus(ruleId, CapacityConstants.WAIT_WAITING);
        int seated = countByStatus(ruleId, CapacityConstants.WAIT_SEATED);
        int occupied = enrolled + offered;
        view.setOccupiedCount(occupied);
        view.setOfferedCount(offered);
        view.setWaitingCount(waiting);
        view.setSeatedCount(seated);
        view.setAvailableCount(Math.max(0, state.getCapacity() - occupied));
        return view;
    }

    // ---------- 内部：递补引擎 ----------

    /**
     * 有空位就按 seq 从小到大逐位递补，直到坐满或队列为空。
     * 每位：WAITING → OFFERED，写服务器钟点与确认截止点，发带时限通知。同事务完成，通知不漏。
     * 返回本轮发出递补数。
     */
    private int promote(CapacityState state, String operator, LocalDateTime now) {
        CapacityRuleVersion active = ruleService.findActiveVersion(state.getRuleId());
        if (active == null) {
            return 0;
        }
        if (!CapacityConstants.WINDOW_OPEN.equals(ruleService.windowStatus(state, now))) {
            return 0;
        }
        CapacityRule rule = ruleService.getRule(state.getRuleId());
        int offeredNow = 0;
        while (true) {
            int enrolled = enrolledCount(state.getBatchId());
            int offered = countByStatus(state.getRuleId(), CapacityConstants.WAIT_OFFERED);
            int occupied = enrolled + offered;
            if (occupied >= state.getCapacity()) {
                break;
            }
            CapacityWaitlist head = waitlistMapper.selectOne(new QueryWrapper<CapacityWaitlist>()
                    .eq("rule_id", state.getRuleId())
                    .eq("status", CapacityConstants.WAIT_WAITING)
                    .orderByAsc("seq")
                    .last("LIMIT 1"));
            if (head == null) {
                break;
            }
            LocalDateTime deadline = now.plusMinutes(state.getOfferTimeoutMinutes());
            String from = head.getStatus();
            head.setStatus(CapacityConstants.WAIT_OFFERED);
            head.setOfferedAt(now);
            head.setOfferDeadline(deadline);
            head.setOfferRound((head.getOfferRound() == null ? 0 : head.getOfferRound()) + 1);
            head.setFinishedAt(null);
            head.setFinishReason(null);
            waitlistMapper.updateById(head);
            recordEvent(head, CapacityConstants.EVENT_OFFER, from, CapacityConstants.WAIT_OFFERED,
                    String.format("第 %d 轮递补，须在 %s 前确认", head.getOfferRound(), deadline),
                    operator, now);
            notificationService.notify(state.getRuleId(), head.getId(), state.getBatchId(),
                    head.getRegNo(), head.getName(),
                    CapacityConstants.NOTIFY_OFFER, "候补递补通知：请在时限内确认",
                    String.format("您已获得 %s 的递补名额，请于 %s 前确认，逾期名额将顺给下一位。",
                            rule.getName(), deadline),
                    deadline, operator);
            offeredNow++;
        }
        return offeredNow;
    }

    /** 超时置 EXPIRED：作废本轮顺位并通知本人（下一位的递补通知由调用方 promote 发出）。 */
    private void expireOffer(CapacityWaitlist row, CapacityState state, String operator, LocalDateTime now) {
        String from = row.getStatus();
        row.setStatus(CapacityConstants.WAIT_EXPIRED);
        row.setFinishedAt(now);
        row.setFinishReason("第 " + row.getOfferRound() + " 轮递补超过确认时限（"
                + row.getOfferDeadline() + "）未确认，名额顺延");
        waitlistMapper.updateById(row);
        recordEvent(row, CapacityConstants.EVENT_EXPIRE, from, CapacityConstants.WAIT_EXPIRED,
                row.getFinishReason(), operator, now);
        notificationService.notify(row.getRuleId(), row.getId(), row.getBatchId(), row.getRegNo(), row.getName(),
                CapacityConstants.NOTIFY_EXPIRED, "递补超时，名额已顺延",
                String.format("您本轮递补未在 %s 前确认，名额已按序顺给下一位；可重新入队排队尾。",
                        row.getOfferDeadline()),
                null, operator);
    }

    // ---------- 内部：计数 / 定位 ----------

    /** 行锁取状态行；未发布过版本（无状态行）直接拒绝候补操作。 */
    private CapacityState lockState(Long ruleId) {
        CapacityState state = stateMapper.selectForUpdate(ruleId);
        if (state == null) {
            throw new BusinessException("CAP_NOT_ACTIVE", "容量规则尚未发布生效版本，候补未开放：ruleId=" + ruleId);
        }
        return state;
    }

    private void requireActiveAndOpen(Long ruleId, CapacityState state, LocalDateTime now) {
        CapacityRuleVersion active = ruleService.findActiveVersion(ruleId);
        if (active == null) {
            throw new BusinessException("CAP_NOT_ACTIVE", "容量规则无生效版本，候补未开放");
        }
        String window = ruleService.windowStatus(state, now);
        if (CapacityConstants.WINDOW_NOT_CONFIGURED.equals(window)) {
            throw new BusinessException("CAP_WINDOW_NOT_CONFIGURED", "该考点尚未配置候补窗口，无法入队");
        }
        if (CapacityConstants.WINDOW_NOT_OPENED.equals(window)) {
            throw new BusinessException("CAP_WINDOW_NOT_OPENED",
                    "候补窗口尚未开始（" + state.getWindowStart() + "），请在窗口内入队");
        }
        if (CapacityConstants.WINDOW_CLOSED.equals(window)) {
            throw new BusinessException("CAP_WINDOW_CLOSED",
                    "候补窗口已于 " + state.getWindowEnd() + " 关闭，无法入队");
        }
    }

    /** 重算并持久化实时计数（占座 = 在册未退位 + 待确认递补）。 */
    private void recomputeCounts(CapacityState state) {
        recomputeCounts(state, null, null);
    }

    private void recomputeCounts(CapacityState state, String operator, LocalDateTime now) {
        int enrolled = enrolledCount(state.getBatchId());
        int offered = countByStatus(state.getRuleId(), CapacityConstants.WAIT_OFFERED);
        int waiting = countByStatus(state.getRuleId(), CapacityConstants.WAIT_WAITING);
        int seated = countByStatus(state.getRuleId(), CapacityConstants.WAIT_SEATED);
        state.setOccupiedCount(enrolled + offered);
        state.setWaitingCount(waiting);
        state.setSeatedCount(seated);
        if (operator != null) {
            state.setUpdatedBy(operator);
            state.setUpdatedAt(now);
        }
        stateMapper.updateById(state);
    }

    private int enrolledCount(Long batchId) {
        Long c = registrationMapper.selectCount(new QueryWrapper<Registration>()
                .eq("batch_id", batchId)
                .ne("status", REG_WITHDRAWN));
        return c == null ? 0 : c.intValue();
    }

    private int countByStatus(Long ruleId, String status) {
        Long c = waitlistMapper.selectCount(new QueryWrapper<CapacityWaitlist>()
                .eq("rule_id", ruleId)
                .eq("status", status));
        return c == null ? 0 : c.intValue();
    }

    private boolean isEnrolled(Long batchId, String regNo) {
        Registration reg = findRegistration(batchId, regNo);
        // 已退位者不算在册，可重新候补
        return reg != null && !REG_WITHDRAWN.equals(reg.getStatus());
    }

    private Registration findRegistration(Long batchId, String regNo) {
        if (regNo == null) {
            return null;
        }
        return registrationMapper.selectOne(new QueryWrapper<Registration>()
                .eq("batch_id", batchId)
                .eq("reg_no", regNo.trim()));
    }

    /** 下一个入队序号：MAX(seq)+1，只增不回收，超时/退出/重排都不复用旧号。 */
    private int nextSeq(Long ruleId) {
        CapacityWaitlist top = waitlistMapper.selectOne(new QueryWrapper<CapacityWaitlist>()
                .eq("rule_id", ruleId)
                .orderByDesc("seq")
                .last("LIMIT 1"));
        return top == null || top.getSeq() == null ? 1 : top.getSeq() + 1;
    }

    private void recordEvent(CapacityWaitlist row, String type, String from, String to,
                             String detail, String operator, LocalDateTime at) {
        CapacityWaitlistEvent e = new CapacityWaitlistEvent();
        e.setWaitlistId(row.getId());
        e.setRuleId(row.getRuleId());
        e.setSeq(row.getSeq());
        e.setChangeType(type);
        e.setStatusFrom(from);
        e.setStatusTo(to);
        e.setDetail(detail);
        e.setOperator(operator);
        e.setEventAt(at);
        eventMapper.insert(e);
    }

    private CapacityWaitlist locate(Long waitlistId, Long ruleId, Long batchId, String regNo) {
        CapacityWaitlist row = null;
        if (waitlistId != null) {
            row = waitlistMapper.selectById(waitlistId);
        } else {
            CapacityRule rule = resolveRule(ruleId, batchId);
            if (regNo == null || regNo.trim().isEmpty()) {
                throw new BusinessException("CAP_REG_NO_REQUIRED", "报名号不能为空");
            }
            row = waitlistMapper.selectOne(new QueryWrapper<CapacityWaitlist>()
                    .eq("rule_id", rule.getId())
                    .eq("reg_no", regNo.trim()));
        }
        if (row == null) {
            throw new BusinessException("CAP_WAITLIST_NOT_FOUND", "候补记录不存在");
        }
        return row;
    }

    private CapacityRule resolveRule(Long ruleId, Long batchId) {
        if (ruleId != null) {
            return ruleService.getRule(ruleId);
        }
        return ruleService.getRuleByBatch(batchId);
    }

    private WaitlistPositionView toPositionView(CapacityWaitlist row, CapacityState state, LocalDateTime now) {
        WaitlistPositionView v = new WaitlistPositionView();
        v.setWaitlistId(row.getId());
        v.setRuleId(row.getRuleId());
        v.setBatchId(row.getBatchId());
        v.setRegNo(row.getRegNo());
        v.setName(row.getName());
        v.setSeq(row.getSeq());
        v.setStatus(row.getStatus());
        v.setOfferRound(row.getOfferRound());
        v.setOfferedAt(row.getOfferedAt());
        v.setOfferDeadline(row.getOfferDeadline());
        v.setConfirmedAt(row.getConfirmedAt());
        v.setFinishReason(row.getFinishReason());
        if (CapacityConstants.WAIT_WAITING.equals(row.getStatus())) {
            // 真实顺位：候补中 seq 比我小的人数 +1
            Long ahead = waitlistMapper.selectCount(new QueryWrapper<CapacityWaitlist>()
                    .eq("rule_id", row.getRuleId())
                    .eq("status", CapacityConstants.WAIT_WAITING)
                    .lt("seq", row.getSeq()));
            int aheadCount = ahead == null ? 0 : ahead.intValue();
            v.setAheadCount(aheadCount);
            v.setPosition(aheadCount + 1);
        } else if (CapacityConstants.WAIT_OFFERED.equals(row.getStatus())) {
            v.setAheadCount(0);
            v.setPosition(0);
        }
        return v;
    }

    private String appendRemark(String oldRemark, String piece) {
        if (oldRemark == null || oldRemark.trim().isEmpty()) {
            return piece;
        }
        return oldRemark + "；" + piece;
    }
}
