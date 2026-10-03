package com.evops.invigilatefee.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.common.BusinessException;
import com.evops.common.OperatorHolder;
import com.evops.invigilatefee.common.InvigilateFeeConstants;
import com.evops.invigilatefee.dto.FeeModeRate;
import com.evops.invigilatefee.dto.FeeRuleRequest;
import com.evops.invigilatefee.dto.FeeRuleVersionRequest;
import com.evops.invigilatefee.entity.FeeTransportRule;
import com.evops.invigilatefee.entity.FeeTransportRuleVersion;
import com.evops.invigilatefee.mapper.FeeTransportRuleMapper;
import com.evops.invigilatefee.mapper.FeeTransportRuleVersionMapper;
import com.evops.invigilatefee.vo.FeeEstimate;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 交通补贴规则（模块 + 版本 + 状态）：
 * - 规则按项目建档，下挂多版本；版本 DRAFT→ACTIVE→DISABLED，发布即冻结各交通方式单价 / 封顶与异常阈值；
 * - 报销金额按“出行日期”命中的生效版本计算（旧版停用后按其发布/停用区间仍可命中历史日期）；
 * - 估算 = min(里程单价×距离, 每程封顶)；应付 = min(估算, 票面合计)；异常随版本阈值判定；
 * - 同规则同时刻仅一版 ACTIVE，停用不删，历史申请保留版本号。
 */
@Service
public class FeeRuleService {

    private static final BigDecimal DEFAULT_ANOMALY_RATE = new BigDecimal("0.20");

    private final FeeTransportRuleMapper ruleMapper;
    private final FeeTransportRuleVersionMapper versionMapper;
    private final ObjectMapper objectMapper;

    public FeeRuleService(FeeTransportRuleMapper ruleMapper,
                          FeeTransportRuleVersionMapper versionMapper,
                          ObjectMapper objectMapper) {
        this.ruleMapper = ruleMapper;
        this.versionMapper = versionMapper;
        this.objectMapper = objectMapper;
    }

    // ---------- 规则模块 ----------

    @Transactional
    public FeeTransportRule createRule(FeeRuleRequest request) {
        FeeTransportRule rule = new FeeTransportRule();
        rule.setCode(request.getCode().trim());
        rule.setName(request.getName().trim());
        rule.setProjectCode(normalizeProject(request.getProjectCode()));
        rule.setStatus(InvigilateFeeConstants.ENABLED);
        rule.setRemark(request.getRemark());
        try {
            ruleMapper.insert(rule);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("DUPLICATE_FEE_RULE_CODE", "交通补贴规则编码已存在：" + rule.getCode());
        }
        return rule;
    }

    @Transactional(readOnly = true)
    public List<FeeTransportRule> listRules(String projectCode) {
        QueryWrapper<FeeTransportRule> wrapper = new QueryWrapper<FeeTransportRule>().orderByAsc("project_code", "code");
        if (projectCode != null && !projectCode.trim().isEmpty()) {
            wrapper.eq("project_code", projectCode.trim().toUpperCase());
        }
        return ruleMapper.selectList(wrapper);
    }

    public FeeTransportRule getRule(Long ruleId) {
        FeeTransportRule rule = ruleMapper.selectById(ruleId);
        if (rule == null) {
            throw new BusinessException("FEE_RULE_NOT_FOUND", "交通补贴规则不存在：id=" + ruleId);
        }
        return rule;
    }

    // ---------- 规则版本 ----------

    @Transactional
    public FeeTransportRuleVersion createDraftVersion(Long ruleId, FeeRuleVersionRequest request) {
        FeeTransportRule rule = getRule(ruleId);
        List<FeeModeRate> modes = validateModes(request.getModes());
        BigDecimal anomalyRate = request.getAnomalyRate() == null
                ? DEFAULT_ANOMALY_RATE
                : request.getAnomalyRate().setScale(4, BigDecimal.ROUND_HALF_UP);
        BigDecimal limit = request.getAnomalyAmountLimit() == null
                ? null : request.getAnomalyAmountLimit().setScale(2, BigDecimal.ROUND_HALF_UP);

        FeeTransportRuleVersion version = new FeeTransportRuleVersion();
        version.setRuleId(rule.getId());
        version.setVersionNo(nextVersionNo(rule.getId()));
        version.setStatus(InvigilateFeeConstants.VERSION_DRAFT);
        version.setModesJson(writeModes(modes));
        version.setAnomalyRate(anomalyRate);
        version.setAnomalyAmountLimit(limit);
        version.setRemark(request.getRemark());
        version.setOperator(OperatorHolder.current());
        versionMapper.insert(version);
        return version;
    }

