package com.evops.capacity.common;

/**
 * 热门考点容量控制与候补递补业务常量。
 */
public final class CapacityConstants {

    private CapacityConstants() {
    }

    // 容量规则版本状态
    /** 草稿：参数可改，未参与候补。 */
    public static final String VERSION_DRAFT = "DRAFT";
    /** 生效：同一规则同时刻仅一版生效，候补只认生效版。 */
    public static final String VERSION_ACTIVE = "ACTIVE";
    /** 停用：被新版本发布顶替或人工停用；历史候补记录仍保留其版本号。 */
    public static final String VERSION_DISABLED = "DISABLED";

    // 候补状态
    /** 候补中：按入队序号排队，位置透明。 */
    public static final String WAIT_WAITING = "WAITING";
    /** 已递补待确认：通知已带确认时限发出，期间占座。 */
    public static final String WAIT_OFFERED = "OFFERED";
    /** 已确认：递补成功锁定座位并回写报名名册。 */
    public static final String WAIT_SEATED = "SEATED";
    /** 超时未确认：顺位作废，名额顺给下一位。 */
    public static final String WAIT_EXPIRED = "EXPIRED";
    /** 主动退出候补 / 锁座后退位。 */
    public static final String WAIT_CANCELLED = "CANCELLED";
    /** 拒收（规则停用等）。 */
    public static final String WAIT_REJECTED = "REJECTED";

    // 流转事件类型
    public static final String EVENT_JOIN = "JOIN";
    public static final String EVENT_OFFER = "OFFER";
    public static final String EVENT_CONFIRM = "CONFIRM";
    public static final String EVENT_EXPIRE = "EXPIRE";
    public static final String EVENT_CANCEL = "CANCEL";
    public static final String EVENT_REJECT = "REJECT";

    // 通知类型
    public static final String NOTIFY_QUEUED = "QUEUED";
    public static final String NOTIFY_OFFER = "OFFER";
    public static final String NOTIFY_SEATED = "SEATED";
    public static final String NOTIFY_EXPIRED = "EXPIRED";
    public static final String NOTIFY_CANCELLED = "CANCELLED";
    public static final String NOTIFY_REJECTED = "REJECTED";
    public static final String NOTIFY_NEW = "NEW";
    public static final String NOTIFY_READ = "READ";

    // 候补窗口状态（计算值，不入库）
    public static final String WINDOW_NOT_CONFIGURED = "NOT_CONFIGURED";
    public static final String WINDOW_NOT_OPENED = "NOT_OPENED";
    public static final String WINDOW_OPEN = "OPEN";
    public static final String WINDOW_CLOSED = "CLOSED";

    /** 递补确认时限上限（分钟）：防止误配出超长占座。 */
    public static final int MAX_OFFER_TIMEOUT_MINUTES = 7 * 24 * 60;
}
