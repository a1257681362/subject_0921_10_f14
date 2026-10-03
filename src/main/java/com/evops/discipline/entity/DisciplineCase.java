package com.evops.discipline.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 违纪案件模块（主表）：一案一学员；当前状态取最新版本，版本链见 t_discipline_case_version。
 * 状态：REPORTED 已立案待决定 / DECIDED 已出决定 / EXECUTED 后果已执行 /
 *      REVOKE_PENDING 撤销复核中 / REVOKED 已撤销 / SEALED 已封存。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_discipline_case")
public class DisciplineCase extends BaseEntity {
    /** 案件号 WJ + 批次编码 + 4 位流水。 */
    private String caseNo;
    private Long batchId;
    private Long registrationId;
    private String regNo;
    private String name;
    /** 证件号留档（学员脱敏视图不回传）。 */
    private String idNo;
    /** CHEAT 作弊 / IMPERSONATE 替考 / PROHIBITED 携带违禁品 / DISORDER 扰乱秩序 / OTHER 其他。 */
    private String violationType;
    private String description;
    private LocalDateTime occurredAt;
    /** LIVE 现场上报 / PAPER 纸面补报。 */
    private String source;
    private String status;
    /** 撤销申请期间暂存原状态，复核驳回恢复。 */
    private String prevStatus;
    private Integer currentVersion;
    private String reporter;
    private LocalDateTime reportedAt;
    private String assignee;
    /** 处理决定期限（立案时间 + 配置期限），服务器钟点判定超时。 */
    private LocalDateTime decisionDeadline;
    private Integer urgedCount;
    private LocalDateTime lastUrgedAt;
    /** WARNING / SCORE_VOID / BAN / SCORE_VOID_AND_BAN。 */
    private String decisionType;
    private String decisionContent;
    private String decider;
    private LocalDateTime decidedAt;
    private String revokeApplicant;
    private String revokeReason;
    private String revokeReviewer;
    private String revokeReviewComment;
    private LocalDateTime revokedAt;
    private LocalDateTime executedAt;
    /** 结案封存标记。 */
    private Boolean sealed;
    private String sealedBy;
    private LocalDateTime sealedAt;
    private String sealRemark;
    private String operator;
    private String remark;
}
