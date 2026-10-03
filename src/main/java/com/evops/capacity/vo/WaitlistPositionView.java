package com.evops.capacity.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 队列位置视图：把真实顺位、前面人数、窗口与确认截止钟点一次讲清，不让人猜。
 */
@Data
public class WaitlistPositionView {
    private Long waitlistId;
    private Long ruleId;
    private Long batchId;
    private String regNo;
    private String name;
    /** 入队序号（只增，永不重排）。 */
    private Integer seq;
    /** WAITING / OFFERED / SEATED / EXPIRED / CANCELLED / REJECTED */
    private String status;
    /** 当前候补顺位（候补中按 seq 的名次，从 1 起）；非候补中为 null。 */
    private Integer position;
    /** 前面还有多少候补中人员。 */
    private Integer aheadCount;
    private Integer offerRound;
    private LocalDateTime offeredAt;
    private LocalDateTime offerDeadline;
    private LocalDateTime confirmedAt;
    private String finishReason;
}
