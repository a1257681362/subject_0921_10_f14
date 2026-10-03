package com.evops.capacity.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.capacity.common.CapacityConstants;
import com.evops.capacity.dto.CapacityParams;
import com.evops.capacity.dto.CapacityRuleRequest;
import com.evops.capacity.dto.CapacityRuleVersionRequest;
import com.evops.capacity.entity.CapacityRule;
import com.evops.capacity.entity.CapacityRuleVersion;
import com.evops.capacity.entity.CapacityState;
import com.evops.capacity.mapper.CapacityRuleMapper;
import com.evops.capacity.mapper.CapacityRuleVersionMapper;
import com.evops.capacity.mapper.CapacityStateMapper;
import com.evops.common.BusinessException;
import com.evops.common.OperatorHolder;
import com.evops.exam.entity.ExamBatch;
import com.evops.exam.service.ExamBatchService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 容量规则模块（模块 + 版本 + 状态）：
 * - 规则按 考点+批次 建档（考点取批次所属考点），一个批次一条规则；
 * - 版本 DRAFT → ACTIVE → DISABLED：发布即冻结窗口/时限/容量 JSON，自动停用同规则旧生效版；
 * - 发布/停用同步刷新 t_capacity_state（配置快照 + 代次 + 实时计数），入队与递补只认生效版；
 * - 发布后若腾出空位，立即触发按序递补（候补队列服务）。
 */
@Service
public class CapacityRuleService {

    private final CapacityRuleMapper ruleMapper;
    private final CapacityRuleVersionMapper versionMapper;
    private final CapacityStateMapper stateMapper;
    private final ExamBatchService batchService;
    private final WaitlistService waitlistService;
    private final ObjectMapper objectMapper;

    public CapacityRuleService(CapacityRuleMapper ruleMapper,
                               CapacityRuleVersionMapper versionMapper,
                               CapacityStateMapper stateMapper,
                               ExamBatchService batchService,
                               @Lazy WaitlistService waitlistService,
                               ObjectMapper objectMapper) {
        this.ruleMapper = ruleMapper;
        this.versionMapper = versionMapper;
        this.stateMapper = stateMapper;
        this.batchService = batchService;
        this.waitlistService = waitlistService;
        this.objectMapper = objectMapper;
    }

    // ---------- 规则模块 ----------

    @Transactional
    public CapacityRule createRule(CapacityRuleRequest request) {
        ExamBatch batch = batchService.getBatch(request.getBatchId());
        CapacityRule existing = ruleMapper.selectOne(
                new QueryWrapper<CapacityRule>().eq("batch_id", batch.getId()));
        if (existing != null) {
            throw new BusinessException("CAP_RULE_BATCH_EXISTS",
                    "批次 " + batch.getCode() + " 已存在容量规则（id=" + existing.getId() + "），一批次只许一条");
        }

        CapacityRule rule = new CapacityRule();
        String code = request.getCode() == null || request.getCode().trim().isEmpty()
                ? "CAP" + batch.getId() : request.getCode().trim();
        rule.setCode(code);
        rule.setName(request.getName().trim());
        rule.setSiteId(batch.getSiteId());
        rule.setBatchId(batch.getId());
        rule.setRemark(request.getRemark());
        try {
            ruleMapper.insert(rule);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("DUPLICATE_CAP_RULE_CODE", "容量规则编码已存在：" + code);
        }
        return rule;
    }

    public List<CapacityRule> listRules(Long siteId, Long batchId) {
        QueryWrapper<CapacityRule> wrapper = new QueryWrapper<CapacityRule>()
                .orderByAsc("site_id", "code");
        if (siteId != null) {
            wrapper.eq("site_id", siteId);
        }
        if (batchId != null) {
            wrapper.eq("batch_id", batchId);
        }
        return ruleMapper.selectList(wrapper);
    }

    public CapacityRule getRule(Long ruleId) {
        if (ruleId == null) {
            throw new BusinessException("CAP_RULE_ID_REQUIRED", "容量规则 id 不能为空");
        }
        CapacityRule rule = ruleMapper.selectById(ruleId);
        if (rule == null) {
            throw new BusinessException("CAP_RULE_NOT_FOUND", "容量规则不存在：id=" + ruleId);
        }
        return rule;
    }

