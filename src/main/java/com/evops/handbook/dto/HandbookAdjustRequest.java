package com.evops.handbook.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.time.LocalDateTime;

/**
 * 手册调整请求：另建新版本（先待确认），不覆盖旧版；字段留空表示沿用上一版内容。
 */
@Data
public class HandbookAdjustRequest {
    private String postDescription;
    private String signInLocation;
    private String emergencyContact;
    private String emergencyPhone;
    @NotBlank(message = "调整原因不能为空")
    private String reason;
    /** 同时调整过期时刻（可空；不传保持不变）。 */
    private LocalDateTime expireAt;
}