    /** 发布：DRAFT→ACTIVE；同规则旧 ACTIVE 自动 DISABLED（记录停用钟点，供按日期回溯历史口径）。 */
    @Transactional
    public FeeTransportRuleVersion publishVersion(Long versionId) {
        FeeTransportRuleVersion version = requireVersion(versionId);
        String operator = OperatorHolder.current();
        LocalDateTime now = LocalDateTime.now();

        if (InvigilateFeeConstants.VERSION_ACTIVE.equals(version.getStatus())) {
            throw new BusinessException("FEE_VERSION_ALREADY_ACTIVE",
                    "版本已是生效状态，无需重复发布：" + version.getVersionNo());
        }
        if (InvigilateFeeConstants.VERSION_DISABLED.equals(version.getStatus())) {
            throw new BusinessException("FEE_VERSION_DISABLED",
                    "停用版本不可重新发布，请新建版本：" + version.getVersionNo());
        }

        List<FeeTransportRuleVersion> actives = versionMapper.selectList(new QueryWrapper<FeeTransportRuleVersion>()
                .eq("rule_id", version.getRuleId())
                .eq("status", InvigilateFeeConstants.VERSION_ACTIVE));
        for (FeeTransportRuleVersion active : actives) {
            active.setStatus(InvigilateFeeConstants.VERSION_DISABLED);
            active.setDisabledBy(operator);
            active.setDisabledAt(now);
            versionMapper.updateById(active);
        }

        version.setStatus(InvigilateFeeConstants.VERSION_ACTIVE);
        version.setPublishedBy(operator);
        version.setPublishedAt(now);
        versionMapper.updateById(version);
        return version;
    }

    @Transactional
    public FeeTransportRuleVersion disableVersion(Long versionId) {
        FeeTransportRuleVersion version = requireVersion(versionId);
        if (!InvigilateFeeConstants.VERSION_ACTIVE.equals(version.getStatus())) {
            throw new BusinessException("FEE_VERSION_NOT_ACTIVE", "仅生效版本可停用：" + version.getVersionNo());
        }
        version.setStatus(InvigilateFeeConstants.VERSION_DISABLED);
        version.setDisabledBy(OperatorHolder.current());
        version.setDisabledAt(LocalDateTime.now());
        versionMapper.updateById(version);
        return version;
    }

    @Transactional(readOnly = true)
    public List<FeeTransportRuleVersion> listVersions(Long ruleId) {
        getRule(ruleId);
        return versionMapper.selectList(new QueryWrapper<FeeTransportRuleVersion>()
                .eq("rule_id", ruleId).orderByDesc("id"));
    }

    public FeeTransportRuleVersion getVersion(Long versionId) {
        return requireVersion(versionId);
    }

    // ---------- 按出行日期选生效版本 ----------

