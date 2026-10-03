package com.evops.invigilatefee.dto;

import lombok.Data;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * 考点路程建档：出发地 → 考点的单程公里数，作为按里程试算的唯一口径来源。
 */
@Data
public class SiteDistanceRequest {
    @NotBlank(message = "出发地编码不能为空")
    private String originCode;
    @NotBlank(message = "出发地名称不能为空")
    private String originName;
    @NotNull(message = "考点 id 不能为空")
    private Long siteId;
    @NotNull(message = "单程公里数不能为空")
    @DecimalMin(value = "0", message = "单程公里数不能为负")
    private BigDecimal distanceKm;
    private String remark;
}
