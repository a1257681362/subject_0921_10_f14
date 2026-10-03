package com.evops.invigilatefee.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 改派记录：一次改派一条。原路线 → 新路线完整留痕，新路线按新出行日期重新选取规则版本并重新估算。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_travel_route_change")
public class TravelRouteChange extends BaseEntity {
    private Long reimbursementId;
    private Long batchId;
    // 原路线
    private Long fromSiteId;
    private String fromSiteCode;
    private String fromOriginCode;
    private String fromTransportMode;
    private LocalDate fromTravelDate;
    private BigDecimal fromDistanceKm;
    private BigDecimal fromEstimatedAmount;
    // 新路线
    private Long toSiteId;
    private String toSiteCode;
    private String toOriginCode;
    private String toTransportMode;
    private LocalDate toTravelDate;
    private BigDecimal toDistanceKm;
    private String toRuleVersionNo;
    private BigDecimal toEstimatedAmount;
    private String reason;
    private String operator;
    private LocalDateTime changedAt;
}
