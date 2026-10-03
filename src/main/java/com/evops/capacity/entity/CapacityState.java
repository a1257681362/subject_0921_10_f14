package com.evops.capacity.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 容量状态：每规则一行当前值。生效配置快照 + 实时计数；
 * 入队/递补/确认/超时全部在该行 FOR UPDATE 行锁内串行，杜绝超卖与重复递补。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_capacity_state")
public class CapacityState extends BaseEntity {
    private Long ruleId;
    private Long siteId;
    private Long batchId;
    private Long currentVersionId;
    private String currentVersionNo;
    /** 配置代次：每次发布/停用 +1。 */
    private Integer versionGeneration;
    private LocalDateTime windowStart;
    private LocalDateTime windowEnd;
    private Integer offerTimeoutMinutes;
    private Integer capacity;
    /** 占座 = 批次内在册报名 + 待确认递补。 */
    private Integer occupiedCount;
    /** 候补中人数。 */
    private Integer waitingCount;
    /** 已递补成功锁座累计。 */
    private Integer seatedCount;
    private String updatedBy;
    private LocalDateTime updatedAt;
}
