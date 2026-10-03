package com.evops.papersel.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.common.BusinessException;
import com.evops.common.OperatorHolder;
import com.evops.papersel.common.PaperSelConstants;
import com.evops.papersel.dto.MaterialDisableRequest;
import com.evops.papersel.dto.MaterialRequest;
import com.evops.papersel.dto.MaterialSnapshot;
import com.evops.papersel.dto.MaterialUpdateRequest;
import com.evops.papersel.engine.Fingerprints;
import com.evops.papersel.entity.PaperMaterial;
import com.evops.papersel.entity.PaperMaterialChange;
import com.evops.papersel.mapper.PaperMaterialChangeMapper;
import com.evops.papersel.mapper.PaperMaterialMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 素材池模块：
 * - 素材建档 / 修改 / 下架 / 恢复，状态 ENABLED / DISABLED，绝不物理删除；
 * - 每次增删改只追加一条台账（前后快照 JSON + 原因 + 经办人）；
 * - 内容指纹按 知识点|难度档|题干|参考答案 计算，内容改动即重算；
 * - 组卷只从 ENABLED 抽；历史卷面持有组卷时点快照，池子变动不影响历史。
 */
@Service
public class MaterialPoolService {

    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyMMddHHmmss");

    private final PaperMaterialMapper materialMapper;
    private final PaperMaterialChangeMapper changeMapper;
    private final ObjectMapper objectMapper;

    public MaterialPoolService(PaperMaterialMapper materialMapper,
                               PaperMaterialChangeMapper changeMapper,
                               ObjectMapper objectMapper) {
        this.materialMapper = materialMapper;
        this.changeMapper = changeMapper;
        this.objectMapper = objectMapper;
    }

    // ---------- 建档 ----------

    @Transactional
    public PaperMaterial create(MaterialRequest request) {
        PaperMaterial material = new PaperMaterial();
        material.setMaterialNo(resolveNo(request.getMaterialNo()));
        material.setProjectCode(normalizeProject(request.getProjectCode()));
        applyContentFields(material, request.getKnowledgePoint(), request.getDifficulty(),
                request.getContent(), request.getReferenceAnswer());
        material.setRemark(request.getRemark());
        material.setStatus(PaperSelConstants.MATERIAL_ENABLED);
        try {
            materialMapper.insert(material);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("DUPLICATE_MATERIAL_NO", "素材编号已存在：" + material.getMaterialNo());
        }
        recordChange(material, PaperSelConstants.CHANGE_CREATE, null, snapshotOf(material), null);
        return material;
    }

    // ---------- 修改（内容或归类变更；编号/项目不可变） ----------

    @Transactional
    public PaperMaterial update(Long id, MaterialUpdateRequest request) {
        PaperMaterial material = requireMaterial(id);
        String before = snapshotOf(material);

        if (request.getKnowledgePoint() != null && !request.getKnowledgePoint().trim().isEmpty()) {
            material.setKnowledgePoint(request.getKnowledgePoint().trim());
        }
        if (request.getDifficulty() != null && !request.getDifficulty().trim().isEmpty()) {
            material.setDifficulty(request.getDifficulty().trim().toUpperCase());
        }
        if (request.getContent() != null && !request.getContent().trim().isEmpty()) {
            material.setContent(request.getContent());
        }
        // 参考答案允许传空串清空：显式 "" 视为清空，null 表示不改
        if (request.getReferenceAnswer() != null) {
            material.setReferenceAnswer(request.getReferenceAnswer().isEmpty() ? null
                    : request.getReferenceAnswer());
        }
        if (request.getRemark() != null) {
            material.setRemark(request.getRemark());
        }
        recomputeHash(material);
        materialMapper.updateById(material);
        recordChange(material, PaperSelConstants.CHANGE_UPDATE, before, snapshotOf(material),
                request.getReason());
        return material;
    }

    // ---------- 下架（不物理删除） ----------

    @Transactional
    public PaperMaterial disable(Long id, MaterialDisableRequest request) {
        PaperMaterial material = requireMaterial(id);
        if (PaperSelConstants.MATERIAL_DISABLED.equals(material.getStatus())) {
            throw new BusinessException("MATERIAL_ALREADY_DISABLED",
                    "素材已下架，无需重复下架：" + material.getMaterialNo());
        }
        String before = snapshotOf(material);
        material.setStatus(PaperSelConstants.MATERIAL_DISABLED);
        material.setDisabledReason(request.getReason().trim());
        material.setDisabledAt(LocalDateTime.now());
        materialMapper.updateById(material);
        recordChange(material, PaperSelConstants.CHANGE_DISABLE, before, snapshotOf(material),
                request.getReason());
        return material;
    }

    // ---------- 恢复（重新入池可抽） ----------

    @Transactional
    public PaperMaterial enable(Long id, String reason) {
        PaperMaterial material = requireMaterial(id);
        if (PaperSelConstants.MATERIAL_ENABLED.equals(material.getStatus())) {
            throw new BusinessException("MATERIAL_ALREADY_ENABLED",
                    "素材已在池，无需重复恢复：" + material.getMaterialNo());
        }
        String before = snapshotOf(material);
        material.setStatus(PaperSelConstants.MATERIAL_ENABLED);
        material.setDisabledReason(null);
        material.setDisabledAt(null);
        materialMapper.updateById(material);
        recordChange(material, PaperSelConstants.CHANGE_ENABLE, before, snapshotOf(material), reason);
        return material;
    }

    // ---------- 查询 ----------

