package com.evops.invigilatefee.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 跨区交通补贴报销申请主表：一人一批次一出行一条。金额 / 状态取当前口径，版本链见 t_fee_claim_version。
 * 提交时按出行日期选规则版本、按区域→考点距离冻结公里数；应付 = min(里程估算（封顶后）, 票面合计)。
 * 状态：DRAFT 待提交 / SUBMITTED 待审核 / MANUAL_REVIEW 异常转人工 / CONFIRMED 已确认只读 /
 *       REJECTED 已驳回 / REASSIGNED 已改派（原单只读冻结，新单见 reassigned_to_claim_id）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_fee_claim")
public class FeeClaim extends BaseEntity {
    private String claimNo;
    private Long batchId;
    private String batchCode;
    private String projectCode;
    private Long siteId;
    private String siteCode;
    private String siteName;

    private Long invigilatorId;
    private String invigilatorCode;
    private String invigilatorName;

    private Long regionId;
    private String regionCode;
    private String regionName;
    /** 申请时冻结的单程公里数（改派新单重新取路线）。 */
    private BigDecimal distanceKm;

    /** 规则版本按出行日期选择。 */
    private LocalDate travelDate;
    private Long ruleId;
    private String ruleCode;
    private Long ruleVersionId;
    private String ruleVersionNo;

    /** BUS / TRAIN / TAXI / CAR / OTHER */
    private String transportMode;
    /** 里程单价×距离并封顶后的系统估算。 */
    private BigDecimal estimatedAmount;
    /** 教师随票据申报的票面合计。 */
    private BigDecimal declaredAmount;
    /** 应付 = min(估算, 票面合计)；确认后调整单在此累加。 */
    private BigDecimal payableAmount;

    private Integer currentVersion;
    private String status;
    /** 系统判定金额异常（偏差超阈值或超上限）。 */
    private Boolean abnormal;

    /** 改派新单回指原单：本单由哪条原申请改派而来。 */
    private Long reassignOfClaimId;
    /** 原单指向改派新单。 */
    private Long reassignedToClaimId;

    private String confirmedBy;
    private LocalDateTime confirmedAt;
    private String reviewedBy;
    private LocalDateTime reviewedAt;
    private String reviewComment;
    private String closedRemark;
    private String remark;
    private String operator;
}
