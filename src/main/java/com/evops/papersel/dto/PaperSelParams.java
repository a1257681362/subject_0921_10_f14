package com.evops.papersel.dto;

import lombok.Data;

/**
 * 抽选算法参数（版本发布时冻结为 JSON）。
 * - strategy：抽选策略，当前仅支持 KP_DIFFICULTY_QUOTA（按知识点+难度档配比）；
 * - maxOverlapRate：套与套之间允许的最大重合率（重合素材数 / 单套题量），超阈自动换种子重抽；
 * - maxRetries：单套自动重抽次数上限（0 表示不重抽，首轮不过即失败）；
 * - compareScope：重合率比对范围，GROUP=仅与本次并行各套比对。
 */
@Data
public class PaperSelParams {
    private String strategy;
    private Double maxOverlapRate;
    private Integer maxRetries;
    private String compareScope;
}
