package com.evops.invigilatefee.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.common.BusinessException;
import com.evops.common.OperatorHolder;
import com.evops.invigilatefee.common.InvigilateFeeConstants;
import com.evops.invigilatefee.dto.ModeTariff;
import com.evops.invigilatefee.dto.TravelRuleRequest;
import com.evops.invigilatefee.dto.TravelRuleVersionRequest;
import com.evops.invigilatefee.entity.TravelAllowanceRule;
import com.evops.invigilatefee.entity.TravelAllowanceRuleVersion;
import com.evops.invigilatefee.mapper.TravelAllowanceRuleMapper;
import com.evops.invigilatefee.mapper.TravelAllowanceRuleVersionMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 交通补贴规则（模块 + 版本 + 状态）：
 * - 规则按项目建档（缺省 DEFAULT 通用），规则下挂多个版本；
 * - 版本 DRAFT → ACTIVE，按生效日期闭区间选择；同一规则的生效区间不允许重叠，保证按出行日期唯一命中；
 * - 发布即冻结各交通方式计费口径（tariffs JSON）与票据偏离阈值，报销把版本 id/no 冻结进版本链；
 * - 历史报销保留版本号，规则升级/停用不影响历史口径。
 */
@Service
public class TravelRuleService {

    /** 默认票据偏离阈值 ±20%。 */
    public static final BigDecimal DEFAULT_DEVIATION = new BigDecimal("0.20");

    private final TravelAllowanceRuleMapper ruleMapper;
    private final TravelAllowanceRuleVersionMapper versionMapper;
    private final ObjectMapper objectMapper;

    public TravelRuleService(TravelAllowanceRuleMapper ruleMapper,
                             TravelAllowanceRuleVersionMapper versionMapper,
                             ObjectMapper objectMapper) {
        this.ruleMapper = ruleMapper;
        this.versionMapper = versionMapper;
        this.objectMapper = objectMapper;
    }

    // ---------- 规则模块 ----------

    @Transactional
    public TravelAllowanceRule createRule(TravelRuleRequest request) {
        TravelAllowanceRule rule = new TravelAllowanceRule();
        rule.setCode(request.getCode().trim());
        rule.setName(request.getName().trim());
        rule.setProjectCode(normalizeProject(request.getProjectCode()));
        rule.setRemark(request.getRemark());
        try {
            ruleMapper.insert(rule);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("DUPLICATE_TRAVEL_RULE_CODE", "交通补贴规则编码已存在：" + rule.getCode());
        }
        return rule;
    }

    @Transactional(readOnly = true)
    public List<TravelAllowanceRule> listRules(String projectCode) {
        QueryWrapper<TravelAllowanceRule> wrapper =
                new QueryWrapper<TravelAllowanceRule>().orderByAsc("project_code", "code");
        if (projectCode != null && !projectCode.trim().isEmpty()) {
            wrapper.eq("project_code", projectCode.trim());
        }
        return ruleMapper.selectList(wrapper);
    }

    public TravelAllowanceRule getRule(Long ruleId) {
        if (ruleId == null) {
            throw new BusinessException("TRAVEL_RULE_ID_REQUIRED", "规则 id 不能为空");
        }
        TravelAllowanceRule rule = ruleMapper.selectById(ruleId);
        if (rule == null) {
            throw new BusinessException("TRAVEL_RULE_NOT_FOUND", "交通补贴规则不存在：id=" + ruleId);
        }
        return rule;
    }

    // ---------- 规则版本 ----------

    @Transactional
    public TravelAllowanceRuleVersion createDraftVersion(Long ruleId, TravelRuleVersionRequest request) {
        TravelAllowanceRule rule = getRule(ruleId);
        LocalDate from = request.getEffectiveFrom();
        LocalDate to = request.getEffectiveTo();
        if (from != null && to != null && from.isAfter(to)) {
            throw new BusinessException("RULE_VERSION_RANGE_INVALID", "生效起必须不晚于生效止");
        }
        List<ModeTariff> tariffs = validateTariffs(request.getTariffs());
        BigDecimal threshold = normalizeThreshold(request.getDeviationThreshold());

        TravelAllowanceRuleVersion version = new TravelAllowanceRuleVersion();
        version.setRuleId(rule.getId());
        version.setVersionNo(nextVersionNo(rule.getId()));
        version.setStatus(InvigilateFeeConstants.VERSION_DRAFT);
        version.setEffectiveFrom(from);
        version.setEffectiveTo(to);
        version.setTariffs(writeTariffs(tariffs));
        version.setDeviationThreshold(threshold);
        version.setRemark(request.getRemark());
        version.setOperator(OperatorHolder.current());
        versionMapper.insert(version);
        return version;
    }

