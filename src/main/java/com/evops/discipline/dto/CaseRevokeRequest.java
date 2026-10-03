package com.evops.discipline.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 撤销立案申请：必须走复核，落 REVOKE_PENDING；申请人取 X-Operator。
 */
@Data
public class CaseRevokeRequest {

    @NotBlank(message = "撤销立案必须填写申请理由")
    private String reason;
}
