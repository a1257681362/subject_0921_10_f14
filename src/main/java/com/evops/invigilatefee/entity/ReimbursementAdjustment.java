package com.evops.invigilatefee.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 报销调整台账：报销确认锁定后唯一修订入口，只追加。
 * 每条变动额（可正可负）累加到报销单应付（approvedAmount + adjustTotal）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_reimbursement_adjustment")
public class ReimbursementAdjustment extends BaseEntity {
    private Long reimbursementId;
    private Long invigilatorId;
    private String invigilatorCode;
    private String invigilatorName;
    /** 本条变动额（可正可负）。 */
    private BigDecimal adjustAmount;
    private String reason;
    private String operator;
    private LocalDateTime adjustedAt;
}
