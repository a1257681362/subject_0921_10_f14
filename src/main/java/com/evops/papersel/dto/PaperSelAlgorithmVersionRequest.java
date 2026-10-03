package com.evops.papersel.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;

@Data
public class PaperSelAlgorithmVersionRequest {
    @Valid
    @NotNull(message = "算法参数不能为空")
    private PaperSelParams params;
    private String remark;
}
