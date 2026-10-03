package com.evops.invigilatefee.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 报销申请版本：提交 / 转人工 / 人工结论 / 确认 / 驳回 / 改派都只追加一版，永不修改。
 * 改派时原单追加 REASSIGN 版（状态 REASSIGNED），新单首版 REASSIGN_NEW。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_fee_claim_version")
public class FeeClaimVersion extends BaseEntity {
    private Long claimId;
    private Integer versionNo;
    /** SUBMIT / MANUAL_REVIEW / MANUAL_APPROVE / CONFIRM / REJECT / REASSIGN / REASSIGN_NEW */
    private String changeType;
    /** 本版状态：SUBMITTED / MANUAL_REVIEW / CONFIRMED / REJECTED / REASSIGNED 等。 */
    private String status;
    private String transportMode;
    private BigDecimal distanceKm;
    private BigDecimal estimatedAmount;
    private BigDecimal declaredAmount;
    private BigDecimal payableAmount;
    private Boolean abnormal;
    private String ruleVersionNo;
    private String changeReason;
    private String operator;
    private LocalDateTime changedAt;
}
