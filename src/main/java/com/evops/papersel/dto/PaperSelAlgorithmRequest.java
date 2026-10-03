package com.evops.papersel.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class PaperSelAlgorithmRequest {
    @NotBlank(message = "算法编码不能为空")
    private String code;
    @NotBlank(message = "算法名称不能为空")
    private String name;
    /** 项目编码，缺省 DEFAULT */
    private String projectCode;
    private String remark;
}
