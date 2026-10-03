package com.evops.invigilatefee.controller;

import com.evops.common.ApiResponse;
import com.evops.invigilatefee.dto.FeeDistanceRequest;
import com.evops.invigilatefee.dto.FeeRegionRequest;
import com.evops.invigilatefee.entity.FeeDistance;
import com.evops.invigilatefee.entity.FeeRegion;
import com.evops.invigilatefee.service.FeeDistanceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

/**
 * 跨区交通补贴主数据：出发区域、区域→考点距离（财务 / 考务维护，金额计算的距离口径）。
 */
@RestController
@RequestMapping("/api/fee")
public class FeeMasterDataController {

    private final FeeDistanceService distanceService;

    public FeeMasterDataController(FeeDistanceService distanceService) {
        this.distanceService = distanceService;
    }

    // ---------- 出发区域 ----------

    @PostMapping("/regions")
    public ApiResponse<FeeRegion> createRegion(@Valid @RequestBody FeeRegionRequest request) {
        return ApiResponse.ok(distanceService.createRegion(request));
    }

    @GetMapping("/regions")
    public ApiResponse<List<FeeRegion>> listRegions(@RequestParam(required = false) String status) {
        return ApiResponse.ok(distanceService.listRegions(status));
    }

    // ---------- 区域→考点距离 ----------

    @PostMapping("/distances")
    public ApiResponse<FeeDistance> createDistance(@Valid @RequestBody FeeDistanceRequest request) {
        return ApiResponse.ok(distanceService.createDistance(request));
    }

    @GetMapping("/distances")
    public ApiResponse<List<FeeDistance>> listDistances(@RequestParam(required = false) String regionCode,
                                                        @RequestParam(required = false) Long siteId) {
        return ApiResponse.ok(distanceService.listDistances(regionCode, siteId));
    }
}
