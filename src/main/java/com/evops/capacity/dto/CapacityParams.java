package com.evops.capacity.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 容量规则参数（发布时冻结的 JSON 口径）：
 * - windowStart/windowEnd 候补窗口起止，按考点配，成对、起早于止；
 * - offerTimeoutMinutes 递补通知确认时限（分钟），超时以服务器钟点判定；
 * - capacity 本批次放号容量（>0）。
 */
@Data
public class CapacityParams {
    private LocalDateTime windowStart;
    private LocalDateTime windowEnd;
    private Integer offerTimeoutMinutes;
    private Integer capacity;
}
