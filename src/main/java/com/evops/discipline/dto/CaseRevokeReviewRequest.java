package com.evops.discipline.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;

/**
 * 撤销立案复核：通过（approved=true）撤销案件并同步取消/还原后果；驳回回到申请前状态。
 * 复核人取 X-Operator，须非撤销申请人（双人复核）。
 */
@Data
public class CaseRevokeReviewRequest {

    @NotNull(message = "复核结论不能为空（approved=true/false）")
    private Boolean approved;

    /** 复核意见（驳回必填）。 */
    private String comment;
}
