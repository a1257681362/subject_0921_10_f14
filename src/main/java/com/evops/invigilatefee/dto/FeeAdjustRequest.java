package com.evops.invigilatefee.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * 报销确认锁定后的修订：只追加调整台账，变动额（可正可负）累加到应付。
 */
@Data
public class FeeAdjustRequest {
    @NotNull(message = "调整金额不能为空")
    private BigDecimal adjustAmount;
    @NotBlank(message = "调整原因不能为空")
    private String reason;
}
