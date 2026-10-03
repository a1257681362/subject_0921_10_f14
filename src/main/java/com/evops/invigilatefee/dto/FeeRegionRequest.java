package com.evops.invigilatefee.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/** 出发区域建档。 */
@Data
public class FeeRegionRequest {
    @NotBlank(message = "区域编码不能为空")
    private String code;
    @NotBlank(message = "区域名称不能为空")
    private String name;
    private String remark;
}
