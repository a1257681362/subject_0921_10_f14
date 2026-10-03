package com.evops.invigilatefee.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;

/**
 * 异常报销人工审核结论（仅 MANUAL_REVIEW）：
 * approved=true 放行并直接确认锁定（人工对金额负责）；approved=false 驳回终结。驳回必须写意见。
 */
@Data
public class FeeManualReviewRequest {
    @NotNull(message = "审核结论不能为空")
    private Boolean approved;
    /** 结论意见 / 依据（驳回必填）。 */
    private String comment;
}
