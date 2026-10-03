package com.evops.invigilatefee.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 跨区监考交通补贴规则模块：按项目建档，规则下挂多个版本。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_travel_allowance_rule")
public class TravelAllowanceRule extends BaseEntity {
    private String code;
    private String name;
    /** 适用项目（缺省 DEFAULT 通用），与批次 project_code 对齐。 */
    private String projectCode;
    private String remark;
}
