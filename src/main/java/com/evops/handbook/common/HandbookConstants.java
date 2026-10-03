package com.evops.handbook.common;

/**
 * 监考手册业务常量：模块（手册主表）/ 版本（只追加）/ 状态（当前版本）三段。
 */
public final class HandbookConstants {

    private HandbookConstants() {
    }

    /** 考点范围请求头：传入考点编码后，查看 / 下载仅限本考点手册。 */
    public static final String SITE_SCOPE_HEADER = "X-Site-Code";

    // 手册主表状态
    /** 待确认：内容已写入，等待负责人放行。 */
    public static final String HANDBOOK_PENDING = "PENDING_CONFIRM";
    /** 已放行：负责人已放行，打印版本已生成。 */
    public static final String HANDBOOK_RELEASED = "RELEASED";
    /** 已归档：过期自动归档或人工归档，不再提供下载。 */
    public static final String HANDBOOK_ARCHIVED = "ARCHIVED";

    // 版本状态
    public static final String VERSION_PENDING = "PENDING_CONFIRM";
    public static final String VERSION_RELEASED = "RELEASED";
    /** 被后续版本顶替（未放行即调整，或换版后旧版）。 */
    public static final String VERSION_SUPERSEDED = "SUPERSEDED";
    public static final String VERSION_ARCHIVED = "ARCHIVED";

    // 版本变更类型
    /** 首版写入。 */
    public static final String CHANGE_CREATE = "CREATE";
    /** 后续调整另建版本。 */
    public static final String CHANGE_ADJUST = "ADJUST";

    // 发布批次类型
    /** 首版放行。 */
    public static final String RELEASE_INITIAL = "INITIAL";
    /** 调整版放行（新开发布批次）。 */
    public static final String RELEASE_ADJUST = "ADJUST";
    /** 补发：沿用原发布批次号、生效时刻与经办人。 */
    public static final String RELEASE_REISSUE = "REISSUE";

    // 考点通知类型
    public static final String NOTICE_RELEASE = "RELEASE";
    public static final String NOTICE_ADJUST = "ADJUST";
    public static final String NOTICE_REISSUE = "REISSUE";
    public static final String NOTICE_ARCHIVE = "ARCHIVE";

    public static final String NOTICE_NEW = "NEW";
    public static final String NOTICE_READ = "READ";

    /** 自动归档调度器经办人口径。 */
    public static final String SYSTEM_OPERATOR = "system";
}