    /** 按批次定位规则（候补接口支持用 batchId 入参）。 */
    public CapacityRule getRuleByBatch(Long batchId) {
        if (batchId == null) {
            throw new BusinessException("CAP_BATCH_ID_REQUIRED", "批次 id 不能为空");
        }
        batchService.getBatch(batchId);
        CapacityRule rule = ruleMapper.selectOne(
                new QueryWrapper<CapacityRule>().eq("batch_id", batchId));
        if (rule == null) {
            throw new BusinessException("CAP_RULE_NOT_FOUND", "该批次尚未配置容量规则：batchId=" + batchId);
        }
        return rule;
    }

    // ---------- 规则版本 ----------

    /** 新建草稿版本：校验通过后把窗口/时限/容量以 JSON 冻结落库（草稿也不允许半截配置）。 */
    @Transactional
    public CapacityRuleVersion createDraftVersion(Long ruleId, CapacityRuleVersionRequest request) {
        CapacityRule rule = getRule(ruleId);
        CapacityParams normalized = validateParams(request.getParams());

        CapacityRuleVersion version = new CapacityRuleVersion();
        version.setRuleId(rule.getId());
        version.setVersionNo(nextVersionNo(rule.getId()));
        version.setStatus(CapacityConstants.VERSION_DRAFT);
        version.setParams(writeParams(normalized));
        version.setRemark(request.getRemark());
        version.setOperator(OperatorHolder.current());
        versionMapper.insert(version);
        return version;
    }

    /** 发布：DRAFT → ACTIVE；同一规则旧 ACTIVE 自动置 DISABLED；刷新状态行并立即递补空位。 */
    @Transactional
    public CapacityRuleVersion publishVersion(Long versionId) {
        CapacityRuleVersion version = requireVersion(versionId);
        String operator = OperatorHolder.current();
        LocalDateTime now = LocalDateTime.now();

        if (CapacityConstants.VERSION_ACTIVE.equals(version.getStatus())) {
            throw new BusinessException("CAP_VERSION_ALREADY_ACTIVE",
                    "版本已是生效状态，无需重复发布：" + version.getVersionNo());
        }
        if (CapacityConstants.VERSION_DISABLED.equals(version.getStatus())) {
            throw new BusinessException("CAP_VERSION_DISABLED",
                    "停用版本不可重新发布，请新建版本：" + version.getVersionNo());
        }

        List<CapacityRuleVersion> actives = versionMapper.selectList(new QueryWrapper<CapacityRuleVersion>()
                .eq("rule_id", version.getRuleId())
                .eq("status", CapacityConstants.VERSION_ACTIVE));
        for (CapacityRuleVersion active : actives) {
            active.setStatus(CapacityConstants.VERSION_DISABLED);
            active.setDisabledBy(operator);
            active.setDisabledAt(now);
            versionMapper.updateById(active);
        }

        version.setStatus(CapacityConstants.VERSION_ACTIVE);
        version.setPublishedBy(operator);
        version.setPublishedAt(now);
        versionMapper.updateById(version);

        applyStateOnPublish(version, operator, now);
        // 配置变更可能腾出空位（如调大容量），立即按序递补，不让候补人干等。
        waitlistService.promote(version.getRuleId());
        return version;
    }

    /** 人工停用生效版：候补/入队随即停止（状态行 active 按版本状态计算）。 */
    @Transactional
    public CapacityRuleVersion disableVersion(Long versionId) {
        CapacityRuleVersion version = requireVersion(versionId);
        if (!CapacityConstants.VERSION_ACTIVE.equals(version.getStatus())) {
            throw new BusinessException("CAP_VERSION_NOT_ACTIVE",
                    "仅生效版本可停用：" + version.getVersionNo());
        }
        version.setStatus(CapacityConstants.VERSION_DISABLED);
        version.setDisabledBy(OperatorHolder.current());
        version.setDisabledAt(LocalDateTime.now());
        versionMapper.updateById(version);

        CapacityState state = stateMapper.selectOne(
                new QueryWrapper<CapacityState>().eq("rule_id", version.getRuleId()));
        if (state != null) {
            state.setVersionGeneration((state.getVersionGeneration() == null ? 0 : state.getVersionGeneration()) + 1);
            state.setUpdatedBy(OperatorHolder.current());
            state.setUpdatedAt(LocalDateTime.now());
            stateMapper.updateById(state);
        }
        return version;
    }

