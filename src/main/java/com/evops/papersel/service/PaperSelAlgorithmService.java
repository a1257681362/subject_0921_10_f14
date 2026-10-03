package com.evops.papersel.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.common.BusinessException;
import com.evops.common.OperatorHolder;
import com.evops.papersel.common.PaperSelConstants;
import com.evops.papersel.dto.PaperSelAlgorithmRequest;
import com.evops.papersel.dto.PaperSelAlgorithmVersionRequest;
import com.evops.papersel.dto.PaperSelParams;
import com.evops.papersel.entity.PaperSelAlgorithm;
import com.evops.papersel.entity.PaperSelAlgorithmVersion;
import com.evops.papersel.mapper.PaperSelAlgorithmMapper;
import com.evops.papersel.mapper.PaperSelAlgorithmVersionMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 抽选算法模块（与混编/等级规则同一套版本模式）：
 * - 算法按项目建档，算法下挂多个版本；
 * - 版本状态 DRAFT → ACTIVE → DISABLED：发布即冻结参数 JSON，自动停用同算法旧生效版；
 * - 组卷只认项目当前 ACTIVE 版本；停用不删版本，历史卷面保留版本号与参数快照。
 */
@Service
public class PaperSelAlgorithmService {

    private final PaperSelAlgorithmMapper algorithmMapper;
    private final PaperSelAlgorithmVersionMapper versionMapper;
    private final ObjectMapper objectMapper;

    public PaperSelAlgorithmService(PaperSelAlgorithmMapper algorithmMapper,
                                    PaperSelAlgorithmVersionMapper versionMapper,
                                    ObjectMapper objectMapper) {
        this.algorithmMapper = algorithmMapper;
        this.versionMapper = versionMapper;
        this.objectMapper = objectMapper;
    }

    // ---------- 算法模块 ----------

    @Transactional
    public PaperSelAlgorithm createAlgorithm(PaperSelAlgorithmRequest request) {
        PaperSelAlgorithm algorithm = new PaperSelAlgorithm();
        algorithm.setCode(request.getCode().trim());
        algorithm.setName(request.getName().trim());
        algorithm.setProjectCode(normalizeProject(request.getProjectCode()));
        algorithm.setRemark(request.getRemark());
        try {
            algorithmMapper.insert(algorithm);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("DUPLICATE_SEL_ALGORITHM_CODE", "抽选算法编码已存在：" + algorithm.getCode());
        }
        return algorithm;
    }

    @Transactional(readOnly = true)
    public List<PaperSelAlgorithm> listAlgorithms(String projectCode) {
        QueryWrapper<PaperSelAlgorithm> wrapper = new QueryWrapper<PaperSelAlgorithm>()
                .orderByAsc("project_code", "code");
        if (projectCode != null && !projectCode.trim().isEmpty()) {
            wrapper.eq("project_code", projectCode.trim().toUpperCase());
        }
        return algorithmMapper.selectList(wrapper);
    }

    public PaperSelAlgorithm getAlgorithm(Long algorithmId) {
        PaperSelAlgorithm algorithm = algorithmMapper.selectById(algorithmId);
        if (algorithm == null) {
            throw new BusinessException("SEL_ALGORITHM_NOT_FOUND", "抽选算法不存在：id=" + algorithmId);
        }
        return algorithm;
    }

    // ---------- 算法版本 ----------

    /** 新建草稿版本：校验并补全参数后以 JSON 冻结落库。 */
    @Transactional
    public PaperSelAlgorithmVersion createDraftVersion(Long algorithmId,
                                                       PaperSelAlgorithmVersionRequest request) {
        PaperSelAlgorithm algorithm = getAlgorithm(algorithmId);
        PaperSelParams normalized = validateParams(request.getParams());

        PaperSelAlgorithmVersion version = new PaperSelAlgorithmVersion();
        version.setAlgorithmId(algorithm.getId());
        version.setVersionNo(nextVersionNo(algorithm.getId()));
        version.setStatus(PaperSelConstants.VERSION_DRAFT);
        version.setParams(writeParams(normalized));
        version.setRemark(request.getRemark());
        version.setOperator(OperatorHolder.current());
        versionMapper.insert(version);
        return version;
    }

