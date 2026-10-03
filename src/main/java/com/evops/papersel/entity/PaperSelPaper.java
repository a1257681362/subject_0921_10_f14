package com.evops.papersel.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 组卷卷面：一套一条。
 * 存衍生种子、中选素材快照、卷面指纹、与各套重合率与重抽次数；只追加只读。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_paper_sel_paper")
public class PaperSelPaper extends BaseEntity {
    private Long runId;
    private Integer paperIndex;
    private String paperNo;
    private Long seed;
    /** 中选素材快照 JSON */
    private String selected;
    private String paperFingerprint;
    private BigDecimal overlapMaxRate;
    /** 与各套重合明细 JSON */
    private String overlapDetail;
    private Integer retries;
    private LocalDateTime executedAt;
}
