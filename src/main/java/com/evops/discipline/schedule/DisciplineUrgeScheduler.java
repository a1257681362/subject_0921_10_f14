package com.evops.discipline.schedule;

import com.evops.discipline.service.DisciplineUrgeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 违纪处理期限兜底：每分钟扫描一次（服务器钟点）。
 * 超过处理决定期限仍未出决定的案件自动催办承办人，并按间隔升级；
 * 人工也可 POST /api/discipline/urge/sweep 立即执行。
 */
@Component
public class DisciplineUrgeScheduler {

    private static final Logger log = LoggerFactory.getLogger(DisciplineUrgeScheduler.class);

    private final DisciplineUrgeService urgeService;

    public DisciplineUrgeScheduler(DisciplineUrgeService urgeService) {
        this.urgeService = urgeService;
    }

    @Scheduled(cron = "${evops.discipline.urge-cron:0 * * * * *}")
    public void sweepOverdue() {
        Map<String, Object> result = urgeService.sweep();
        int urgedCases = (Integer) result.get("urgedCases");
        if (urgedCases > 0) {
            log.info("违纪处理期限扫描：超时案件 {} 件，本轮催办 {} 件、新增催办 {} 轮",
                    result.get("overdueCount"), urgedCases, result.get("urgeRounds"));
        }
    }
}
