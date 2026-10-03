package com.evops.capacity.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 候补通知：入队/递补/锁座/超时顺延/退出/拒收都通知。
 * 递补通知必带确认截止钟点（服务器时间）。本版先落库，短信等外发渠道下版再接。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_capacity_notification")
public class CapacityNotification extends BaseEntity {
    private Long ruleId;
    private Long waitlistId;
    private Long batchId;
    private String regNo;
    private String name;
    /** QUEUED / OFFER / SEATED / EXPIRED / CANCELLED / REJECTED */
    private String notifyType;
    private String title;
    private String content;
    /** 递补通知携带的确认截止钟点（服务器时间），其余类型为空。 */
    private LocalDateTime offerDeadline;
    /** NEW 未读 / READ 已读 */
    private String status;
    private String operator;
    private LocalDateTime notifiedAt;
    private LocalDateTime readAt;
}
