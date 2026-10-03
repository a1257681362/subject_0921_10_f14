package com.evops.invigilatefee.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 交通补贴规则版本：发布即冻结各交通方式计费口径（tariffs JSON）与票据偏离阈值；
 * 状态 DRAFT → ACTIVE → DISABLED；报销按出行日期取生效版并冻结版本号，口径可追溯。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_travel_allowance_rule_version")
public class TravelAllowanceRuleVersion extends BaseEntity {
    private Long ruleId;
    private String versionNo;
    /** DRAFT / ACTIVE / DISABLED */
    private String status;
    /** 生效日期闭区间，按出行日期选取；为空表示不限。 */
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    /** 各交通方式计费口径冻结 JSON。 */
    private String tariffs;
    /** 票据金额相对试算金额的允许偏离比例（如 0.20 = ±20%）。 */
    private BigDecimal deviationThreshold;
    private String remark;
    private String publishedBy;
    private LocalDateTime publishedAt;
    private String disabledBy;
    private LocalDateTime disabledAt;
    private String operator;
}
