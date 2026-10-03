package com.evops.handbook.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 负责人放行：可指定生效时刻，缺省取服务器当前钟点。放行才生成打印版本。
 */
@Data
public class HandbookReleaseRequest {
    private LocalDateTime effectiveAt;
}
