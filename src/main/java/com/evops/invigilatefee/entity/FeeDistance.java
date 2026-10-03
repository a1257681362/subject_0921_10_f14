package com.evops.invigilatefee.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 区域→考点距离主数据：同一对（区域, 考点）唯一，里程数为金额计算的距离依据。
 * 改派到新考点即取新路线的距离重新估算；原申请冻结时保留其当时的 distance_km 快照。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_fee_distance")
public class FeeDistance extends BaseEntity {
    private Long regionId;
    private String regionCode;
    private Long siteId;
    private String siteCode;
    private String siteName;
    /** 单程公里数。 */
    private BigDecimal distanceKm;
    /** ENABLED / DISABLED */
    private String status;
    private String remark;
}
