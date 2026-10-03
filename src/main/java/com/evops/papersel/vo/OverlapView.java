package com.evops.papersel.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 套间重合情况（供教研会复核不雷同口径）。
 */
@Data
public class OverlapView {
    private Integer paperIndex;
    private Integer shared;
    private BigDecimal rate;
}
