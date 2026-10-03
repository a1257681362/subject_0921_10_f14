package com.evops.discipline.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotEmpty;
import java.util.List;

/**
 * 立案后补充证据（只追加，不修改不删除已有证据）。
 */
@Data
public class EvidenceAddRequest {

    @NotEmpty(message = "补充证据至少一条")
    @Valid
    private List<CaseReportRequest.EvidenceItem> evidences;
}