    /** 发布：DRAFT → ACTIVE；同一算法的旧 ACTIVE 自动置 DISABLED。 */
    @Transactional
    public PaperSelAlgorithmVersion publishVersion(Long versionId) {
        PaperSelAlgorithmVersion version = requireVersion(versionId);
        String operator = OperatorHolder.current();
        LocalDateTime now = LocalDateTime.now();

        if (PaperSelConstants.VERSION_ACTIVE.equals(version.getStatus())) {
            throw new BusinessException("SEL_VERSION_ALREADY_ACTIVE",
                    "版本已是生效状态，无需重复发布：" + version.getVersionNo());
        }
        if (PaperSelConstants.VERSION_DISABLED.equals(version.getStatus())) {
            throw new BusinessException("SEL_VERSION_DISABLED",
                    "停用版本不可重新发布，请新建版本：" + version.getVersionNo());
        }

        List<PaperSelAlgorithmVersion> actives = versionMapper.selectList(
                new QueryWrapper<PaperSelAlgorithmVersion>()
                        .eq("algorithm_id", version.getAlgorithmId())
                        .eq("status", PaperSelConstants.VERSION_ACTIVE));
        for (PaperSelAlgorithmVersion active : actives) {
            active.setStatus(PaperSelConstants.VERSION_DISABLED);
            active.setDisabledBy(operator);
            active.setDisabledAt(now);
            versionMapper.updateById(active);
        }

        version.setStatus(PaperSelConstants.VERSION_ACTIVE);
        version.setPublishedBy(operator);
        version.setPublishedAt(now);
        versionMapper.updateById(version);
        return version;
    }

    /** 人工停用生效版。 */
    @Transactional
    public PaperSelAlgorithmVersion disableVersion(Long versionId) {
        PaperSelAlgorithmVersion version = requireVersion(versionId);
        if (!PaperSelConstants.VERSION_ACTIVE.equals(version.getStatus())) {
            throw new BusinessException("SEL_VERSION_NOT_ACTIVE",
                    "仅生效版本可停用：" + version.getVersionNo());
        }
        version.setStatus(PaperSelConstants.VERSION_DISABLED);
        version.setDisabledBy(OperatorHolder.current());
        version.setDisabledAt(LocalDateTime.now());
        versionMapper.updateById(version);
        return version;
    }

    @Transactional(readOnly = true)
    public List<PaperSelAlgorithmVersion> listVersions(Long algorithmId) {
        getAlgorithm(algorithmId);
        return versionMapper.selectList(new QueryWrapper<PaperSelAlgorithmVersion>()
                .eq("algorithm_id", algorithmId)
                .orderByDesc("id"));
    }

    public PaperSelAlgorithmVersion getVersion(Long versionId) {
        return requireVersion(versionId);
    }

    /**
     * 取项目当前生效算法版本：项目下所有算法中仅允许一条 ACTIVE；
     * 多条 ACTIVE 无法唯一确定时抛错；没有生效版返回 null（组卷按内置默认口径兜底）。
     */
    @Transactional(readOnly = true)
    public PaperSelAlgorithmVersion findActiveVersion(String projectCode) {
        String project = normalizeProject(projectCode);
        List<PaperSelAlgorithm> algorithms = algorithmMapper.selectList(
                new QueryWrapper<PaperSelAlgorithm>().eq("project_code", project));
        if (algorithms.isEmpty()) {
            return null;
        }
        List<Long> ids = new ArrayList<>();
        for (PaperSelAlgorithm a : algorithms) {
            ids.add(a.getId());
        }
        List<PaperSelAlgorithmVersion> actives = versionMapper.selectList(
                new QueryWrapper<PaperSelAlgorithmVersion>()
                        .in("algorithm_id", ids)
                        .eq("status", PaperSelConstants.VERSION_ACTIVE)
                        .orderByDesc("id"));
        if (actives.isEmpty()) {
            return null;
        }
        if (actives.size() > 1) {
            throw new BusinessException("MULTIPLE_ACTIVE_SEL_VERSION",
                    "项目 " + project + " 存在多个生效抽选算法版本，请先停用多余版本");
        }
        return actives.get(0);
    }

    // ---------- 参数校验 / 序列化 ----------

