package com.evops.invigilatefee.dto;

import lombok.Data;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 报销票据提交：教师只能提交本人凭证。可多次提交，金额合计回填报销单并触发异常判定。
 */
@Data
public class ReceiptSubmitRequest {
    /** INVOICE 发票 / TICKET 车票 / E_TICKET 电子客票 / OTHER 其他 */
    @NotBlank(message = "票据类型不能为空")
    private String receiptType;
    private String receiptNo;
    @NotNull(message = "票据金额不能为空")
    @DecimalMin(value = "0", message = "票据金额不能为负")
    private BigDecimal amount;
    private LocalDate travelDate;
    private String attachmentRef;
    private String submitRemark;
}
