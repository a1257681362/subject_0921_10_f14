package com.evops.invigilatefee.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 报销调整台账：申请确认锁定后唯一修订入口，只追加。
 * 每条变动额（可正可负）累加到申请应付；不覆盖确认口径，历史可追溯。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_fee_claim_adjustment")
public class FeeClaimAdjustment extends BaseEntity {
    private Long claimId;
    private Long invigilatorId;
    private String invigilatorCode;
    private String invigilatorName;
    /** 本条变动额（可正可负）。 */
    private BigDecimal adjustAmount;
    private String reason;
    private String operator;
    private LocalDateTime adjustedAt;
}
