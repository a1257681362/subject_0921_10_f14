package com.evops.invigilatefee.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 考点路程主数据：出发地 → 考点的单程公里数。同一起讫唯一；试算金额只认启用中的路程。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_site_distance")
public class SiteDistance extends BaseEntity {
    private String originCode;
    private String originName;
    private Long siteId;
    private String siteCode;
    private String siteName;
    /** 单程公里数。 */
    private BigDecimal distanceKm;
    /** ENABLED / DISABLED */
    private String status;
    private String remark;
}
