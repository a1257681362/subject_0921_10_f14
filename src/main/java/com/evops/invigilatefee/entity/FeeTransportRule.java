package com.evops.invigilatefee.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 交通补贴规则模块：按项目建档，规则下挂多个版本。
 * 版本三段式 DRAFT→ACTIVE→DISABLED，同规则同时刻仅一版 ACTIVE。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_fee_transport_rule")
public class FeeTransportRule extends BaseEntity {
    private String code;
    private String name;
    /** 适用项目（缺省 DEFAULT 通用）。 */
    private String projectCode;
    /** ENABLED / DISABLED（模块停用，历史申请不受影响）。 */
    private String status;
    private String remark;
}
