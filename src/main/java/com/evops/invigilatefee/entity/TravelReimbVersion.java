package com.evops.invigilatefee.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 报销版本：新建/改派/提交票据/人工核定/确认只追加一版，永不修改。
 * 改派产生 REROUTE 版快照新路线；原申请的路线与金额作为历史版本保留、只读。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_travel_reimb_version")
public class TravelReimbVersion extends BaseEntity {
    private Long reimbursementId;
    private Integer versionNo;
    /** DRAFT / SUBMITTED / MANUAL_REVIEW / CONFIRMED / REJECTED */
    private String status;
    private String originCode;
    private String originName;
    private String transportMode;
    private LocalDate travelDate;
    private BigDecimal distanceKm;
    private Long ruleVersionId;
    private String ruleVersionNo;
    private String calcMode;
    private BigDecimal estimatedAmount;
    private BigDecimal receiptAmount;
    private BigDecimal approvedAmount;
    /** CREATE / REROUTE / RECEIPT / MANUAL_REVIEW / MANUAL_APPROVE / REJECT / CONFIRM */
    private String changeType;
    private String changeReason;
    private Long routeChangeId;
    private String operator;
    private LocalDateTime changedAt;
}
