package com.evops.capacity.schedule;

import com.evops.capacity.service.WaitlistService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 候补超时兜底：每分钟扫描一次（服务器钟点）。
 * 超确认时限未确认者置 EXPIRED 并通知本人，名额在同一事务顺给下一位并发出递补通知；
 * 候补窗口已关闭（或规则停用）时拒收仍在排队者。人工也可 POST /api/capacity/waitlist/sweep-expired 立即执行。
 */
@Component
public class WaitlistScheduler {

    private static final Logger log = LoggerFactory.getLogger(WaitlistScheduler.class);

    private final WaitlistService waitlistService;

    public WaitlistScheduler(WaitlistService waitlistService) {
        this.waitlistService = waitlistService;
    }

    @Scheduled(cron = "${evops.capacity.sweep-cron:0 * * * * *}")
    public void sweepExpired() {
        Map<String, Object> result = waitlistService.sweepExpired();
        int expired = (Integer) result.get("expiredCount");
        int offered = (Integer) result.get("offeredCount");
        int rejected = (Integer) result.get("rejectedCount");
        if (expired > 0 || offered > 0 || rejected > 0) {
            log.info("候补超时兜底扫描：超时顺延 {} 人、新递补 {} 人、窗口终止拒收 {} 人，规则 {}",
                    expired, offered, rejected, result.get("ruleIds"));
        }
    }
}
