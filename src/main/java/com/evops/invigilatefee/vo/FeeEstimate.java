package com.evops.invigilatefee.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 单次交通补贴估算明细（不落库）：里程单价×距离→每程封顶→与票面取小，并给出异常判定。
 */
@Data
public class FeeEstimate {
    private String projectCode;
    private String ruleCode;
    private String ruleVersionNo;
    private String transportMode;
    private BigDecimal distanceKm;
    /** 命中交通方式的里程单价（元/公里）。 */
    private BigDecimal pricePerKm;
    /** 每程封顶（元，null 表示不封顶）。 */
    private BigDecimal capPerTrip;

    /** 未封顶里程额 = 单价 × 距离。 */
    private BigDecimal mileageAmount;
    /** 封顶后里程估算 = min(mileageAmount, capPerTrip)。 */
    private BigDecimal estimatedAmount;
    /** 票据票面合计。 */
    private BigDecimal declaredAmount;
    /** 应付 = min(估算, 票面合计)。 */
    private BigDecimal payableAmount;

    /** 异常阈值（随规则版本冻结）。 */
    private BigDecimal anomalyRate;
    private BigDecimal anomalyAmountLimit;
    /** 是否异常（偏差超阈值或超上限或无票）。 */
    private boolean abnormal;
    /** 异常原因（可读，中文）。 */
    private List<String> anomalyReasons = new ArrayList<>();
}
