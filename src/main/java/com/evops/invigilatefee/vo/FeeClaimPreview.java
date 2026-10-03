package com.evops.invigilatefee.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 报销提交预检结果（两步动作第一步）：不落库，回显命中距离 / 规则版本 / 金额与异常判定。
 * submit 执行时复用同一解析与估算口径，保证预检所见即所得。
 */
@Data
public class FeeClaimPreview {
    private Long batchId;
    private String batchCode;
    private String projectCode;
    private Long siteId;
    private String siteCode;
    private String siteName;

    private String invigilatorCode;
    private String invigilatorName;

    private Long regionId;
    private String regionCode;
    private String regionName;
    private LocalDate travelDate;

    private int receiptCount;
    private BigDecimal receiptTotal;

    private FeeEstimate estimate;

    /** 提交后将进入的状态：SUBMITTED 待财务审核 / MANUAL_REVIEW 异常转人工。 */
    private String nextStatus;
    /** 是否可直接提交（结构性校验通过即可提交，异常不阻断提交而是转人工）。 */
    private boolean actionable;
    /** 汇总说明。 */
    private String summary;
    private List<String> notes = new ArrayList<>();
}