    /**
     * 取项目在指定出行日期适用的规则版本：先项目专用，再回落 DEFAULT 通用。
     * 命中口径：ACTIVE 且 published_at ≤ 当日；或 DISABLED 且 published_at ≤ 当日 ≤ disabled_at。
     * 同时刻仅一版 ACTIVE，故候选应唯一；多条命中视为口径冲突报错。
     */
    @Transactional(readOnly = true)
    public FeeTransportRuleVersion requireActiveVersion(String projectCode, LocalDate travelDate) {
        LocalDate day = travelDate == null ? LocalDate.now() : travelDate;
        FeeTransportRule rule = resolveRule(projectCode);

        List<FeeTransportRuleVersion> all = versionMapper.selectList(new QueryWrapper<FeeTransportRuleVersion>()
                .eq("rule_id", rule.getId()));
        List<FeeTransportRuleVersion> hit = new ArrayList<>();
        for (FeeTransportRuleVersion v : all) {
            if (covers(v, day)) {
                hit.add(v);
            }
        }
        if (hit.isEmpty()) {
            throw new BusinessException("FEE_NO_ACTIVE_VERSION_ON_DATE",
                    "交通补贴规则 " + rule.getCode() + " 在出行日期 " + day + " 没有生效版本，请先发布规则版本");
        }
        if (hit.size() > 1) {
            throw new BusinessException("FEE_VERSION_AMBIGUOUS",
                    "规则 " + rule.getCode() + " 在 " + day + " 命中多个生效版本，口径冲突，请核对发布/停用区间");
        }
        return hit.get(0);
    }

    /** 解析项目对应唯一启用规则；项目无则回落 DEFAULT；多规则 / 无规则报错。 */
    private FeeTransportRule resolveRule(String projectCode) {
        String project = normalizeProject(projectCode);
        FeeTransportRule exact = pickSingleRule(project);
        if (exact != null) {
            return exact;
        }
        if (!InvigilateFeeConstants.PROJECT_DEFAULT.equals(project)) {
            FeeTransportRule fallback = pickSingleRule(InvigilateFeeConstants.PROJECT_DEFAULT);
            if (fallback != null) {
                return fallback;
            }
        }
        throw new BusinessException("FEE_RULE_NOT_CONFIGURED",
                "项目 " + project + " 尚未配置启用的交通补贴规则，请先建档并发布版本");
    }

    private FeeTransportRule pickSingleRule(String project) {
        List<FeeTransportRule> rules = ruleMapper.selectList(new QueryWrapper<FeeTransportRule>()
                .eq("project_code", project)
                .eq("status", InvigilateFeeConstants.ENABLED));
        if (rules.isEmpty()) {
            return null;
        }
        if (rules.size() > 1) {
            throw new BusinessException("FEE_RULE_AMBIGUOUS",
                    "项目 " + project + " 存在多条启用交通补贴规则，无法唯一取价，请按项目配一条");
        }
        return rules.get(0);
    }

    private boolean covers(FeeTransportRuleVersion v, LocalDate day) {
        if (v.getPublishedAt() == null) {
            return false;
        }
        LocalDate published = v.getPublishedAt().toLocalDate();
        if (day.isBefore(published)) {
            return false;
        }
        if (InvigilateFeeConstants.VERSION_ACTIVE.equals(v.getStatus())) {
            return true;
        }
        if (InvigilateFeeConstants.VERSION_DISABLED.equals(v.getStatus())) {
            // 生效区间止日取不含：停用当天即由新发布版本生效，避免发布日两版同时命中造成歧义
            return v.getDisabledAt() == null || day.isBefore(v.getDisabledAt().toLocalDate());
        }
        return false;
    }

    // ---------- 估算与异常判定 ----------

