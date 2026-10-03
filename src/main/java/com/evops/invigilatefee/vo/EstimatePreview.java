package com.evops.invigilatefee.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 交通补贴试算结果：按 出行日期命中的规则版本 + 交通方式口径 + 考点里程 计算，不落库。
 * 申请 / 改派时返回同一份口径，便于教师与财务核对金额来源。
 */
@Data
public class EstimatePreview {
    private Long batchId;
    private String batchCode;
    private Long siteId;
    private String siteCode;
    private String siteName;
    private String projectCode;
    private String originCode;
    private String originName;
    private String transportMode;
    private LocalDate travelDate;
    private BigDecimal distanceKm;
    /** 命中并冻结的规则版本（按出行日期选取）。 */
    private Long ruleVersionId;
    private String ruleVersionNo;
    /** DISTANCE / FIXED */
    private String calcMode;
    private BigDecimal perKmRate;
    private BigDecimal capAmount;
    private BigDecimal fixedAmount;
    private Boolean receiptRequired;
    /** 试算金额。 */
    private BigDecimal estimatedAmount;
    private String note;
}
