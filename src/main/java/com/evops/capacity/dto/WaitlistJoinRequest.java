package com.evops.capacity.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 候补入队请求：ruleId / batchId 二选一定位规则；
 * 同一规则同一报名号重复请求只回原位置，不新建、不插队。
 */
@Data
public class WaitlistJoinRequest {
    private Long ruleId;
    private Long batchId;
    @NotBlank(message = "报名号不能为空")
    private String regNo;
    @NotBlank(message = "姓名不能为空")
    private String name;
    private String phone;
}
