package com.evops.capacity.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 候补队列条目：一人一规则一条（uk(rule_id, reg_no)）。
 * 顺序只认入队序号 seq；重复入队返回原记录原序号，不能换队重排/插队。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_capacity_waitlist")
public class CapacityWaitlist extends BaseEntity {
    private Long ruleId;
    private Long siteId;
    private Long batchId;
    private String regNo;
    private String name;
    private String phone;
    /** 入队序号（只增）；候补中按 seq 排序即真实顺位。 */
    private Integer seq;
    /** WAITING / OFFERED / SEATED / EXPIRED / CANCELLED / REJECTED */
    private String status;
    /** 本轮递补通知钟点（服务器时间）。 */
    private LocalDateTime offeredAt;
    /** 本轮递补确认截止钟点（服务器时间）。 */
    private LocalDateTime offerDeadline;
    /** 递补轮次：每顺延一次 +1。 */
    private Integer offerRound;
    private LocalDateTime confirmedAt;
    /** 锁座后回写的报名名册记录。 */
    private Long registrationId;
    private LocalDateTime finishedAt;
    private String finishReason;
}
