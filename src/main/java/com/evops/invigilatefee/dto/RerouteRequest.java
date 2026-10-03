package com.evops.invigilatefee.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * 改派：原申请只读保留为历史版本，按新路线（新考点/出发地/交通方式/出行日期）重新选取规则版本并重新估算。
 */
@Data
public class RerouteRequest {
    /** 改派后的批次（决定新考点与项目）。 */
    @NotNull(message = "改派后批次 id 不能为空")
    private Long batchId;
    @NotBlank(message = "改派后出发地编码不能为空")
    private String originCode;
    @NotBlank(message = "改派后交通方式不能为空")
    private String transportMode;
    @NotNull(message = "改派后出行日期不能为空")
    private LocalDate travelDate;
    private String reason;
}
