package com.evops.handbook.schedule;

import com.evops.handbook.service.HandbookService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 过期手册自动归档兜底：每分钟扫描一次（服务器钟点）。
 * expire_at 已过且未归档的手册置 ARCHIVED 并通知考点。
 * 人工也可 POST /api/handbooks/{id}/archive 立即归档。
 */
@Component
public class HandbookArchiveScheduler {

    private static final Logger log = LoggerFactory.getLogger(HandbookArchiveScheduler.class);

    private final HandbookService handbookService;

    public HandbookArchiveScheduler(HandbookService handbookService) {
        this.handbookService = handbookService;
    }

    @Scheduled(cron = "${evops.handbook.archive-cron:0 * * * * *}")
    public void sweepExpired() {
        int archived = handbookService.sweepExpired();
        if (archived > 0) {
            log.info("监考手册过期归档扫描：自动归档 {} 本", archived);
        }
    }
}
