package com.evops.invigilatefee.controller;

import com.evops.common.ApiResponse;
import com.evops.invigilatefee.dto.SiteDistanceRequest;
import com.evops.invigilatefee.entity.SiteDistance;
import com.evops.invigilatefee.service.SiteDistanceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

/**
 * 考点路程主数据：出发地 → 考点单程公里数，按里程试算交通补贴的唯一口径来源。
 */
@RestController
@RequestMapping("/api/site-distances")
public class SiteDistanceController {

    private final SiteDistanceService distanceService;

    public SiteDistanceController(SiteDistanceService distanceService) {
        this.distanceService = distanceService;
    }

    @PostMapping
    public ApiResponse<SiteDistance> create(@Valid @RequestBody SiteDistanceRequest request) {
        return ApiResponse.ok(distanceService.create(request));
    }

    @GetMapping
    public ApiResponse<List<SiteDistance>> list(@RequestParam(required = false) Long siteId,
                                                @RequestParam(required = false) String originCode) {
        return ApiResponse.ok(distanceService.list(siteId, originCode));
    }

    /** 停用后该起讫不再参与试算（历史报销已冻结里程，不受影响）。 */
    @PostMapping("/{id}/disable")
    public ApiResponse<SiteDistance> disable(@PathVariable Long id) {
        return ApiResponse.ok(distanceService.disable(id));
    }
}