    /**
     * 按版本口径估算金额：里程单价×距离→每程封顶→与票面合计取小；异常随版本阈值判定。
     */
    public FeeEstimate estimate(FeeTransportRuleVersion version, String transportMode,
                                BigDecimal distanceKm, BigDecimal declaredTotal) {
        String mode = normalizeMode(transportMode);
        List<FeeModeRate> modes = readModes(version.getModesJson());
        FeeModeRate modeRate = null;
        for (FeeModeRate m : modes) {
            if (mode.equals(m.getTransportMode())) {
                modeRate = m;
                break;
            }
        }
        if (modeRate == null) {
            throw new BusinessException("FEE_MODE_NOT_CONFIGURED",
                    "规则版本 " + version.getVersionNo() + " 未配置交通方式 " + mode + " 的计补口径");
        }

        BigDecimal km = distanceKm.setScale(2, BigDecimal.ROUND_HALF_UP);
        BigDecimal price = modeRate.getPricePerKm().setScale(2, BigDecimal.ROUND_HALF_UP);
        BigDecimal mileage = price.multiply(km).setScale(2, BigDecimal.ROUND_HALF_UP);
        BigDecimal estimated = mileage;
        if (modeRate.getCapPerTrip() != null) {
            BigDecimal cap = modeRate.getCapPerTrip().setScale(2, BigDecimal.ROUND_HALF_UP);
            if (estimated.compareTo(cap) > 0) {
                estimated = cap;
            }
        }
        BigDecimal declared = (declaredTotal == null ? BigDecimal.ZERO : declaredTotal)
                .setScale(2, BigDecimal.ROUND_HALF_UP);
        BigDecimal payable = estimated.min(declared);

        FeeEstimate est = new FeeEstimate();
        est.setTransportMode(mode);
        est.setDistanceKm(km);
        est.setPricePerKm(price);
        est.setCapPerTrip(modeRate.getCapPerTrip() == null ? null
                : modeRate.getCapPerTrip().setScale(2, BigDecimal.ROUND_HALF_UP));
        est.setMileageAmount(mileage);
        est.setEstimatedAmount(estimated);
        est.setDeclaredAmount(declared);
        est.setPayableAmount(payable);
        est.setAnomalyRate(version.getAnomalyRate());
        est.setAnomalyAmountLimit(version.getAnomalyAmountLimit());

        // 异常判定：无票 / 票面与估算偏差超阈值 / 票面超绝对上限
        if (declared.compareTo(BigDecimal.ZERO) <= 0) {
            est.setAbnormal(true);
            est.getAnomalyReasons().add("票面合计为 0，缺少有效审批票据");
        } else {
            BigDecimal rate = version.getAnomalyRate() == null ? DEFAULT_ANOMALY_RATE : version.getAnomalyRate();
            if (estimated.compareTo(BigDecimal.ZERO) <= 0) {
                est.setAbnormal(true);
                est.getAnomalyReasons().add("系统按里程估算为 0，但票面申报 " + declared.toPlainString()
                        + " 元，金额无法对应，转人工核对");
            } else {
                BigDecimal diff = declared.subtract(estimated).abs();
                BigDecimal ratio = diff.divide(estimated, 4, BigDecimal.ROUND_HALF_UP);
                if (ratio.compareTo(rate) > 0) {
                    est.setAbnormal(true);
                    est.getAnomalyReasons().add("票面合计 " + declared.toPlainString() + " 元与系统估算 "
                            + estimated.toPlainString() + " 元偏差 "
                            + ratio.multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString()
                            + "%，超过阈值 "
                            + rate.multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString() + "%");
                }
            }
            if (version.getAnomalyAmountLimit() != null
                    && declared.compareTo(version.getAnomalyAmountLimit()) > 0) {
                est.setAbnormal(true);
                est.getAnomalyReasons().add("票面合计 " + declared.toPlainString() + " 元超过异常金额上限 "
                        + version.getAnomalyAmountLimit().toPlainString() + " 元");
            }
        }
        return est;
    }

    // ---------- 交通方式口径校验 / 序列化 ----------

