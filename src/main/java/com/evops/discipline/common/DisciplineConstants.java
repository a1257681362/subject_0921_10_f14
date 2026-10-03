package com.evops.discipline.common;

/**
 * 违纪立案与案件封存业务常量。
 * 三段式：违纪案件（模块主表）/ 案件版本（只追加）/ 案件状态（取当前版本）。
 */
public final class DisciplineConstants {

    private DisciplineConstants() {
    }

    /** 学员脱敏自查请求头：传报名号即本人视图，只回自己案件的脱敏信息。 */
    public static final String SELF_HEADER = "X-Self-Code";

    // 案件来源
    /** 监考现场上报。 */
    public static final String SOURCE_LIVE = "LIVE";
    /** 纸面存量补报。 */
    public static final String SOURCE_PAPER = "PAPER";

    // 违纪类型（与缺考裁定的违纪记录同口径）
    public static final String VIOLATION_CHEAT = "CHEAT";
    public static final String VIOLATION_IMPERSONATE = "IMPERSONATE";
    public static final String VIOLATION_PROHIBITED = "PROHIBITED";
    public static final String VIOLATION_DISORDER = "DISORDER";
    public static final String VIOLATION_OTHER = "OTHER";

    // 案件状态（状态机）
    /** 已立案待决定：监考上报附证据即立案，限期出处理决定。 */
    public static final String CASE_REPORTED = "REPORTED";
    /** 已出处理决定：决定类型对应成绩作废/禁考后果，待执行。 */
    public static final String CASE_DECIDED = "DECIDED";
    /** 后果已执行/落档：成绩作废已回写成绩档案（晚到成绩挂起自动回写）。 */
    public static final String CASE_EXECUTED = "EXECUTED";
    /** 撤销复核中：撤销立案必须走复核，期间原决定暂停。 */
    public static final String CASE_REVOKE_PENDING = "REVOKE_PENDING";
    /** 已撤销立案：复核通过，成绩作废/禁考后果同步取消并还原。 */
    public static final String CASE_REVOKED = "REVOKED";
    /** 已结案封存：封存记录仅授权岗可查。 */
    public static final String CASE_SEALED = "SEALED";

    // 处理决定类型
    /** 警告（不落成绩后果，仅决定留档）。 */
    public static final String DECISION_WARNING = "WARNING";
    /** 成绩作废。 */
    public static final String DECISION_SCORE_VOID = "SCORE_VOID";
    /** 禁考。 */
    public static final String DECISION_BAN = "BAN";
    /** 成绩作废并禁考。 */
    public static final String DECISION_SCORE_VOID_AND_BAN = "SCORE_VOID_AND_BAN";

    // 后果类型
    public static final String CONS_SCORE_VOID = "SCORE_VOID";
    public static final String CONS_BAN = "BAN";

    // 后果状态
    /** 待执行（禁考类决定生成即可执行；成绩作废待执行回写）。 */
    public static final String CONS_PENDING = "PENDING";
    /** 已执行。 */
    public static final String CONS_EXECUTED = "EXECUTED";
    /** 成绩尚未导入：挂起，成绩落库时自动回写作废。 */
    public static final String CONS_PENDING_SCORE = "PENDING_SCORE";
    /** 随立案撤销取消。 */
    public static final String CONS_CANCELLED = "CANCELLED";

    // 版本变更类型
    public static final String CHANGE_CREATE = "CREATE";
    public static final String CHANGE_DECIDE = "DECIDE";
    public static final String CHANGE_EXECUTE = "EXECUTE";
    public static final String CHANGE_REVOKE_APPLY = "REVOKE_APPLY";
    public static final String CHANGE_REVOKE_APPROVE = "REVOKE_APPROVE";
    public static final String CHANGE_REVOKE_REJECT = "REVOKE_REJECT";
    public static final String CHANGE_SEAL = "SEAL";

    // 证据类型
    public static final String EVIDENCE_PHOTO = "PHOTO";
    public static final String EVIDENCE_VIDEO = "VIDEO";
    public static final String EVIDENCE_DOC = "DOC";
    public static final String EVIDENCE_STATEMENT = "STATEMENT";
    public static final String EVIDENCE_OTHER = "OTHER";

    // 催办通知状态
    public static final String URGE_NEW = "NEW";
    public static final String URGE_READ = "READ";
    public static final String URGE_CHANNEL_IN_SITE = "IN_SITE";

    // 通知类型（复用 t_score_notification，成绩/违纪通知一处查）
    /** 违纪处理催办（发给承办人）。 */
    public static final String NOTIFY_DISCIPLINE_URGE = "DISCIPLINE_URGE";
    /** 违纪处理决定结果（发给学员）。 */
    public static final String NOTIFY_DISCIPLINE_DECISION = "DISCIPLINE_DECISION";
    /** 违纪案件撤销结论（发给学员）。 */
    public static final String NOTIFY_DISCIPLINE_REVOKE = "DISCIPLINE_REVOKE";

    // 配置键
    public static final String CFG_DEADLINE_HOURS = "DECISION_DEADLINE_HOURS";
    public static final String CFG_URGE_INTERVAL_HOURS = "URGE_INTERVAL_HOURS";
    public static final String CFG_DEFAULT_ASSIGNEE = "DEFAULT_ASSIGNEE";

    /** 默认处理决定期限（小时）：7×24。 */
    public static final int DEFAULT_DEADLINE_HOURS = 168;
    /** 默认催办间隔（小时）。 */
    public static final int DEFAULT_URGE_INTERVAL_HOURS = 24;
    public static final String DEFAULT_ASSIGNEE_NAME = "考务负责人";

    /** Shiro 授权角色：仅该角色可查封存记录。 */
    public static final String ROLE_ARCHIVER = "discipline_archiver";
}
