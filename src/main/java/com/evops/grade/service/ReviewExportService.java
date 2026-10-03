package com.evops.grade.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.common.BusinessException;
import com.evops.grade.common.GradeConstants;
import com.evops.grade.entity.ReviewMaterial;
import com.evops.grade.entity.ReviewMaterialVersion;
import com.evops.grade.entity.ScoreReview;
import com.evops.grade.mapper.ReviewMaterialMapper;
import com.evops.grade.mapper.ReviewMaterialVersionMapper;
import com.evops.grade.mapper.ScoreReviewMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 复核导出（CSV，UTF-8 BOM，Excel 直接打开不乱码）：
 * - 管理端按批次导出；学员端只导出本人（姓名掩码）；
 * - 列含申请流转结论与结论依据、材料版本摘要（附件替换后的旧版摘要也在）；
 * - 严格不带出内部批注（t_review_material.internal_annotation 在装配时根本不取）。
 */
@Service
public class ReviewExportService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ScoreReviewMapper reviewMapper;
    private final ReviewMaterialMapper materialMapper;
    private final ReviewMaterialVersionMapper versionMapper;

    public ReviewExportService(ScoreReviewMapper reviewMapper,
                               ReviewMaterialMapper materialMapper,
                               ReviewMaterialVersionMapper versionMapper) {
        this.reviewMapper = reviewMapper;
        this.materialMapper = materialMapper;
        this.versionMapper = versionMapper;
    }

    /** 管理端：导出一个批次的全部复核申请（含历史，窗口关闭后仍可导出）。 */
    @Transactional(readOnly = true)
    public byte[] exportBatch(Long batchId) {
        if (batchId == null) {
            throw new BusinessException("BATCH_ID_REQUIRED", "批次 id 不能为空");
        }
        List<ScoreReview> reviews = reviewMapper.selectList(new QueryWrapper<ScoreReview>()
                .eq("batch_id", batchId)
                .orderByAsc("applied_at", "id"));
        return toCsv(reviews, false);
    }

    /** 学员本人：只导出本人的复核申请，姓名掩码，同样不含内部批注。 */
    @Transactional(readOnly = true)
    public byte[] exportSelf(String regNo) {
        if (regNo == null || regNo.trim().isEmpty()) {
            throw new BusinessException("REG_NO_REQUIRED", "报名号不能为空");
        }
        List<ScoreReview> reviews = reviewMapper.selectList(new QueryWrapper<ScoreReview>()
                .eq("reg_no", regNo.trim())
                .orderByAsc("applied_at", "id"));
        return toCsv(reviews, true);
    }

    private byte[] toCsv(List<ScoreReview> reviews, boolean selfView) {
        StringBuilder sb = new StringBuilder('﻿');
        sb.append("复核申请id,批次id,报名号,姓名,状态,状态说明,申请时间,结论时间,原分,原等级,复核后分,复核后等级,结论依据,材料版本摘要\r\n");
        for (ScoreReview r : reviews) {
            sb.append(r.getId()).append(',')
                    .append(r.getBatchId()).append(',')
                    .append(csv(r.getRegNo())).append(',')
                    .append(csv(selfView ? maskName(r.getName()) : r.getName())).append(',')
                    .append(csv(r.getStatus())).append(',')
                    .append(csv(statusLabel(r.getStatus()))).append(',')
                    .append(csv(fmt(r.getAppliedAt()))).append(',')
                    .append(csv(fmt(r.getConcludedAt()))).append(',')
                    .append(num(r.getScoreBefore())).append(',')
                    .append(csv(r.getGradeBefore())).append(',')
                    .append(num(r.getScoreAfter())).append(',')
                    .append(csv(r.getGradeAfter())).append(',')
                    .append(csv(r.getConclusion())).append(',')
                    // 材料摘要只取版本 digestSummary/changeReason，绝不取 internal_annotation
                    .append(csv(materialDigest(r.getId())))
                    .append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** 材料按进入时间排列；每份材料给标题、当前版摘要、替换后旧版摘要。 */
    private String materialDigest(Long reviewId) {
        List<ReviewMaterial> materials = materialMapper.selectList(new QueryWrapper<ReviewMaterial>()
                .eq("review_id", reviewId)
                .orderByAsc("create_time", "id"));
        if (materials.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ReviewMaterial m : materials) {
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append(m.getTitle()).append('[').append(m.getCurrentVersion()).append("版]");
            List<ReviewMaterialVersion> versions = versionMapper.selectList(
                    new QueryWrapper<ReviewMaterialVersion>()
                            .eq("material_id", m.getId())
                            .orderByAsc("version_no"));
            for (ReviewMaterialVersion v : versions) {
                sb.append("；v").append(v.getVersionNo());
                if (GradeConstants.MATERIAL_SUPERSEDED.equals(v.getStatus())) {
                    sb.append("(已替换)");
                }
                if (v.getDigestSummary() != null) {
                    sb.append(':').append(v.getDigestSummary());
                }
                if (GradeConstants.MATERIAL_SUPERSEDED.equals(v.getStatus()) && v.getChangeReason() != null) {
                    sb.append("（替换原因：").append(v.getChangeReason()).append('）');
                }
            }
        }
        return sb.toString();
    }

    private String statusLabel(String status) {
        if (GradeConstants.REVIEW_SUBMITTED.equals(status)) {
            return "待处理";
        }
        if (GradeConstants.REVIEW_APPROVED.equals(status)) {
            return "复核通过";
        }
        if (GradeConstants.REVIEW_REJECTED.equals(status)) {
            return "复核驳回";
        }
        if (GradeConstants.REVIEW_CLOSED.equals(status)) {
            return "超窗关闭";
        }
        return status;
    }

    private String maskName(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        return name.length() == 1 ? name : name.substring(0, 1) + "*";
    }

    private String fmt(java.time.LocalDateTime t) {
        return t == null ? "" : t.format(TS);
    }

    private String num(java.math.BigDecimal n) {
        return n == null ? "" : n.toPlainString();
    }

    private String csv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }
}
