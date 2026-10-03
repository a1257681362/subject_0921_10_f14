package com.evops.grade.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.common.BusinessException;
import com.evops.grade.common.GradeConstants;
import com.evops.grade.dto.MaterialRegisterRequest;
import com.evops.grade.dto.MaterialReplaceRequest;
import com.evops.grade.entity.ReviewMaterial;
import com.evops.grade.entity.ReviewMaterialRead;
import com.evops.grade.entity.ReviewMaterialVersion;
import com.evops.grade.entity.ScoreReview;
import com.evops.grade.mapper.ReviewMaterialMapper;
import com.evops.grade.mapper.ReviewMaterialReadMapper;
import com.evops.grade.mapper.ReviewMaterialVersionMapper;
import com.evops.grade.mapper.ScoreReviewMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 复核材料：与申请流转分开处理。
 * - 文件进入 evops-base 即留档（REGISTER 一版），材料列表按进入时间排列；
 * - 附件版本替换只追加 REPLACE 新版，旧版不删，保留摘要（文件名/大小/哈希/原因/钟点）置 SUPERSEDED；
 * - 阅读单独记 t_review_material_read，不影响复核单状态，不受复核窗口影响（窗口关闭后历史仍可阅读）；
 * - 内部批注只挂在材料主表，仅内部管理视图可见，学员视图/导出都不带出；
 * - 学员（readerKind=SELF）只能读本人材料，非本人统一按不存在处理，不暴露存在性。
 */
@Service
public class ReviewMaterialService {

    private final ReviewMaterialMapper materialMapper;
    private final ReviewMaterialVersionMapper versionMapper;
    private final ReviewMaterialReadMapper readMapper;
    private final ScoreReviewMapper reviewMapper;

    public ReviewMaterialService(ReviewMaterialMapper materialMapper,
                                 ReviewMaterialVersionMapper versionMapper,
                                 ReviewMaterialReadMapper readMapper,
                                 ScoreReviewMapper reviewMapper) {
        this.materialMapper = materialMapper;
        this.versionMapper = versionMapper;
        this.readMapper = readMapper;
        this.reviewMapper = reviewMapper;
    }

    // ---------- 材料进入留档（与窗口/流转状态无关，进入 evops-base 即按时间留档） ----------

    @Transactional
    public ReviewMaterial register(MaterialRegisterRequest request, String operator) {
        ScoreReview review = requireReview(request.getReviewId());
        String title = request.getTitle().trim();
        ReviewMaterial dup = materialMapper.selectOne(new QueryWrapper<ReviewMaterial>()
                .eq("review_id", review.getId())
                .eq("title", title));
        if (dup != null) {
            throw new BusinessException("REVIEW_MATERIAL_DUP",
                    "该复核申请下已存在同名材料（id=" + dup.getId() + "），换版请走附件版本替换");
        }
        boolean hasFile = request.getFileRef() != null && !request.getFileRef().trim().isEmpty();
        boolean hasContent = request.getContent() != null && !request.getContent().trim().isEmpty();
        if (!hasFile && !hasContent) {
            throw new BusinessException("REVIEW_MATERIAL_BODY_REQUIRED",
                    "材料需提供附件引用 fileRef 或文本内容 content");
        }
        LocalDateTime now = LocalDateTime.now();

        ReviewMaterial material = new ReviewMaterial();
        material.setReviewId(review.getId());
        material.setBatchId(review.getBatchId());
        material.setRegistrationId(review.getRegistrationId());
        material.setRegNo(review.getRegNo());
        material.setName(review.getName());
        material.setTitle(title);
        material.setMaterialType(normalizeType(request.getMaterialType()));
        material.setStatus(GradeConstants.MATERIAL_ACTIVE);
        material.setCurrentVersion(1);
        material.setInternalAnnotation(trimToNull(request.getInternalAnnotation()));
        materialMapper.insert(material);

        ReviewMaterialVersion v1 = new ReviewMaterialVersion();
        v1.setMaterialId(material.getId());
        v1.setReviewId(review.getId());
        v1.setVersionNo(1);
        v1.setStatus(GradeConstants.MATERIAL_ACTIVE);
        v1.setFileRef(trimToNull(request.getFileRef()));
        v1.setContentHash(trimToNull(request.getContentHash()));
        v1.setContent(trimToNull(request.getContent()));
        v1.setFileSize(request.getFileSize());
        v1.setOriginalFilename(trimToNull(request.getOriginalFilename()));
        v1.setDigestSummary(buildDigest(trimToNull(request.getDigestSummary()),
                request.getOriginalFilename(), request.getFileSize(), request.getContentHash()));
        v1.setChangeType(GradeConstants.MATERIAL_REGISTER);
        v1.setOperator(operator);
        v1.setEnteredAt(now);
        versionMapper.insert(v1);
        return material;
    }

    // ---------- 附件版本替换：旧版留摘要，新版为当前版 ----------

