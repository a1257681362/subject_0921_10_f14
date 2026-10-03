package com.evops.grade.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 学员本人复核视图（X-Self-Code）：只能读本人资料。
 * 不含审核岗内部批注；通知回执按 reviewId 挂回原申请一并回显。
 */
@Data
public class ReviewSelfVO {
    private Long id;
    private Long batchId;
    private String regNo;
    /** 掩码姓名（保留姓）。 */
    private String maskedName;
    private String status;
    private String statusLabel;
    private String applyReason;
    private LocalDateTime appliedAt;
    /** 审核岗写明的结论依据（驳回维持/通过改分均必填）。 */
    private String conclusion;
    private LocalDateTime concludedAt;
    private BigDecimal scoreBefore;
    private String gradeBefore;
    private BigDecimal scoreAfter;
    private String gradeAfter;
    /** 本人材料（按进入时间排列；不含内部批注）。 */
    private List<MaterialSelfVO> materials = new ArrayList<>();
    /** 挂回原申请的通知回执（结论通知/超窗关闭通知）。 */
    private List<ReceiptVO> receipts = new ArrayList<>();

    @Data
    public static class MaterialSelfVO {
        private Long materialId;
        private String title;
        private String materialType;
        private String status;
        private Integer currentVersion;
        private LocalDateTime enteredAt;
        /** 当前版文件名/大小/哈希摘要。 */
        private String currentDigest;
        /** 历史版本摘要（附件替换后旧版只留摘要，按版本序）。 */
        private List<VersionDigest> versions = new ArrayList<>();
    }

    @Data
    public static class VersionDigest {
        private Integer versionNo;
        private String status;
        private String changeType;
        private String digestSummary;
        private String changeReason;
        private LocalDateTime enteredAt;
    }

    @Data
    public static class ReceiptVO {
        private Long notificationId;
        private String notifyType;
        private String title;
        private String content;
        private String status;
        private LocalDateTime notifiedAt;
        private LocalDateTime readAt;
    }
}
