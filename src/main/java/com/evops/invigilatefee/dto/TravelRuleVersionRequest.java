package com.evops.invigilatefee.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotEmpty;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 规则版本建档（草稿）：发布即冻结各交通方式口径与票据偏离阈值；版本按出行日期生效。
 */
@Data
public class TravelRuleVersionRequest {
    /** 生效日期闭区间；为空表示长期有效。 */
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    @NotEmpty(message = "计费口径不能为空")
    @Valid
    private List<ModeTariff> tariffs;
    /** 票据金额相对试算金额的允许偏离比例（如 0.20 = ±20%）；为空取默认 20%。 */
    private BigDecimal deviationThreshold;
    private String remark;
}