    @Transactional(readOnly = true)
    public List<PaperMaterial> list(String projectCode, String knowledgePoint, String difficulty,
                                    String status) {
        QueryWrapper<PaperMaterial> wrapper = new QueryWrapper<PaperMaterial>()
                .orderByAsc("project_code", "knowledge_point", "difficulty", "id");
        if (projectCode != null && !projectCode.trim().isEmpty()) {
            wrapper.eq("project_code", projectCode.trim().toUpperCase());
        }
        if (knowledgePoint != null && !knowledgePoint.trim().isEmpty()) {
            wrapper.eq("knowledge_point", knowledgePoint.trim());
        }
        if (difficulty != null && !difficulty.trim().isEmpty()) {
            wrapper.eq("difficulty", difficulty.trim().toUpperCase());
        }
        if (status != null && !status.trim().isEmpty()) {
            wrapper.eq("status", status.trim().toUpperCase());
        }
        return materialMapper.selectList(wrapper);
    }

    public PaperMaterial getMaterial(Long id) {
        return requireMaterial(id);
    }

    @Transactional(readOnly = true)
    public List<PaperMaterialChange> listChanges(Long materialId) {
        requireMaterial(materialId);
        return changeMapper.selectList(new QueryWrapper<PaperMaterialChange>()
                .eq("material_id", materialId)
                .orderByAsc("id"));
    }

    /** 组卷/复算取在池素材（按 id 稳定排序，指纹不依赖查询顺序）。 */
    @Transactional(readOnly = true)
    public List<PaperMaterial> listEnabled(String projectCode) {
        return materialMapper.selectList(new QueryWrapper<PaperMaterial>()
                .eq("project_code", normalizeProject(projectCode))
                .eq("status", PaperSelConstants.MATERIAL_ENABLED)
                .orderByAsc("id"));
    }

    // ---------- 快照 / 指纹 ----------

    public MaterialSnapshot toSnapshot(PaperMaterial m) {
        MaterialSnapshot s = new MaterialSnapshot();
        s.setMaterialId(m.getId());
        s.setMaterialNo(m.getMaterialNo());
        s.setKnowledgePoint(m.getKnowledgePoint());
        s.setDifficulty(m.getDifficulty());
        s.setContentHash(m.getContentHash());
        return s;
    }

    public List<MaterialSnapshot> toSnapshots(List<PaperMaterial> materials) {
        List<MaterialSnapshot> snapshots = new ArrayList<>();
        for (PaperMaterial m : materials) {
            snapshots.add(toSnapshot(m));
        }
        return snapshots;
    }

    // ---------- 内部 ----------

    private PaperMaterial requireMaterial(Long id) {
        if (id == null) {
            throw new BusinessException("MATERIAL_ID_REQUIRED", "素材 id 不能为空");
        }
        PaperMaterial material = materialMapper.selectById(id);
        if (material == null) {
            throw new BusinessException("MATERIAL_NOT_FOUND", "素材不存在：id=" + id);
        }
        return material;
    }

    private void applyContentFields(PaperMaterial material, String kp, String difficulty,
                                    String content, String answer) {
        if (kp == null || kp.trim().isEmpty()) {
            throw new BusinessException("MATERIAL_KP_REQUIRED", "知识点不能为空");
        }
        if (difficulty == null || difficulty.trim().isEmpty()) {
            throw new BusinessException("MATERIAL_DIFFICULTY_REQUIRED", "难度档不能为空");
        }
        if (content == null || content.trim().isEmpty()) {
            throw new BusinessException("MATERIAL_CONTENT_REQUIRED", "题干内容不能为空");
        }
        material.setKnowledgePoint(kp.trim());
        material.setDifficulty(difficulty.trim().toUpperCase());
        material.setContent(content);
        material.setReferenceAnswer(answer);
        recomputeHash(material);
    }

    private void recomputeHash(PaperMaterial material) {
        material.setContentHash(Fingerprints.materialContentHash(
                material.getKnowledgePoint(), material.getDifficulty(),
                material.getContent(), material.getReferenceAnswer()));
    }

    private void recordChange(PaperMaterial material, String type, String before, String after,
                              String reason) {
        PaperMaterialChange change = new PaperMaterialChange();
        change.setMaterialId(material.getId());
        change.setMaterialNo(material.getMaterialNo());
        change.setChangeType(type);
        change.setBeforeSnapshot(before);
        change.setAfterSnapshot(after);
        change.setReason(reason);
        change.setOperator(OperatorHolder.current());
        change.setChangedAt(LocalDateTime.now());
        changeMapper.insert(change);
    }

    private String snapshotOf(PaperMaterial m) {
        try {
            return objectMapper.writeValueAsString(toSnapshot(m));
        } catch (Exception e) {
            throw new BusinessException("MATERIAL_SNAPSHOT_FAILED", "素材快照序列化失败：" + e.getMessage());
        }
    }

    private String resolveNo(String requested) {
        if (requested != null && !requested.trim().isEmpty()) {
            return requested.trim();
        }
        String prefix = "SC" + LocalDateTime.now().format(NO_FMT);
        // 秒级前缀 + 4 位序列：查库内同前缀最大序号，避免同秒建档撞号
        List<PaperMaterial> samePrefix = materialMapper.selectList(
                new QueryWrapper<PaperMaterial>().likeRight("material_no", prefix));
        int max = 0;
        for (PaperMaterial m : samePrefix) {
            String tail = m.getMaterialNo().substring(prefix.length());
            try {
                max = Math.max(max, Integer.parseInt(tail));
            } catch (NumberFormatException ignored) {
                // 非本规则编号忽略
            }
        }
        return prefix + String.format("%04d", max + 1);
    }

    private String normalizeProject(String projectCode) {
        if (projectCode == null || projectCode.trim().isEmpty()) {
            return PaperSelConstants.PROJECT_DEFAULT;
        }
        return projectCode.trim().toUpperCase();
    }
}