    /** 发布：DRAFT → ACTIVE。同规则生效区间与在版版本重叠即拒绝，保证按出行日期唯一命中。 */
    @Transactional
    public TravelAllowanceRuleVersion publishVersion(Long versionId) {
        TravelAllowanceRuleVersion version = requireVersion(versionId);
        if (InvigilateFeeConstants.VERSION_ACTIVE.equals(version.getStatus())) {
            throw new BusinessException("VERSION_ALREADY_ACTIVE",
                    "规则版本已是生效状态，无需重复发布：" + version.getVersionNo());
        }
        if (InvigilateFeeConstants.VERSION_DISABLED.equals(version.getStatus())) {
            throw new BusinessException("VERSION_DISABLED", "停用版本不可重新发布，请新建版本：" + version.getVersionNo());
        }

        List<TravelAllowanceRuleVersion> actives = versionMapper.selectList(new QueryWrapper<TravelAllowanceRuleVersion>()
                .eq("rule_id", version.getRuleId())
                .eq("status", InvigilateFeeConstants.VERSION_ACTIVE));
        for (TravelAllowanceRuleVersion other : actives) {
            if (rangesOverlap(version.getEffectiveFrom(), version.getEffectiveTo(),
                    other.getEffectiveFrom(), other.getEffectiveTo())) {
                throw new BusinessException("RULE_VERSION_RANGE_OVERLAP",
                        "版本 " + version.getVersionNo() + " 的生效区间与在版版本 " + other.getVersionNo()
                                + " 重叠，同一出行日期会命中多版，请核对生效起止");
            }
        }

        String operator = OperatorHolder.current();
        version.setStatus(InvigilateFeeConstants.VERSION_ACTIVE);
        version.setPublishedBy(operator);
        version.setPublishedAt(LocalDateTime.now());
        version.setOperator(operator);
        versionMapper.updateById(version);
        return version;
    }

    /** 停用：不删版本，历史报销仍保留其版本号。 */
    @Transactional
    public TravelAllowanceRuleVersion disableVersion(Long versionId) {
        TravelAllowanceRuleVersion version = requireVersion(versionId);
        if (InvigilateFeeConstants.VERSION_DISABLED.equals(version.getStatus())) {
            throw new BusinessException("VERSION_ALREADY_DISABLED", "规则版本已停用：" + version.getVersionNo());
        }
        String operator = OperatorHolder.current();
        version.setStatus(InvigilateFeeConstants.VERSION_DISABLED);
        version.setDisabledBy(operator);
        version.setDisabledAt(LocalDateTime.now());
        version.setOperator(operator);
        versionMapper.updateById(version);
        return version;
    }

    @Transactional(readOnly = true)
    public List<TravelAllowanceRuleVersion> listVersions(Long ruleId) {
        getRule(ruleId);
        return versionMapper.selectList(new QueryWrapper<TravelAllowanceRuleVersion>()
                .eq("rule_id", ruleId)
                .orderByAsc("id"));
    }

    public TravelAllowanceRuleVersion requireVersion(Long versionId) {
        if (versionId == null) {
            throw new BusinessException("RULE_VERSION_ID_REQUIRED", "规则版本 id 不能为空");
        }
        TravelAllowanceRuleVersion version = versionMapper.selectById(versionId);
        if (version == null) {
            throw new BusinessException("RULE_VERSION_NOT_FOUND", "规则版本不存在：id=" + versionId);
        }
        return version;
    }

    // ---------- 按出行日期选取版本 ----------

