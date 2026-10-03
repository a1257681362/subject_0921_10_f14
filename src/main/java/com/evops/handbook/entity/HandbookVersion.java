package com.evops.handbook.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 监考手册版本：写入即待确认；负责人放行时冻结内容并生成打印版。
 * PENDING_CONFIRM → RELEASED；调整另建新版，未放行旧待确认版置 SUPERSEDED；归档随主表置 ARCHIVED。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_handbook_version")
public class HandbookVersion extends BaseEntity {
    private Long handbookId;
    private Integer versionNo;
    /** CREATE / ADJUST */
    private String changeType;
    /** PENDING_CONFIRM / RELEASED / SUPERSEDED / ARCHIVED */
    private String status;
    /** 岗位说明 */
    private String postDescription;
    /** 签到地点 */
    private String signInLocation;
    /** 应急联系人 */
    private String emergencyContact;
    private String emergencyPhone;
    /** 写入经办人 / 写入钟点 */
    private String submittedBy;
    private LocalDateTime submittedAt;
    /** 负责人放行（生成打印版） */
    private String releasedBy;
    private LocalDateTime releasedAt;
    /** 生效时刻：放行时确定，补发沿用不改写。 */
    private LocalDateTime effectiveAt;
    /** 放行时生成并冻结的打印版本（HTML）。 */
    private String printHtml;
    private LocalDateTime printGeneratedAt;
    /** 调整原因（ADJUST 版）。 */
    private String adjustReason;
    private LocalDateTime archivedAt;
}
