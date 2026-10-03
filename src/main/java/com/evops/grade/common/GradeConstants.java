package com.evops.grade.common;

/**
 * 出分等级与成绩复核业务常量。
 */
public final class GradeConstants {

    private GradeConstants() {
    }

    /** 默认项目编码（批次未指定项目时）。 */
    public static final String PROJECT_DEFAULT = "DEFAULT";
    /** 助考项目：本批次先跑。 */
    public static final String PROJECT_ZHUKAO = "ZHUKAO";

    // 等级规则版本状态
    /** 草稿：可改分数段，未参与定级。 */
    public static final String VERSION_DRAFT = "DRAFT";
    /** 生效：同一规则同时刻仅一版生效（同项目可按科目各有一条规则），定级只认生效版。 */
    public static final String VERSION_ACTIVE = "ACTIVE";
    /** 停用：被新版本发布顶替或人工停用；历史成绩仍保留其版本号。 */
    public static final String VERSION_DISABLED = "DISABLED";

    // 成绩状态
    public static final String SCORE_RECORDED = "RECORDED";
    public static final String SCORE_REVIEWING = "REVIEWING";

    // 复核状态
    /** 待处理：窗口期内提交。 */
    public static final String REVIEW_SUBMITTED = "SUBMITTED";
    /** 复核通过（改分）。 */
    public static final String REVIEW_APPROVED = "APPROVED";
    /** 复核驳回（维持原分）。 */
    public static final String REVIEW_REJECTED = "REJECTED";
    /** 超过批次复核窗口，系统自动关闭。 */
    public static final String REVIEW_CLOSED = "CLOSED";

    // 通知类型 / 状态
    public static final String NOTIFY_REVIEW_RESULT = "REVIEW_RESULT";
    public static final String NOTIFY_REVIEW_CLOSED = "REVIEW_CLOSED";
    /** 缺考裁定结论通知（裁定确认生效即通知本人）。 */
    public static final String NOTIFY_ABSENCE_RULING = "ABSENCE_RULING";
    public static final String NOTIFY_NEW = "NEW";
    public static final String NOTIFY_READ = "READ";

    // 复核窗口状态（计算值，不入库）
    public static final String WINDOW_NOT_CONFIGURED = "NOT_CONFIGURED";
    public static final String WINDOW_NOT_OPENED = "NOT_OPENED";
    public static final String WINDOW_OPEN = "OPEN";
    public static final String WINDOW_CLOSED = "CLOSED";

    // grade-review-1：复核材料（流转与阅读分离）
    /** 材料在档：当前版本，文件可阅读。 */
    public static final String MATERIAL_ACTIVE = "ACTIVE";
    /** 材料已被新版本替换：旧版不删，仅留摘要，可查不可读正文。 */
    public static final String MATERIAL_SUPERSEDED = "SUPERSEDED";
    /** 材料随申请进入 evops-base 留档。 */
    public static final String MATERIAL_REGISTER = "REGISTER";
    /** 附件版本替换：旧版置 SUPERSEDED 留摘要。 */
    public static final String MATERIAL_REPLACE = "REPLACE";
    /** 阅读人：审核岗（X-Operator）。 */
    public static final String READER_STAFF = "STAFF";
    /** 阅读人：学员本人（X-Self-Code）。 */
    public static final String READER_SELF = "SELF";
}