    @Transactional(readOnly = true)
    public List<CapacityRuleVersion> listVersions(Long ruleId) {
        getRule(ruleId);
        return versionMapper.selectList(new QueryWrapper<CapacityRuleVersion>()
                .eq("rule_id", ruleId)
                .orderByDesc("id"));
    }

    public CapacityRuleVersion getVersion(Long versionId) {
        return requireVersion(versionId);
    }

    // ---------- 状态行 ----------

    /** 规则当前状态行（可能为 null：尚未发布过任何版本）。 */
    public CapacityState findState(Long ruleId) {
        return stateMapper.selectOne(new QueryWrapper<CapacityState>().eq("rule_id", ruleId));
    }

    /** 规则当前生效版本；无生效版返回 null。 */
    public CapacityRuleVersion findActiveVersion(Long ruleId) {
        List<CapacityRuleVersion> actives = versionMapper.selectList(new QueryWrapper<CapacityRuleVersion>()
                .eq("rule_id", ruleId)
                .eq("status", CapacityConstants.VERSION_ACTIVE)
                .orderByDesc("id"));
        if (actives.isEmpty()) {
            return null;
        }
        if (actives.size() > 1) {
            throw new BusinessException("CAP_MULTIPLE_ACTIVE",
                    "容量规则存在多个生效版本，请先停用多余版本：ruleId=" + ruleId);
        }
        return actives.get(0);
    }

    /** 计算窗口状态：NOT_CONFIGURED / NOT_OPENED / OPEN / CLOSED（只认服务器钟点）。 */
    public String windowStatus(CapacityState state, LocalDateTime now) {
        if (state == null || state.getWindowStart() == null || state.getWindowEnd() == null) {
            return CapacityConstants.WINDOW_NOT_CONFIGURED;
        }
        if (now.isBefore(state.getWindowStart())) {
            return CapacityConstants.WINDOW_NOT_OPENED;
        }
        if (now.isAfter(state.getWindowEnd())) {
            return CapacityConstants.WINDOW_CLOSED;
        }
        return CapacityConstants.WINDOW_OPEN;
    }

    // ---------- 参数校验 / 序列化 ----------

    /** 校验窗口/时限/容量，返回用于冻结的规范化参数。 */
    public CapacityParams validateParams(CapacityParams raw) {
        if (raw == null) {
            throw new BusinessException("CAP_PARAMS_REQUIRED", "容量参数不能为空");
        }
        CapacityParams norm = new CapacityParams();
        boolean startMissing = raw.getWindowStart() == null;
        boolean endMissing = raw.getWindowEnd() == null;
        if (startMissing != endMissing) {
            throw new BusinessException("CAP_WINDOW_HALF", "候补窗口起止必须同时配置或同时留空");
        }
        if (!startMissing && !raw.getWindowStart().isBefore(raw.getWindowEnd())) {
            throw new BusinessException("CAP_WINDOW_RANGE", "候补窗口开始时间必须早于结束时间");
        }
        norm.setWindowStart(raw.getWindowStart());
        norm.setWindowEnd(raw.getWindowEnd());

        if (raw.getOfferTimeoutMinutes() == null) {
            throw new BusinessException("CAP_TIMEOUT_REQUIRED", "递补确认时限（分钟）不能为空");
        }
        if (raw.getOfferTimeoutMinutes() < 1) {
            throw new BusinessException("CAP_TIMEOUT_INVALID", "递补确认时限必须 ≥ 1 分钟");
        }
        if (raw.getOfferTimeoutMinutes() > CapacityConstants.MAX_OFFER_TIMEOUT_MINUTES) {
            throw new BusinessException("CAP_TIMEOUT_INVALID",
                    "递补确认时限不能超过 " + CapacityConstants.MAX_OFFER_TIMEOUT_MINUTES + " 分钟");
        }
        norm.setOfferTimeoutMinutes(raw.getOfferTimeoutMinutes());

        if (raw.getCapacity() == null || raw.getCapacity() < 1) {
            throw new BusinessException("CAP_CAPACITY_INVALID", "放号容量必须为正整数");
        }
        norm.setCapacity(raw.getCapacity());
        return norm;
    }

