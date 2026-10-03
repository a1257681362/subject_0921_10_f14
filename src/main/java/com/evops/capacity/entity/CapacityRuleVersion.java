package com.evops.capacity.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 容量规则版本：窗口/时限/容量在建草稿时冻结；发布后永不修改。
 * DRAFT → ACTIVE → DISABLED；同一规则同时刻仅一版 ACTIVE。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_capacity_rule_version")
public class CapacityRuleVersion extends BaseEntity {
    private Long ruleId;
    private String versionNo;
    /** DRAFT / ACTIVE / DISABLED */
    private String status;
    /** 发布时冻结的容量参数 JSON：windowStart/windowEnd/offerTimeoutMinutes/capacity */
    private String params;
    private String remark;
    private String publishedBy;
    private LocalDateTime publishedAt;
    private String disabledBy;
    private LocalDateTime disabledAt;
    private String operator;
}
