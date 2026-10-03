package com.evops.handbook.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 监考手册模块主表：一个考试批次一本手册。
 * 当前版本号与状态只切标记；所有版本只追加，过期自动归档。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_invigilation_handbook")
public class InvigilationHandbook extends BaseEntity {
    private String handbookNo;
    private Long batchId;
    /** 批次/考点快照（冗余自批次建档，批次编码即发布归属口径）。 */
    private String batchCode;
    private String batchName;
    private Long siteId;
    private String siteCode;
    private String siteName;
    private String projectCode;
    private Long currentVersionId;
    private Integer currentVersionNo;
    /** PENDING_CONFIRM / RELEASED / ARCHIVED */
    private String status;
    /** 过期时刻：为空不自动过期。 */
    private LocalDateTime expireAt;
    private String archivedBy;
    private LocalDateTime archivedAt;
    private String archiveReason;
    private String operator;
    private String remark;
}
