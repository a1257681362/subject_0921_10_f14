package com.evops.handbook.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 手册考点通知：放行 / 调整放行 / 补发 / 归档各一条，只追加。
 * 本版站内落库，短信/邮件等外发渠道下版再接。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_handbook_notice")
public class HandbookNotice extends BaseEntity {
    private Long handbookId;
    private Long releaseId;
    private Long versionId;
    private Long siteId;
    private String siteCode;
    private String siteName;
    /** RELEASE / ADJUST / REISSUE / ARCHIVE */
    private String noticeType;
    private String title;
    private String content;
    /** NEW / READ */
    private String status;
    private String operator;
    private LocalDateTime noticedAt;
    private LocalDateTime readAt;
}
