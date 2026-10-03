package com.evops.papersel.common;

/**
 * 教研组卷抽选业务常量。
 */
public final class PaperSelConstants {

    private PaperSelConstants() {
    }

    /** 默认项目编码（素材/算法未指定项目时）。 */
    public static final String PROJECT_DEFAULT = "DEFAULT";

    // 素材状态
    /** 在池可抽。 */
    public static final String MATERIAL_ENABLED = "ENABLED";
    /** 已下架：不参与抽选，但不物理删除，历史卷面凭内容哈希仍可追溯。 */
    public static final String MATERIAL_DISABLED = "DISABLED";

    // 素材变更类型
    public static final String CHANGE_CREATE = "CREATE";
    public static final String CHANGE_UPDATE = "UPDATE";
    public static final String CHANGE_DISABLE = "DISABLE";
    public static final String CHANGE_ENABLE = "ENABLE";

    // 抽选算法版本状态（与混编/等级规则同一套三段式口径）
    /** 草稿：参数可改，未参与组卷。 */
    public static final String VERSION_DRAFT = "DRAFT";
    /** 生效：同一算法同时刻仅一版生效，组卷只认生效版。 */
    public static final String VERSION_ACTIVE = "ACTIVE";
    /** 停用：被新版本发布顶替或人工停用；历史卷面保留版本号与参数快照。 */
    public static final String VERSION_DISABLED = "DISABLED";

    // 组卷策略
    /** 按知识点+难度档配比，逐档从素材池随机抽。 */
    public static final String STRATEGY_KP_DIFFICULTY_QUOTA = "KP_DIFFICULTY_QUOTA";

    // 内置默认抽选参数（项目尚无生效算法版本时的兜底口径）
    public static final double DEFAULT_MAX_OVERLAP_RATE = 0.20d;
    public static final int DEFAULT_MAX_RETRIES = 50;
    /** 重合率比对范围：GROUP 仅与本次并行各套逐一比对（当前仅支持该口径）。 */
    public static final String COMPARE_SCOPE_GROUP = "GROUP";

    public static final double MIN_OVERLAP_RATE = 0.0d;
    public static final double MAX_OVERLAP_RATE_LIMIT = 1.0d;
    public static final int MIN_RETRIES = 0;
    public static final int MAX_RETRIES_LIMIT = 1000;
}
