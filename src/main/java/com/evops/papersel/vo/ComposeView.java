package com.evops.papersel.vo;

import lombok.Data;

import java.util.List;

/**
 * 组卷方案视图（预检不落库 / 执行落库后返回同一结构）。
 */
@Data
public class ComposeView {
    private Long runId;
    private String runNo;
    private String groupCode;
    private String projectCode;
    private Integer paperCount;
    private Integer paperSize;
    private String selVersionNo;
    private Long rootSeed;
    private String poolFingerprint;
    private Integer poolSize;
    /** 提示（勉强过阈、余量紧张等）；池子不足直接拒收不会返回本视图。 */
    private List<String> warnings;
    private List<ComposedPaperView> papers;
}
