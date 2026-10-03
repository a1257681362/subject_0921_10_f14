package com.evops.invigilatefee.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import java.util.List;

/**
 * 改派：监考教师改到另一考点 / 另一出发路线。
 * 原申请冻结为只读（REASSIGNED），按新批次考点 + 新区域 / 出行日期 / 交通方式新建一条申请重新估算。
 */
@Data
public class FeeReassignRequest {
    /** 改派后前往的批次（带出其考点 / 项目）。 */
    private Long newBatchId;
    /** 改派后的出发区域编码；为空沿用原区域。 */
    private String newRegionCode;
    /** 改派后出行日期；为空取新批次考试日期。 */
    private java.time.LocalDate newTravelDate;
    /** 改派后交通方式；为空沿用原方式。 */
    private String newTransportMode;

    /** 改派时随新路线一并提交的票据（可空）：带则新单直接进入待审核（异常转人工）；不带则新单先 DRAFT 待补票。 */
    @Valid
    private List<FeeClaimSubmitRequest.FeeReceiptItem> newReceipts;

    @NotBlank(message = "改派原因不能为空")
    private String reason;
}

