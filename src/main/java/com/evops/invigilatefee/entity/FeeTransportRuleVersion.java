package com.evops.invigilatefee.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 交通补贴规则版本：发布即冻结各交通方式单价 / 每程封顶与异常阈值。
 * 报销金额按“出行日期”命中的 ACTIVE 版本计算，申请永久记下 rule_version_no，规则以后升级不改历史口径。
 * modesJson 结构见 FeeModeRate：[{transportMode, pricePerKm, capPerTrip}]。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_fee_transport_rule_version")
public class FeeTransportRuleVersion extends BaseEntity {
    private Long ruleId;
    /** 规则 code + -R + 递增序号，如 JT-RULE-R1。 */
    private String versionNo;
    /** DRAFT / ACTIVE / DISABLED */
    private String status;
    /** 各交通方式口径 JSON。 */
    private String modesJson;
    /** 异常阈值：估算与申报 / 票据合计偏差比例超此值转人工（0.20=20%）。 */
    private BigDecimal anomalyRate;
    /** 异常绝对上限（元，可空）：票面 / 申报合计超过该值转人工。 */
    private BigDecimal anomalyAmountLimit;
    private String remark;
    private String publishedBy;
    private LocalDateTime publishedAt;
    private String disabledBy;
    private LocalDateTime disabledAt;
    private String operator;
}