    public String writeParams(CapacityParams params) {
        try {
            return objectMapper.writeValueAsString(params);
        } catch (Exception e) {
            throw new BusinessException("CAP_PARAMS_SERIALIZE_FAILED", "容量参数序列化失败：" + e.getMessage());
        }
    }

    public CapacityParams readParams(String json) {
        try {
            CapacityParams params = objectMapper.readValue(json, new TypeReference<CapacityParams>() {
            });
            if (params == null) {
                throw new BusinessException("CAP_PARAMS_BROKEN", "容量版本参数为空或已损坏");
            }
            return params;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("CAP_PARAMS_BROKEN", "容量版本参数无法解析：" + e.getMessage());
        }
    }

    // ---------- 内部 ----------

    /** 发布后把生效配置快照落到状态行（存在则刷新，代次 +1）。实时计数由候补服务统一重算。 */
    private void applyStateOnPublish(CapacityRuleVersion version, String operator, LocalDateTime now) {
        CapacityRule rule = getRule(version.getRuleId());
        CapacityParams params = readParams(version.getParams());

        CapacityState state = stateMapper.selectOne(
                new QueryWrapper<CapacityState>().eq("rule_id", rule.getId()));
        boolean isNew = state == null;
        if (isNew) {
            state = new CapacityState();
            state.setRuleId(rule.getId());
            state.setVersionGeneration(0);
            state.setOccupiedCount(0);
            state.setWaitingCount(0);
            state.setSeatedCount(0);
        }
        state.setSiteId(rule.getSiteId());
        state.setBatchId(rule.getBatchId());
        state.setCurrentVersionId(version.getId());
        state.setCurrentVersionNo(version.getVersionNo());
        state.setVersionGeneration((state.getVersionGeneration() == null ? 0 : state.getVersionGeneration()) + 1);
        state.setWindowStart(params.getWindowStart());
        state.setWindowEnd(params.getWindowEnd());
        state.setOfferTimeoutMinutes(params.getOfferTimeoutMinutes());
        state.setCapacity(params.getCapacity());
        state.setUpdatedBy(operator);
        state.setUpdatedAt(now);
        if (isNew) {
            stateMapper.insert(state);
        } else {
            stateMapper.updateById(state);
        }
    }

    private CapacityRuleVersion requireVersion(Long versionId) {
        if (versionId == null) {
            throw new BusinessException("CAP_VERSION_ID_REQUIRED", "容量版本 id 不能为空");
        }
        CapacityRuleVersion version = versionMapper.selectById(versionId);
        if (version == null) {
            throw new BusinessException("CAP_VERSION_NOT_FOUND", "容量规则版本不存在：id=" + versionId);
        }
        return version;
    }

    /** 版本号：规则 code + A + 递增序号（如 CAP1-A1）。 */
    private String nextVersionNo(Long ruleId) {
        CapacityRule rule = getRule(ruleId);
        int max = 0;
        String prefix = rule.getCode() + "-A";
        for (CapacityRuleVersion v : versionMapper.selectList(
                new QueryWrapper<CapacityRuleVersion>().eq("rule_id", ruleId))) {
            String no = v.getVersionNo();
            if (no != null && no.startsWith(prefix)) {
                try {
                    max = Math.max(max, Integer.parseInt(no.substring(prefix.length())));
                } catch (NumberFormatException ignored) {
                    // 非本规则序号忽略
                }
            }
        }
        return prefix + (max + 1);
    }
}
