package com.evops.invigilatefee.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import java.math.BigDecimal;

/** 区域→考点距离建档：同一对（区域, 考点）唯一。 */
@Data
public class FeeDistanceRequest {
    @NotBlank(message = "出发区域编码不能为空")
    private String regionCode;
    @NotNull(message = "考点 id 不能为空")
    private Long siteId;
    @NotNull(message = "距离（公里）不能为空")
    @Positive(message = "距离必须大于 0")
    private BigDecimal distanceKm;
    private String remark;
}