    /**
     * 用新附件替换当前版本：旧版（含其文件引用/正文）不删除，状态置 SUPERSEDED 并记下替换原因与摘要；
     * 追加一条 ACTIVE 新版本。材料主表 currentVersion+1，整体仍 ACTIVE。
     */
    @Transactional
    public ReviewMaterialVersion replace(Long materialId, MaterialReplaceRequest request, String operator) {
        ReviewMaterial material = requireMaterial(materialId);
        ReviewMaterialVersion current = versionMapper.selectOne(new QueryWrapper<ReviewMaterialVersion>()
                .eq("material_id", materialId)
                .eq("version_no", material.getCurrentVersion()));
        if (current == null) {
            throw new BusinessException("REVIEW_MATERIAL_VERSION_MISSING",
                    "材料当前版本缺失：materialId=" + materialId);
        }
        boolean hasFile = request.getFileRef() != null && !request.getFileRef().trim().isEmpty();
        boolean hasContent = request.getContent() != null && !request.getContent().trim().isEmpty();
        if (!hasFile && !hasContent) {
            throw new BusinessException("REVIEW_MATERIAL_BODY_REQUIRED",
                    "替换版本需提供附件引用 fileRef 或文本内容 content");
        }
        LocalDateTime now = LocalDateTime.now();

        // 旧版留摘要：摘要字段原本就在，补记替换原因后置 SUPERSEDED，不删文件引用/哈希等留档信息
        current.setStatus(GradeConstants.MATERIAL_SUPERSEDED);
        current.setChangeReason(request.getReason().trim());
        versionMapper.updateById(current);

        int nextVersion = material.getCurrentVersion() + 1;
        ReviewMaterialVersion next = new ReviewMaterialVersion();
        next.setMaterialId(materialId);
        next.setReviewId(material.getReviewId());
        next.setVersionNo(nextVersion);
        next.setStatus(GradeConstants.MATERIAL_ACTIVE);
        next.setFileRef(trimToNull(request.getFileRef()));
        next.setContentHash(trimToNull(request.getContentHash()));
        next.setContent(trimToNull(request.getContent()));
        next.setFileSize(request.getFileSize());
        next.setOriginalFilename(trimToNull(request.getOriginalFilename()));
        next.setDigestSummary(buildDigest(trimToNull(request.getDigestSummary()),
                request.getOriginalFilename(), request.getFileSize(), request.getContentHash()));
        next.setChangeType(GradeConstants.MATERIAL_REPLACE);
        next.setChangeReason(request.getReason().trim());
        next.setOperator(operator);
        next.setEnteredAt(now);
        versionMapper.insert(next);

        material.setCurrentVersion(nextVersion);
        material.setStatus(GradeConstants.MATERIAL_ACTIVE);
        materialMapper.updateById(material);
        return next;
    }

    // ---------- 内部批注（只内部可见） ----------

    @Transactional
    public ReviewMaterial annotate(Long materialId, String annotation) {
        ReviewMaterial material = requireMaterial(materialId);
        material.setInternalAnnotation(trimToNull(annotation));
        materialMapper.updateById(material);
        return material;
    }

    // ---------- 阅读留痕（独立于流转；窗口关闭后仍可阅读） ----------

    /**
     * 阅读材料当前版并留痕。学员（SELF）必须是材料本人，否则按不存在处理；
     * 审核岗（STAFF）可阅读任意材料。无论复核窗口是否关闭、复核单处于何种状态都可阅读。
     */
    @Transactional
    public ReviewMaterialVersion read(Long materialId, String reader, String readerKind, String regNo) {
        ReviewMaterial material = requireMaterial(materialId);
        if (GradeConstants.READER_SELF.equals(readerKind)) {
            if (regNo == null || !material.getRegNo().equals(regNo.trim())) {
                // 学员只能读本人资料：不暴露材料是否存在
                throw new BusinessException("REVIEW_MATERIAL_NOT_FOUND", "复核材料不存在：id=" + materialId);
            }
        }
        ReviewMaterialVersion current = versionMapper.selectOne(new QueryWrapper<ReviewMaterialVersion>()
                .eq("material_id", materialId)
                .eq("version_no", material.getCurrentVersion()));
        LocalDateTime now = LocalDateTime.now();
        ReviewMaterialRead read = new ReviewMaterialRead();
        read.setMaterialId(materialId);
        read.setVersionNo(material.getCurrentVersion());
        read.setReviewId(material.getReviewId());
        read.setRegNo(material.getRegNo());
        read.setReader(reader);
        read.setReaderKind(readerKind);
        read.setReadAt(now);
        readMapper.insert(read);
        return current;
    }

    // ---------- 查询（内部视图，含内部批注；学员视图走 Self 视图装配） ----------

