package com.evops.invigilatefee.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 单一交通方式计费口径（规则版本 tariffs JSON 的一项）：
 * - DISTANCE 按里程：perKmRate × 里程，capAmount 非空时封顶；里程低于 minDistance 不计；
 * - FIXED 定额：直接取 fixedAmount。
 * receiptRequired 标记该方式是否必须附审批票据（如自驾按里程可不强制，出租/网约车需票据）。
 */
@Data
public class ModeTariff {
    /** BUS / RAIL / TAXI / SELF_DRIVE / OTHER */
    private String transportMode;
    /** DISTANCE / FIXED */
    private String calcMode;
    /** 每公里单价（DISTANCE 用）。 */
    private BigDecimal perKmRate;
    /** 里程计费封顶额；为空不封顶。 */
    private BigDecimal capAmount;
    /** 定额金额（FIXED 用）。 */
    private BigDecimal fixedAmount;
    /** 最低里程门槛（DISTANCE 用，缺省 0）。 */
    private BigDecimal minDistance;
    /** 是否必须附审批票据。 */
    private Boolean receiptRequired;
}
