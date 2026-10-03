package com.evops.handbook.dto;

import lombok.Data;

/**
 * 补发请求：不传 originReleaseId 时补发当前生效发布批次。
 * 补发保留原经办人、生效时刻与发布批次号。
 */
@Data
public class HandbookReissueRequest {
    private Long originReleaseId;
    private String reason;
}