    public List<FeeModeRate> validateModes(List<FeeModeRate> raw) {
        if (raw == null || raw.isEmpty()) {
            throw new BusinessException("FEE_MODES_REQUIRED", "交通方式计补口径不能为空");
        }
        List<FeeModeRate> modes = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < raw.size(); i++) {
            FeeModeRate m = raw.get(i);
            if (m == null || m.getTransportMode() == null || m.getTransportMode().trim().isEmpty()) {
                throw new BusinessException("FEE_MODE_REQUIRED", "第 " + (i + 1) + " 项交通方式不能为空");
            }
            String mode = normalizeMode(m.getTransportMode());
            if (m.getPricePerKm() == null) {
                throw new BusinessException("FEE_PRICE_REQUIRED", "交通方式 " + mode + " 的里程单价不能为空");
            }
            if (m.getPricePerKm().compareTo(BigDecimal.ZERO) < 0) {
                throw new BusinessException("FEE_PRICE_INVALID", "交通方式 " + mode + " 的里程单价不能为负");
            }
            if (m.getCapPerTrip() != null && m.getCapPerTrip().compareTo(BigDecimal.ZERO) < 0) {
                throw new BusinessException("FEE_CAP_INVALID", "交通方式 " + mode + " 的每程封顶不能为负");
            }
            if (!seen.add(mode)) {
                throw new BusinessException("FEE_MODE_DUPLICATED", "交通方式重复配置：" + mode);
            }
            FeeModeRate norm = new FeeModeRate();
            norm.setTransportMode(mode);
            norm.setPricePerKm(m.getPricePerKm().setScale(2, BigDecimal.ROUND_HALF_UP));
            norm.setCapPerTrip(m.getCapPerTrip() == null ? null
                    : m.getCapPerTrip().setScale(2, BigDecimal.ROUND_HALF_UP));
            modes.add(norm);
        }
        modes.sort(Comparator.comparing(FeeModeRate::getTransportMode));
        return modes;
    }

    public String writeModes(List<FeeModeRate> modes) {
        try {
            return objectMapper.writeValueAsString(modes);
        } catch (Exception e) {
            throw new BusinessException("FEE_MODES_SERIALIZE_FAILED", "交通方式口径序列化失败：" + e.getMessage());
        }
    }

    public List<FeeModeRate> readModes(String json) {
        try {
            List<FeeModeRate> modes = objectMapper.readValue(json, new TypeReference<List<FeeModeRate>>() {
            });
            if (modes == null || modes.isEmpty()) {
                throw new BusinessException("FEE_MODES_BROKEN", "规则版本交通方式口径为空或已损坏");
            }
            return modes;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("FEE_MODES_BROKEN", "规则版本交通方式口径无法解析：" + e.getMessage());
        }
    }

    /** 供估算回填规则信息。 */
    public void fillRuleInfo(FeeEstimate est, FeeTransportRule rule, FeeTransportRuleVersion version) {
        est.setProjectCode(rule.getProjectCode());
        est.setRuleCode(rule.getCode());
        est.setRuleVersionNo(version.getVersionNo());
    }

    // ---------- 内部 ----------

    private FeeTransportRuleVersion requireVersion(Long versionId) {
        if (versionId == null) {
            throw new BusinessException("FEE_VERSION_ID_REQUIRED", "规则版本 id 不能为空");
        }
        FeeTransportRuleVersion version = versionMapper.selectById(versionId);
        if (version == null) {
            throw new BusinessException("FEE_RULE_VERSION_NOT_FOUND", "交通补贴规则版本不存在：id=" + versionId);
        }
        return version;
    }

    private String nextVersionNo(Long ruleId) {
        FeeTransportRule rule = getRule(ruleId);
        int max = 0;
        String prefix = rule.getCode() + "-R";
        for (FeeTransportRuleVersion v : versionMapper.selectList(
                new QueryWrapper<FeeTransportRuleVersion>().eq("rule_id", ruleId))) {
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

    private String normalizeMode(String mode) {
        if (mode == null || mode.trim().isEmpty()) {
            throw new BusinessException("FEE_MODE_REQUIRED", "交通方式不能为空");
        }
        String m = mode.trim().toUpperCase();
        if (!InvigilateFeeConstants.MODE_BUS.equals(m)
                && !InvigilateFeeConstants.MODE_TRAIN.equals(m)
                && !InvigilateFeeConstants.MODE_TAXI.equals(m)
                && !InvigilateFeeConstants.MODE_CAR.equals(m)
                && !InvigilateFeeConstants.MODE_OTHER.equals(m)) {
            throw new BusinessException("FEE_MODE_NOT_SUPPORTED",
                    "不支持的交通方式：" + m + "（仅 BUS / TRAIN / TAXI / CAR / OTHER）");
        }
        return m;
    }

    private String normalizeProject(String projectCode) {
        return projectCode == null || projectCode.trim().isEmpty()
                ? InvigilateFeeConstants.PROJECT_DEFAULT : projectCode.trim().toUpperCase();
    }
}
