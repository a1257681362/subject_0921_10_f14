package com.evops.invigilatefee.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 出发区域：跨区交通补贴的起点口径。区域→考点距离按区域在 t_fee_distance 维护。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_fee_region")
public class FeeRegion extends BaseEntity {
    private String code;
    private String name;
    /** ENABLED / DISABLED */
    private String status;
    private String remark;
}
