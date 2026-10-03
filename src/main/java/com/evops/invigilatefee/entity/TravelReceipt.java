package com.evops.invigilatefee.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 报销票据：教师只能提交本人凭证。票据金额合计回填报销单参与异常判定。
 * 状态：SUBMITTED 已提交 / APPROVED 已核入 / REJECTED 不予核入。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_travel_receipt")
public class TravelReceipt extends BaseEntity {
    private Long reimbursementId;
    private Long invigilatorId;
    private String invigilatorCode;
    private String invigilatorName;
    /** INVOICE 发票 / TICKET 车票 / E_TICKET 电子客票 / OTHER 其他 */
    private String receiptType;
    private String receiptNo;
    private BigDecimal amount;
    private LocalDate travelDate;
    /** 凭证影像/附件引用（本版只存引用，不做文件存储）。 */
    private String attachmentRef;
    /** SUBMITTED / APPROVED / REJECTED */
    private String status;
    private String submitRemark;
    private String operator;
    private LocalDateTime submittedAt;
}
