package com.evops.papersel.vo;

import com.evops.papersel.dto.MaterialSnapshot;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 单套卷面视图（预检与执行共用；预检未落库时 paperNo 为空）。
 */
@Data
public class ComposedPaperView {
    private Integer paperIndex;
    private String paperNo;
    private Long seed;
    private List<MaterialSnapshot> selected;
    private String paperFingerprint;
    private BigDecimal overlapMaxRate;
    private List<OverlapView> overlapDetail;
    private Integer retries;
}
