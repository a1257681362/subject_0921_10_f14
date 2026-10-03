package com.evops.invigilatefee.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.DecimalMax;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;

/**
 * 交通补贴规则草稿版本：提交各交通方式单价 / 封顶与异常阈值；发布即冻结。
 */
@Data
public class FeeRuleVersionRequest {
    @NotEmpty(message = "交通方式计补口径不能为空")
    @Valid
    private List<FeeModeRate> modes;

    /** 异常偏差比例阈值（[0,1]，缺省 0.20）：估算与票面合计偏差超此值转人工。 */
    @DecimalMin(value = "0", message = "异常偏差比例不能为负")
    @DecimalMax(value = "1", message = "异常偏差比例不能超过 1")
    private BigDecimal anomalyRate;

    /** 异常绝对上限（元，可空）。 */
    @DecimalMin(value = "0", message = "异常金额上限不能为负")
    private BigDecimal anomalyAmountLimit;

    private String remark;
}