    /**
     * 解析某项目在指定出行日期适用的生效规则版本：先匹配项目专用规则，再回落 DEFAULT 通用；
     * 仅取 ACTIVE 且生效区间覆盖当日者；多条命中视为口径冲突直接报错；没有则抛 RULE_VERSION_NOT_ACTIVE。
     */
    @Transactional(readOnly = true)
    public TravelAllowanceRuleVersion resolveVersion(String projectCode, LocalDate travelDate) {
        String project = normalizeProject(projectCode);
        LocalDate day = travelDate == null ? LocalDate.now() : travelDate;

        TravelAllowanceRuleVersion version = pickVersion(project, day);
        if (version != null) {
            return version;
        }
        if (!InvigilateFeeConstants.PROJECT_DEFAULT.equals(project)) {
            version = pickVersion(InvigilateFeeConstants.PROJECT_DEFAULT, day);
            if (version != null) {
                return version;
            }
        }
        throw new BusinessException("RULE_VERSION_NOT_ACTIVE",
                "项目 " + project + " 在出行日期 " + day + " 没有适用的生效交通补贴规则版本，请先发布");
    }

    /** 取项目规则在当日唯一命中的生效版本；无命中 null；多条命中报歧义。 */
    private TravelAllowanceRuleVersion pickVersion(String project, LocalDate day) {
        List<TravelAllowanceRule> rules = ruleMapper.selectList(new QueryWrapper<TravelAllowanceRule>()
                .eq("project_code", project));
        if (rules.isEmpty()) {
            return null;
        }
        if (rules.size() > 1) {
            throw new BusinessException("TRAVEL_RULE_AMBIGUOUS",
                    "项目 " + project + " 配置了多条交通补贴规则，无法确定按哪条试算，请合并为一条");
        }
        List<TravelAllowanceRuleVersion> actives = versionMapper.selectList(
                new QueryWrapper<TravelAllowanceRuleVersion>()
                        .eq("rule_id", rules.get(0).getId())
                        .eq("status", InvigilateFeeConstants.VERSION_ACTIVE));
        List<TravelAllowanceRuleVersion> hit = new ArrayList<>();
        for (TravelAllowanceRuleVersion v : actives) {
            if (covers(v, day)) {
                hit.add(v);
            }
        }
        if (hit.isEmpty()) {
            return null;
        }
        if (hit.size() > 1) {
            throw new BusinessException("RULE_VERSION_AMBIGUOUS",
                    "项目 " + project + " 在 " + day + " 命中多条生效规则版本，口径冲突，请核对生效区间");
        }
        return hit.get(0);
    }

    private boolean covers(TravelAllowanceRuleVersion v, LocalDate day) {
        if (v.getEffectiveFrom() != null && day.isBefore(v.getEffectiveFrom())) {
            return false;
        }
        return v.getEffectiveTo() == null || !day.isAfter(v.getEffectiveTo());
    }

    /** 两闭区间是否重叠（null 端表示无限）。 */
    private boolean rangesOverlap(LocalDate from1, LocalDate to1, LocalDate from2, LocalDate to2) {
        LocalDate a = from1 == null ? LocalDate.MIN : from1;
        LocalDate b = to1 == null ? LocalDate.MAX : to1;
        LocalDate c = from2 == null ? LocalDate.MIN : from2;
        LocalDate d = to2 == null ? LocalDate.MAX : to2;
        return !a.isAfter(d) && !c.isAfter(b);
    }

    // ---------- 计费口径与试算 ----------

    /** 取规则版本内某交通方式的口径；未配置即报错。 */
    public ModeTariff requireTariff(TravelAllowanceRuleVersion version, String transportMode) {
        String mode = normalizeMode(transportMode);
        for (ModeTariff t : readTariffs(version.getTariffs())) {
            if (mode.equals(t.getTransportMode())) {
                return t;
            }
        }
        throw new BusinessException("TARIFF_MODE_NOT_CONFIGURED",
                "规则版本 " + version.getVersionNo() + " 未配置交通方式 " + mode + " 的计费口径");
    }

    /**
     * 按规则版本口径试算：DISTANCE 单价×里程（受最低里程门槛与封顶约束），FIXED 取定额。
     * 金额四舍五入保留两位。distanceKm 仅 DISTANCE 需要。
     */
    public BigDecimal calculate(TravelAllowanceRuleVersion version, String transportMode, BigDecimal distanceKm) {
        ModeTariff tariff = requireTariff(version, transportMode);
        return calculateWithTariff(tariff, distanceKm);
    }