    /** 校验并补全默认值，返回用于冻结的规范化参数。 */
    public PaperSelParams validateParams(PaperSelParams raw) {
        PaperSelParams p = raw == null ? new PaperSelParams() : raw;
        PaperSelParams norm = new PaperSelParams();

        String strategy = p.getStrategy() == null || p.getStrategy().trim().isEmpty()
                ? PaperSelConstants.STRATEGY_KP_DIFFICULTY_QUOTA : p.getStrategy().trim().toUpperCase();
        if (!PaperSelConstants.STRATEGY_KP_DIFFICULTY_QUOTA.equals(strategy)) {
            throw new BusinessException("SEL_PARAM_STRATEGY_INVALID",
                    "不支持的抽选策略：" + strategy + "，当前仅支持 KP_DIFFICULTY_QUOTA");
        }
        norm.setStrategy(strategy);

        double overlap = p.getMaxOverlapRate() == null
                ? PaperSelConstants.DEFAULT_MAX_OVERLAP_RATE : p.getMaxOverlapRate();
        if (overlap < PaperSelConstants.MIN_OVERLAP_RATE
                || overlap > PaperSelConstants.MAX_OVERLAP_RATE_LIMIT) {
            throw new BusinessException("SEL_PARAM_OVERLAP_INVALID",
                    "套间最大重合率须在 [0, 1] 之间");
        }
        norm.setMaxOverlapRate(overlap);

        int retries = p.getMaxRetries() == null
                ? PaperSelConstants.DEFAULT_MAX_RETRIES : p.getMaxRetries();
        if (retries < PaperSelConstants.MIN_RETRIES || retries > PaperSelConstants.MAX_RETRIES_LIMIT) {
            throw new BusinessException("SEL_PARAM_RETRIES_INVALID",
                    "单套重抽上限须在 [" + PaperSelConstants.MIN_RETRIES + ", "
                            + PaperSelConstants.MAX_RETRIES_LIMIT + "] 之间");
        }
        norm.setMaxRetries(retries);

        String scope = p.getCompareScope() == null || p.getCompareScope().trim().isEmpty()
                ? PaperSelConstants.COMPARE_SCOPE_GROUP : p.getCompareScope().trim().toUpperCase();
        if (!PaperSelConstants.COMPARE_SCOPE_GROUP.equals(scope)) {
            throw new BusinessException("SEL_PARAM_SCOPE_INVALID",
                    "不支持的重合率比对范围：" + scope + "，当前仅支持 GROUP（本次并行各套）");
        }
        norm.setCompareScope(scope);
        return norm;
    }

    /** 内置默认参数（项目无生效算法版本时的兜底口径）。 */
    public PaperSelParams defaultParams() {
        PaperSelParams p = new PaperSelParams();
        p.setStrategy(PaperSelConstants.STRATEGY_KP_DIFFICULTY_QUOTA);
        p.setMaxOverlapRate(PaperSelConstants.DEFAULT_MAX_OVERLAP_RATE);
        p.setMaxRetries(PaperSelConstants.DEFAULT_MAX_RETRIES);
        p.setCompareScope(PaperSelConstants.COMPARE_SCOPE_GROUP);
        return p;
    }

    public String writeParams(PaperSelParams params) {
        try {
            return objectMapper.writeValueAsString(params);
        } catch (Exception e) {
            throw new BusinessException("SEL_PARAMS_SERIALIZE_FAILED", "算法参数序列化失败：" + e.getMessage());
        }
    }

    public PaperSelParams readParams(String json) {
        try {
            PaperSelParams params = objectMapper.readValue(json, new TypeReference<PaperSelParams>() {
            });
            if (params == null) {
                throw new BusinessException("SEL_PARAMS_BROKEN", "算法版本参数为空或已损坏");
            }
            return params;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("SEL_PARAMS_BROKEN", "算法版本参数无法解析：" + e.getMessage());
        }
    }

    // ---------- 内部 ----------

    private PaperSelAlgorithmVersion requireVersion(Long versionId) {
        if (versionId == null) {
            throw new BusinessException("SEL_VERSION_ID_REQUIRED", "算法版本 id 不能为空");
        }
        PaperSelAlgorithmVersion version = versionMapper.selectById(versionId);
        if (version == null) {
            throw new BusinessException("SEL_VERSION_NOT_FOUND", "抽选算法版本不存在：id=" + versionId);
        }
        return version;
    }

    /** 版本号：算法 code + A + 递增序号（如 ZJ-DEFAULT-A1）。 */
    private String nextVersionNo(Long algorithmId) {
        PaperSelAlgorithm algorithm = getAlgorithm(algorithmId);
        int max = 0;
        String prefix = algorithm.getCode() + "-A";
        for (PaperSelAlgorithmVersion v : versionMapper.selectList(
                new QueryWrapper<PaperSelAlgorithmVersion>().eq("algorithm_id", algorithmId))) {
            String no = v.getVersionNo();
            if (no != null && no.startsWith(prefix)) {
                try {
                    max = Math.max(max, Integer.parseInt(no.substring(prefix.length())));
                } catch (NumberFormatException ignored) {
                    // 非本算法序号忽略
                }
            }
        }
        return prefix + (max + 1);
    }

    private String normalizeProject(String projectCode) {
        if (projectCode == null || projectCode.trim().isEmpty()) {
            return PaperSelConstants.PROJECT_DEFAULT;
        }
        return projectCode.trim().toUpperCase();
    }
}
