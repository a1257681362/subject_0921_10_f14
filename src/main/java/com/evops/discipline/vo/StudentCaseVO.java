package com.evops.discipline.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 学员本人脱敏视图：只回自己案件的状态与结论，不回证件号、监考上报人、承办人、证据原文/附件、催办等内部信息。
 * 姓名做掩码（保留姓），用于学员凭报名号自查案件进度。
 */
@Data
public class StudentCaseVO {
    private String caseNo;
    private Long batchId;
    private String batchCode;
    /** 掩码姓名（如 张*）。 */
    private String maskedName;
    /** 违纪类型编码。 */
    private String violationType;
    /** 违纪类型中文名。 */
    private String violationLabel;
    /** 当前状态编码。 */
    private String status;
    /** 当前状态中文名。 */
    private String statusLabel;
    /** 处理决定类型（决定作出后可见）。 */
    private String decisionType;
    private String decisionTypeLabel;
    /** 处理决定正文（送达学员，决定作出后可见）。 */
    private String decisionContent;
    private LocalDateTime reportedAt;
    private LocalDateTime decisionDeadline;
    private LocalDateTime decidedAt;
    private LocalDateTime executedAt;
    private LocalDateTime revokedAt;
    private Boolean sealed;
    private LocalDateTime sealedAt;
    /** 状态轨迹（脱敏：只给阶段与钟点，不给经办/复核人）。 */
    private List<TimelineItem> timeline = new ArrayList<>();

    @Data
    public static class TimelineItem {
        private String changeType;
        private String changeTypeLabel;
        private String status;
        private LocalDateTime changedAt;
    }
}