    /** 材料按进入 evops-base 的时间排列（首版 entered_at，同刻按 id）。 */
    public List<ReviewMaterial> listByReview(Long reviewId) {
        requireReview(reviewId);
        List<ReviewMaterial> materials = materialMapper.selectList(new QueryWrapper<ReviewMaterial>()
                .eq("review_id", reviewId)
                .orderByAsc("create_time", "id"));
        return materials;
    }

    public List<ReviewMaterialVersion> listVersions(Long materialId) {
        requireMaterial(materialId);
        return versionMapper.selectList(new QueryWrapper<ReviewMaterialVersion>()
                .eq("material_id", materialId)
                .orderByAsc("version_no"));
    }

    public List<ReviewMaterialRead> listReads(Long materialId) {
        requireMaterial(materialId);
        return readMapper.selectList(new QueryWrapper<ReviewMaterialRead>()
                .eq("material_id", materialId)
                .orderByAsc("read_at", "id"));
    }

    public ReviewMaterial getMaterial(Long materialId) {
        return requireMaterial(materialId);
    }

    // ---------- 学员脱敏视图装配（不含内部批注；旧版只给摘要） ----------

    /**
     * 组装学员本人可见的材料清单：按进入时间排列；不下发 fileRef/content/contentHash 正文，
     * 只给当前版摘要与历史版本摘要；内部批注绝不下发。
     */
    public List<com.evops.grade.vo.ReviewSelfVO.MaterialSelfVO> selfMaterials(Long reviewId) {
        List<com.evops.grade.vo.ReviewSelfVO.MaterialSelfVO> result = new ArrayList<>();
        for (ReviewMaterial m : listByReview(reviewId)) {
            com.evops.grade.vo.ReviewSelfVO.MaterialSelfVO vo =
                    new com.evops.grade.vo.ReviewSelfVO.MaterialSelfVO();
            vo.setMaterialId(m.getId());
            vo.setTitle(m.getTitle());
            vo.setMaterialType(m.getMaterialType());
            vo.setStatus(m.getStatus());
            vo.setCurrentVersion(m.getCurrentVersion());
            List<ReviewMaterialVersion> versions = listVersions(m.getId());
            for (ReviewMaterialVersion v : versions) {
                if (v.getVersionNo().equals(m.getCurrentVersion())) {
                    vo.setEnteredAt(v.getEnteredAt());
                    vo.setCurrentDigest(v.getDigestSummary());
                }
                com.evops.grade.vo.ReviewSelfVO.VersionDigest d =
                        new com.evops.grade.vo.ReviewSelfVO.VersionDigest();
                d.setVersionNo(v.getVersionNo());
                d.setStatus(v.getStatus());
                d.setChangeType(v.getChangeType());
                d.setDigestSummary(v.getDigestSummary());
                d.setChangeReason(GradeConstants.MATERIAL_SUPERSEDED.equals(v.getStatus())
                        ? v.getChangeReason() : null);
                d.setEnteredAt(v.getEnteredAt());
                vo.getVersions().add(d);
            }
            result.add(vo);
        }
        return result;
    }

    // ---------- 内部 ----------

    private ScoreReview requireReview(Long reviewId) {
        if (reviewId == null) {
            throw new BusinessException("REVIEW_ID_REQUIRED", "复核申请 id 不能为空");
        }
        ScoreReview review = reviewMapper.selectById(reviewId);
        if (review == null) {
            throw new BusinessException("REVIEW_NOT_FOUND", "复核申请不存在：id=" + reviewId);
        }
        return review;
    }

    private ReviewMaterial requireMaterial(Long materialId) {
        if (materialId == null) {
            throw new BusinessException("REVIEW_MATERIAL_ID_REQUIRED", "复核材料 id 不能为空");
        }
        ReviewMaterial material = materialMapper.selectById(materialId);
        if (material == null) {
            throw new BusinessException("REVIEW_MATERIAL_NOT_FOUND", "复核材料不存在：id=" + materialId);
        }
        return material;
    }

    private String normalizeType(String type) {
        if (type == null || type.trim().isEmpty()) {
            return "DOC";
        }
        String t = type.trim().toUpperCase();
        if ("DOC".equals(t) || "PHOTO".equals(t) || "FORM".equals(t) || "OTHER".equals(t)) {
            return t;
        }
        throw new BusinessException("REVIEW_MATERIAL_TYPE_INVALID",
                "材料类型非法（DOC/PHOTO/FORM/OTHER）：" + type);
    }

    private String buildDigest(String explicit, String filename, Long fileSize, String hash) {
        if (explicit != null) {
            return explicit;
        }
        StringBuilder sb = new StringBuilder();
        if (filename != null && !filename.trim().isEmpty()) {
            sb.append("文件：").append(filename.trim());
        }
        if (fileSize != null) {
            sb.append(sb.length() == 0 ? "大小：" : "；大小：").append(fileSize).append(" 字节");
        }
        if (hash != null && !hash.trim().isEmpty()) {
            sb.append(sb.length() == 0 ? "摘要：" : "；摘要：").append(hash.trim());
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
