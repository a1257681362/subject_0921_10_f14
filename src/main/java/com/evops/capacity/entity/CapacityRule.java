package com.evops.capacity.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 容量规则模块：按 考点+批次 建档，一个批次一条规则，规则下挂多个版本。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_capacity_rule")
public class CapacityRule extends BaseEntity {
    private String code;
    private String name;
    private Long siteId;
    private Long batchId;
    private String remark;
}
