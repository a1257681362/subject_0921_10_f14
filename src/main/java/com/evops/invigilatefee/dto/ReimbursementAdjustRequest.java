package com.evops.invigilatefee.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * 报销调整：确认锁定后唯一修订入口。变动额可正可负，只追加台账。
 */
@Data
public class ReimbursementAdjustRequest {
    @NotNull(message = "调整金额不能为空")
    private BigDecimal adjustAmount;
    @NotBlank(message = "调整原因不能为空")
    private String reason;
}
