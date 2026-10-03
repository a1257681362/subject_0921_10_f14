package com.evops.invigilatefee.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/** 财务驳回报销（SUBMITTED / MANUAL_REVIEW → REJECTED），须填原因。 */
@Data
public class FeeRejectRequest {
    @NotBlank(message = "驳回原因不能为空")
    private String reason;
}
