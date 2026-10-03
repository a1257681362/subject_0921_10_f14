package com.evops.invigilatefee.common;

/**
 * 跨区监考交通补贴业务常量：交通方式、规则版本状态、报销状态、版本变更类型、票据状态、本人视图请求头。
 */
public final class InvigilateFeeConstants {

    private InvigilateFeeConstants() {
    }

    /** 默认项目编码（批次未指定项目时）。 */
    public static final String PROJECT_DEFAULT = "DEFAULT";

    // 通用启用/停用
    public static final String ENABLED = "ENABLED";
    public static final String DISABLED = "DISABLED";

    // 交通方式
    /** 公交 / 长途大巴。 */
    public static final String MODE_BUS = "BUS";
    /** 火车 / 轨道交通。 */
    public static final String MODE_RAIL = "RAIL";
    /** 出租 / 网约车。 */
    public static final String MODE_TAXI = "TAXI";
    /** 自驾（私车公用）。 */
    public static final String MODE_SELF_DRIVE = "SELF_DRIVE";
    /** 其他。 */
    public static final String MODE_OTHER = "OTHER";

    // 计费方式（冻结进报销版本）
    /** 按里程：perKmRate × 里程，受 capAmount 封顶。 */
    public static final String CALC_DISTANCE = "DISTANCE";
    /** 定额。 */
    public static final String CALC_FIXED = "FIXED";

    // 规则版本状态
    /** 草稿：可改口径，未参与试算。 */
    public static final String VERSION_DRAFT = "DRAFT";
    /** 生效：同一规则同时刻仅一版；报销按出行日期取此版。 */
    public static final String VERSION_ACTIVE = "ACTIVE";
    /** 停用：被新版顶替或人工停用；历史报销保留其版本号。 */
    public static final String VERSION_DISABLED = "DISABLED";

    // 报销状态
    /** 草稿：教师尚未提交或还在补凭证。 */
    public static final String REIMB_DRAFT = "DRAFT";
    /** 已提交：等待财务处理。 */
    public static final String REIMB_SUBMITTED = "SUBMITTED";
    /** 异常转人工：票据与试算偏离超阈，等待人工核定。 */
    public static final String REIMB_MANUAL_REVIEW = "MANUAL_REVIEW";
    /** 已确认锁定。 */
    public static final String REIMB_CONFIRMED = "CONFIRMED";
    /** 已驳回。 */
    public static final String REIMB_REJECTED = "REJECTED";

    // 版本变更类型
    public static final String CHANGE_CREATE = "CREATE";
    /** 改派：新路线重新估算版。 */
    public static final String CHANGE_REROUTE = "REROUTE";
    /** 提交票据。 */
    public static final String CHANGE_RECEIPT = "RECEIPT";
    /** 异常转人工。 */
    public static final String CHANGE_MANUAL_REVIEW = "MANUAL_REVIEW";
    /** 人工核定通过。 */
    public static final String CHANGE_MANUAL_APPROVE = "MANUAL_APPROVE";
    /** 驳回。 */
    public static final String CHANGE_REJECT = "REJECT";
    /** 财务确认。 */
    public static final String CHANGE_CONFIRM = "CONFIRM";

    // 票据状态
    public static final String RECEIPT_SUBMITTED = "SUBMITTED";
    public static final String RECEIPT_APPROVED = "APPROVED";
    public static final String RECEIPT_REJECTED = "REJECTED";

    // 票据类型
    public static final String RECEIPT_INVOICE = "INVOICE";
    public static final String RECEIPT_TICKET = "TICKET";
    public static final String RECEIPT_E_TICKET = "E_TICKET";
    public static final String RECEIPT_OTHER = "OTHER";

    /** 本人视图请求头：传监考人员编码，教师只能提交/查看本人凭证与报销；不传为财务全量视图。 */
    public static final String SELF_HEADER = "X-Self-Code";
}
