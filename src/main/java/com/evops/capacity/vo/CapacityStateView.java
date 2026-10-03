package com.evops.capacity.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 容量状态总览：生效配置、窗口钟点、实时计数一页讲清。
 */
@Data
public class CapacityStateView {
    private Long ruleId;
    private String ruleCode;
    private String ruleName;
    private Long siteId;
    private Long batchId;
    private String batchCode;
    private Long currentVersionId;
    private String currentVersionNo;
    private Integer versionGeneration;
    /** 是否存在生效版本（无生效版不能入队/递补）。 */
    private boolean active;
    private LocalDateTime windowStart;
    private LocalDateTime windowEnd;
    /** NOT_CONFIGURED / NOT_OPENED / OPEN / CLOSED（按服务器钟点计算）。 */
    private String windowStatus;
    private Integer offerTimeoutMinutes;
    private Integer capacity;
    private Integer occupiedCount;
    private Integer offeredCount;
    private Integer waitingCount;
    private Integer seatedCount;
    private Integer availableCount;
    private LocalDateTime now;
}
