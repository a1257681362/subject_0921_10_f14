package com.evops.capacity.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 候补流转事件：入队/递补/确认/超时/退出/拒收全程留痕，只追加。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_capacity_waitlist_event")
public class CapacityWaitlistEvent extends BaseEntity {
    private Long waitlistId;
    private Long ruleId;
    private Integer seq;
    /** JOIN / OFFER / CONFIRM / EXPIRE / CANCEL / REJECT */
    private String changeType;
    private String statusFrom;
    private String statusTo;
    private String detail;
    private String operator;
    private LocalDateTime eventAt;
}
