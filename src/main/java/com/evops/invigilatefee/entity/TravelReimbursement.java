package com.evops.invigilatefee.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 交通补贴报销申请主表：一次跨区出行一条。金额/状态取当前版本；版本链见 t_travel_reimb_version。
 * 状态：DRAFT 草稿 / SUBMITTED 待财务 / MANUAL_REVIEW 异常转人工 / CONFIRMED 已确认 / REJECTED 已驳回。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_travel_reimbursement")
public class TravelReimbursement extends BaseEntity {
    private String reimbursementNo;
    private Long batchId;
    private Long siteId;
    private String siteCode;
    private String siteName;
    private String projectCode;
    private Long invigilatorId;
    private String invigilatorCode;
    private String invigilatorName;
    /** 当前路线出发地（改派后为新路线；旧路线留版本链/改派记录）。 */
    private String originCode;
    private String originName;
    /** BUS / RAIL / TAXI / SELF_DRIVE / OTHER */
    private String transportMode;
    /** 出行日期（规则版本按此日期选取）。 */
    private LocalDate travelDate;
    private BigDecimal distanceKm;
    /** 计费口径冻结。 */
    private Long ruleVersionId;
    private String ruleVersionNo;
    /** DISTANCE / FIXED */
    private String calcMode;
    /** 系统按规则+里程试算金额。 */
    private BigDecimal estimatedAmount;
    /** 已提交审批票据合计。 */
    private BigDecimal receiptAmount;
    /** 最终核定金额（确认时落定；人工核定时可改）。 */
    private BigDecimal approvedAmount;
    private Integer currentVersion;
    private String status;
    /** 异常转人工原因。 */
    private String abnormalReason;
    private String reviewer;
    private LocalDateTime reviewedAt;
    private String reviewComment;
    private String confirmedBy;
    private LocalDateTime confirmedAt;
    /** 确认后由调整单累计的变动额；最终应付 = approvedAmount + adjustTotal。 */
    private BigDecimal adjustTotal;
    private Integer adjustCount;
    private String remark;
    private String operator;
}
