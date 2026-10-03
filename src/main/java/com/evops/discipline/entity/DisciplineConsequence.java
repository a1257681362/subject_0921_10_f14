package com.evops.discipline.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 案件后果：决定类型对应 成绩作废（SCORE_VOID）/ 禁考（BAN）。
 * 成绩作废执行即回写成绩档案（valid=false 并挂案件号）；成绩尚未导入挂 PENDING_SCORE，落库时自动回写。
 * 立案撤销：已执行后果同步取消并还原（成绩恢复有效），禁考区间作废。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_discipline_consequence")
public class DisciplineConsequence extends BaseEntity {
    private Long caseId;
    private String caseNo;
    private Long batchId;
    private Long registrationId;
    private String regNo;
    private String name;
    /** SCORE_VOID 成绩作废 / BAN 禁考。 */
    private String consType;
    /** PENDING / EXECUTED / PENDING_SCORE / CANCELLED。 */
    private String status;
    private Long scoreId;
    private BigDecimal scoreBefore;
    private String gradeBefore;
    /** 禁考闭区间 [banFrom, banUntil]；banUntil 为空表示长期禁考。 */
    private LocalDate banFrom;
    private LocalDate banUntil;
    private String executedBy;
    private LocalDateTime executedAt;
    private String executeRemark;
    private String cancelledBy;
    private LocalDateTime cancelledAt;
    private String cancelReason;
}
