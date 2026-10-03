package com.evops.discipline.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 案件版本（只追加）：CREATE 立案 / DECIDE 决定 / EXECUTE 执行 /
 * REVOKE_APPLY 申请撤销 / REVOKE_APPROVE 撤销通过 / REVOKE_REJECT 撤销驳回 / SEAL 封存。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_discipline_case_version")
public class DisciplineCaseVersion extends BaseEntity {
    private Long caseId;
    private Integer versionNo;
    private String status;
    private String changeType;
    private String violationType;
    private String decisionType;
    private String changeReason;
    private String reviewer;
    private String operator;
    private LocalDateTime changedAt;
}
