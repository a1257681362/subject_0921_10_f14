package com.evops.invigilatefee.dto;

import lombok.Data;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * 规则版本内单一交通方式的计补口径：里程单价（元/公里）与每程封顶（元，可空表示不封顶）。
 * 里程估算 = min(pricePerKm × 距离, capPerTrip)。
 */
@Data
public class FeeModeRate {
    /** BUS / TRAIN / TAXI / CAR / OTHER */
    @NotBlank(message = "交通方式不能为空")
    private String transportMode;
    /** 里程单价（元/公里）。 */
    @NotNull(message = "里程单价不能为空")
    @DecimalMin(value = "0", message = "里程单价不能为负")
    private BigDecimal pricePerKm;
    /** 每程封顶（元，可空表示不封顶）。 */
    @DecimalMin(value = "0", message = "每程封顶不能为负")
    private BigDecimal capPerTrip;
}
