package com.evops.papersel.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class MaterialDisableRequest {
    /** 下架原因（下架必填）；恢复时可空。 */
    @NotBlank(message = "下架原因不能为空")
    private String reason;
}
