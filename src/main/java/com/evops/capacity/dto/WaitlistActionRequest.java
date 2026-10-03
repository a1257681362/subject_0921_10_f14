package com.evops.capacity.dto;

import lombok.Data;

/**
 * 候补动作请求（确认 / 退出 / 退位通用）：
 * waitlistId 或 (ruleId|batchId + regNo) 定位；退位/退出可带 reason。
 */
@Data
public class WaitlistActionRequest {
    private Long waitlistId;
    private Long ruleId;
    private Long batchId;
    private String regNo;
    private String reason;
}
