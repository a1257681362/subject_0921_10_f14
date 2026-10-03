package com.evops.invigilatefee.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/** 交通补贴规则模块建档（按项目）。 */
@Data
public class FeeRuleRequest {
    @NotBlank(message = "规则编码不能为空")
    private String code;
    @NotBlank(message = "规则名称不能为空")
    private String name;
    /** 适用项目，缺省 DEFAULT。 */
    private String projectCode;
    private String remark;
}
