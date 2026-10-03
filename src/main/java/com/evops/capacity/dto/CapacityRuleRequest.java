package com.evops.capacity.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
public class CapacityRuleRequest {
    /** 规则编码，缺省 CAP + 批次 id */
    private String code;
    @NotBlank(message = "规则名称不能为空")
    private String name;
    /** 批次 id（容量按 考点+批次 配，考点取批次所属考点） */
    @NotNull(message = "批次 id 不能为空")
    private Long batchId;
    private String remark;
}
