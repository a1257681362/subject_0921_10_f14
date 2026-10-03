package com.evops.papersel.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 组卷运行：一次多场并行一条。
 * 存根种子、算法版本与参数快照、配比、池子快照与池子指纹、各套重抽概况；只追加，卷面只读。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_paper_sel_run")
public class PaperSelRun extends BaseEntity {
    private String runNo;
    /** 一次多场并行的组号 */
    private String groupCode;
    private String projectCode;
    private Integer paperCount;
    private Integer paperSize;
    /** 知识点+难度档配比 JSON */
    private String quotas;
    private Long selVersionId;
    private String selVersionNo;
    /** 算法参数快照 JSON（复算不依赖版本表现值） */
    private String paramsSnapshot;
    private Long rootSeed;
    /** 在池素材冻结快照 JSON */
    private String poolSnapshot;
    private String poolFingerprint;
    private BigDecimal maxOverlapRate;
    /** 各套重抽次数 JSON */
    private String retrySummary;
    private String warnings;
    /** COMPOSED 已组卷（卷面留档只读） */
    private String status;
    private String operator;
    private LocalDateTime executedAt;
}
