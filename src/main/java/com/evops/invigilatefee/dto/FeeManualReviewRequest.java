package com.evops.invigilatefee.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 异常金额人工核定：仅 MANUAL_REVIEW 可处理。
 * approved=true 放行（可显式给核定金额，为空取票据合计）；approved=false 驳回必须写明原因。
 * 类名加 Fee 前缀以避免与 regcond 模块同名 DTO 的 MyBatis 类型别名冲突。
 */
@Data
public class FeeManualReviewRequest {
    private Boolean approved;
    /** 人工核定金额（放行时可覆盖；为空取票据合计）。 */
    private BigDecimal approvedAmount;
    private String comment;
}