    /** 试算并回连口径明细（供申请/改派预览展示金额来源）。 */
    public CalcResult calculateDetail(TravelAllowanceRuleVersion version, String transportMode,
                                      BigDecimal distanceKm) {
        ModeTariff tariff = requireTariff(version, transportMode);
        return new CalcResult(tariff, calculateWithTariff(tariff, distanceKm));
    }

    private BigDecimal calculateWithTariff(ModeTariff tariff, BigDecimal distanceKm) {
        if (InvigilateFeeConstants.CALC_FIXED.equals(tariff.getCalcMode())) {
            return money(tariff.getFixedAmount() == null ? BigDecimal.ZERO : tariff.getFixedAmount());
        }
        // DISTANCE
        if (distanceKm == null) {
            throw new BusinessException("DISTANCE_REQUIRED",
                    "交通方式 " + tariff.getTransportMode() + " 按里程计费，必须提供启用的考点里程");
        }
        BigDecimal min = tariff.getMinDistance() == null ? BigDecimal.ZERO : tariff.getMinDistance();
        if (distanceKm.compareTo(min) < 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal rate = tariff.getPerKmRate() == null ? BigDecimal.ZERO : tariff.getPerKmRate();
        BigDecimal amount = rate.multiply(distanceKm);
        if (tariff.getCapAmount() != null && amount.compareTo(tariff.getCapAmount()) > 0) {
            amount = tariff.getCapAmount();
        }
        return money(amount);
    }

    /** 票据金额是否异常：相对试算金额偏离超过版本阈值（试算为 0 时，票据非 0 即异常）。 */
    public boolean isAbnormal(TravelAllowanceRuleVersion version, BigDecimal estimated, BigDecimal receipt) {
        if (receipt == null) {
            return false;
        }
        BigDecimal threshold = version.getDeviationThreshold() == null
                ? DEFAULT_DEVIATION : version.getDeviationThreshold();
        if (estimated == null || estimated.signum() == 0) {
            return receipt.signum() != 0;
        }
        BigDecimal diff = receipt.subtract(estimated).abs();
        BigDecimal allowed = estimated.abs().multiply(threshold);
        return diff.compareTo(allowed) > 0;
    }

    private BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    // ---------- 口径校验 / 序列化 ----------

    public List<ModeTariff> validateTariffs(List<ModeTariff> raw) {
        if (raw == null || raw.isEmpty()) {
            throw new BusinessException("TARIFFS_REQUIRED", "计费口径不能为空");
        }
        Set<String> seen = new HashSet<>();
        List<ModeTariff> tariffs = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            ModeTariff t = raw.get(i);
            if (t == null) {
                throw new BusinessException("TARIFF_REQUIRED", "第 " + (i + 1) + " 条计费口径为空");
            }
            String mode = normalizeMode(t.getTransportMode());
            if (!seen.add(mode)) {
                throw new BusinessException("TARIFF_MODE_DUP", "交通方式计费口径重复：" + mode);
            }
            ModeTariff norm = new ModeTariff();
            norm.setTransportMode(mode);
            norm.setReceiptRequired(Boolean.TRUE.equals(t.getReceiptRequired()));

            String calcMode = t.getCalcMode() == null || t.getCalcMode().trim().isEmpty()
                    ? InvigilateFeeConstants.CALC_DISTANCE : t.getCalcMode().trim().toUpperCase();
            if (InvigilateFeeConstants.CALC_DISTANCE.equals(calcMode)) {
                norm.setCalcMode(InvigilateFeeConstants.CALC_DISTANCE);
                if (t.getPerKmRate() == null || t.getPerKmRate().signum() < 0) {
                    throw new BusinessException("TARIFF_RATE_INVALID",
                            "交通方式 " + mode + " 按里程计费必须提供非负每公里单价");
                }
                norm.setPerKmRate(t.getPerKmRate());
                if (t.getCapAmount() != null) {
                    if (t.getCapAmount().signum() < 0) {
                        throw new BusinessException("TARIFF_CAP_INVALID", "交通方式 " + mode + " 封顶额不能为负");
                    }
                    norm.setCapAmount(t.getCapAmount());
                }
                if (t.getMinDistance() != null) {
                    if (t.getMinDistance().signum() < 0) {
                        throw new BusinessException("TARIFF_MIN_DISTANCE_INVALID",
                                "交通方式 " + mode + " 最低里程门槛不能为负");
                    }
                    norm.setMinDistance(t.getMinDistance());
                } else {
                    norm.setMinDistance(BigDecimal.ZERO);
                }
            } else if (InvigilateFeeConstants.CALC_FIXED.equals(calcMode)) {
                norm.setCalcMode(InvigilateFeeConstants.CALC_FIXED);
                if (t.getFixedAmount() == null || t.getFixedAmount().signum() < 0) {
                    throw new BusinessException("TARIFF_FIXED_INVALID",
                            "交通方式 " + mode + " 定额计费必须提供非负定额金额");
                }
                norm.setFixedAmount(t.getFixedAmount());
            } else {
                throw new BusinessException("TARIFF_CALC_MODE_INVALID",
                        "交通方式 " + mode + " 的计费方式不支持：" + calcMode + "（仅 DISTANCE / FIXED）");
            }
            tariffs.add(norm);
        }
        return tariffs;
    }

