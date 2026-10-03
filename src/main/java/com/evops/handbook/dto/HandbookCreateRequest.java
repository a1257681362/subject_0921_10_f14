package com.evops.handbook.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.time.LocalDateTime;

/**
 * 手册写入请求：内容写入 evops-base 后先进入待确认状态。
 */
@Data
public class HandbookCreateRequest {
    @NotNull(message = "考试批次 id 不能为空")
    private Long batchId;
    @NotBlank(message = "岗位说明不能为空")
    private String postDescription;
    @NotBlank(message = "签到地点不能为空")
    private String signInLocation;
    @NotBlank(message = "应急联系人不能为空")
    private String emergencyContact;
    @NotBlank(message = "应急联系电话不能为空")
    private String emergencyPhone;
    /** 过期时刻：为空表示不自动过期。 */
    private LocalDateTime expireAt;
    private String remark;
}
