package com.evops.invigilatefee.common;

/**
 * 跨区监考交通补贴报销业务常量：交通方式、规则版本状态、申请状态、版本变更类型。
 * 本模块独立核算，不与监考聘任 / 结算耦合（申请仅关联批次与本人监考编码）。
 */
public final class InvigilateFeeConstants {

    private InvigilateFeeConstants() {
    }

    /** 默认项目编码（批次未指定项目时）。 */
    public static final String PROJECT_DEFAULT = "DEFAULT";

    // 通用启用 / 停用
    public static final String ENABLED = "ENABLED";
    public static final String DISABLED = "DISABLED";

    // 交通方式
    /** 大巴 / 长途客车 */
    public static final String MODE_BUS = "BUS";
    /** 火车 / 高铁 */
    public static final String MODE_TRAIN = "TRAIN";
    /** 出租车 / 网约车 */
    public static final String MODE_TAXI = "TAXI";
    /** 自驾（按里程油耗 / 过路口径） */
    public static final String MODE_CAR = "CAR";
    /** 其他（须附票据） */
    public static final String MODE_OTHER = "OTHER";

    // 规则版本状态（模块 + 版本 + 状态 三段式）
    /** 草稿：可改口径，未参与计算。 */
    public static final String VERSION_DRAFT = "DRAFT";
    /** 生效：同一规则同时刻仅一版生效，报销按出行日期选生效版。 */
    public static final String VERSION_ACTIVE = "ACTIVE";
    /** 停用：被新版发布顶替或人工停用；历史申请保留其版本号。 */
    public static final String VERSION_DISABLED = "DISABLED";

    // 报销申请（当前版本）状态
    /** 待提交：教师暂存（本版实现提交即落 SUBMITTED，DRAFT 作为初始态保留）。 */
    public static final String CLAIM_DRAFT = "DRAFT";
    /** 待财务审核：金额正常，等待确认。 */
    public static final String CLAIM_SUBMITTED = "SUBMITTED";
    /** 异常转人工：金额偏差超阈值或超上限，须人工结论。 */
    public static final String CLAIM_MANUAL_REVIEW = "MANUAL_REVIEW";
    /** 已确认：应付锁定，只读；修订一律走调整单。 */
    public static final String CLAIM_CONFIRMED = "CONFIRMED";
    /** 已驳回：财务 / 人工驳回，终结（仍只读留痕）。 */
    public static final String CLAIM_REJECTED = "REJECTED";
    /** 已改派：原申请冻结只读，新路线另建新申请重新估算。 */
    public static final String CLAIM_REASSIGNED = "REASSIGNED";

    // 申请版本变更类型
    /** 教师提交本人凭证 */
    public static final String CHANGE_SUBMIT = "SUBMIT";
    /** 系统判定金额异常，转人工审核 */
    public static final String CHANGE_MANUAL_REVIEW = "MANUAL_REVIEW";
    /** 人工审核结论（放行转待确认 / 直接确认） */
    public static final String CHANGE_MANUAL_APPROVE = "MANUAL_APPROVE";
    /** 财务确认，应付锁定 */
    public static final String CHANGE_CONFIRM = "CONFIRM";
    /** 财务 / 人工驳回 */
    public static final String CHANGE_REJECT = "REJECT";
    /** 原单改派冻结 */
    public static final String CHANGE_REASSIGN = "REASSIGN";
    /** 改派新单（按新路线重新估算） */
    public static final String CHANGE_REASSIGN_NEW = "REASSIGN_NEW";

    /** 本人视图请求头：传监考人员编码，只能提交 / 查看本人申请与票据；不传为财务全量视图。 */
    public static final String SELF_HEADER = "X-Self-Code";
}