    private BigDecimal normalizeThreshold(BigDecimal threshold) {
        if (threshold == null) {
            return DEFAULT_DEVIATION;
        }
        if (threshold.signum() < 0 || threshold.compareTo(BigDecimal.ONE) > 0) {
            throw new BusinessException("DEVIATION_THRESHOLD_INVALID", "票据偏离阈值必须在 0 ~ 1 之间（如 0.20 表示 ±20%）");
        }
        return threshold.setScale(4, RoundingMode.HALF_UP);
    }

    public String writeTariffs(List<ModeTariff> tariffs) {
        try {
            return objectMapper.writeValueAsString(tariffs);
        } catch (Exception e) {
            throw new BusinessException("TARIFFS_SERIALIZE_FAILED", "计费口径序列化失败：" + e.getMessage());
        }
    }

    public List<ModeTariff> readTariffs(String json) {
        try {
            List<ModeTariff> tariffs = objectMapper.readValue(json, new TypeReference<List<ModeTariff>>() {
            });
            if (tariffs == null || tariffs.isEmpty()) {
                throw new BusinessException("TARIFFS_BROKEN", "规则版本计费口径为空或已损坏");
            }
            return tariffs;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("TARIFFS_BROKEN", "规则版本计费口径无法解析：" + e.getMessage());
        }
    }

    private String normalizeMode(String mode) {
        if (mode == null || mode.trim().isEmpty()) {
            throw new BusinessException("TRANSPORT_MODE_REQUIRED", "交通方式不能为空");
        }
        String m = mode.trim().toUpperCase();
        if (!InvigilateFeeConstants.MODE_BUS.equals(m)
                && !InvigilateFeeConstants.MODE_RAIL.equals(m)
                && !InvigilateFeeConstants.MODE_TAXI.equals(m)
                && !InvigilateFeeConstants.MODE_SELF_DRIVE.equals(m)
                && !InvigilateFeeConstants.MODE_OTHER.equals(m)) {
            throw new BusinessException("TRANSPORT_MODE_NOT_SUPPORTED",
                    "不支持的交通方式：" + m + "（仅 BUS / RAIL / TAXI / SELF_DRIVE / OTHER）");
        }
        return m;
    }

    public String normalizeProject(String projectCode) {
        return projectCode == null || projectCode.trim().isEmpty()
                ? InvigilateFeeConstants.PROJECT_DEFAULT : projectCode.trim().toUpperCase();
    }

    private String nextVersionNo(Long ruleId) {
        TravelAllowanceRule rule = getRule(ruleId);
        int max = 0;
        String prefix = rule.getCode() + "-R";
        for (TravelAllowanceRuleVersion v : versionMapper.selectList(
                new QueryWrapper<TravelAllowanceRuleVersion>().eq("rule_id", ruleId))) {
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

    /** 试算明细中间结果：命中口径 + 金额。 */
    public static class CalcResult {
        private final ModeTariff tariff;
        private final BigDecimal amount;

        public CalcResult(ModeTariff tariff, BigDecimal amount) {
            this.tariff = tariff;
            this.amount = amount;
        }

        public ModeTariff getTariff() {
            return tariff;
        }

        public BigDecimal getAmount() {
            return amount;
        }
    }
}
