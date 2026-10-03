package com.evops.discipline.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.time.LocalDate;

/**
 * 处理决定：限期内由承办人作出；决定类型对应后果（成绩作废 / 禁考），决定即冻结进版本。
 */
@Data
public class CaseDecisionRequest {

    /**
     * WARNING 警告 / SCORE_VOID 成绩作废 / BAN 禁考 / SCORE_VOID_AND_BAN 成绩作废并禁考。
     */
    @NotBlank(message = "处理决定类型不能为空（WARNING / SCORE_VOID / BAN / SCORE_VOID_AND_BAN）")
    private String decisionType;

    /** 处理决定正文（事实认定、依据、结论），必填。 */
    @NotBlank(message = "处理决定内容不能为空")
    private String decisionContent;

    /** 禁考起始日（BAN 类可空，缺省决定作出当天）。 */
    private LocalDate banFrom;

    /** 禁考截止日（BAN 类可空；为空表示长期禁考）。 */
    private LocalDate banUntil;
}
