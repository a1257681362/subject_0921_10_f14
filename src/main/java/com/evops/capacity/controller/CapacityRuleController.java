package com.evops.capacity.controller;

import com.evops.capacity.dto.CapacityRuleRequest;
import com.evops.capacity.dto.CapacityRuleVersionRequest;
import com.evops.capacity.entity.CapacityRule;
import com.evops.capacity.entity.CapacityRuleVersion;
import com.evops.capacity.service.CapacityRuleService;
import com.evops.capacity.service.WaitlistService;
import com.evops.capacity.vo.CapacityStateView;
import com.evops.common.ApiResponse;
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
 * 容量规则模块（模块 + 版本 + 状态）。路径在 /api/** 下，自动走 HTTP Basic。
 */
@RestController
@RequestMapping("/api/capacity-rules")
public class CapacityRuleController {

    private final CapacityRuleService ruleService;
    private final WaitlistService waitlistService;

    public CapacityRuleController(CapacityRuleService ruleService, WaitlistService waitlistService) {
        this.ruleService = ruleService;
        this.waitlistService = waitlistService;
    }

    @PostMapping
    public ApiResponse<CapacityRule> create(@Valid @RequestBody CapacityRuleRequest request) {
        return ApiResponse.ok(ruleService.createRule(request));
    }

    @GetMapping
    public ApiResponse<List<CapacityRule>> list(@RequestParam(required = false) Long siteId,
                                                @RequestParam(required = false) Long batchId) {
        return ApiResponse.ok(ruleService.listRules(siteId, batchId));
    }

    @GetMapping("/{ruleId}")
    public ApiResponse<CapacityRule> get(@PathVariable Long ruleId) {
        return ApiResponse.ok(ruleService.getRule(ruleId));
    }

    /** 建草稿版本：窗口/时限/容量校验通过后以 JSON 冻结。 */
    @PostMapping("/{ruleId}/versions")
    public ApiResponse<CapacityRuleVersion> createVersion(
            @PathVariable Long ruleId,
            @Valid @RequestBody CapacityRuleVersionRequest request) {
        return ApiResponse.ok(ruleService.createDraftVersion(ruleId, request));
    }

    @GetMapping("/{ruleId}/versions")
    public ApiResponse<List<CapacityRuleVersion>> listVersions(@PathVariable Long ruleId) {
        return ApiResponse.ok(ruleService.listVersions(ruleId));
    }

    @GetMapping("/versions/{versionId}")
    public ApiResponse<CapacityRuleVersion> getVersion(@PathVariable Long versionId) {
        return ApiResponse.ok(ruleService.getVersion(versionId));
    }

    /** 发布：DRAFT → ACTIVE，旧生效版自动 DISABLED；状态行刷新并立即递补空位。 */
    @PostMapping("/versions/{versionId}/publish")
    public ApiResponse<CapacityRuleVersion> publish(@PathVariable Long versionId) {
        return ApiResponse.ok(ruleService.publishVersion(versionId));
    }

    /** 人工停用生效版（停用后不再入队/递补，候补排队人由超时扫描拒收并通知）。 */
    @PostMapping("/versions/{versionId}/disable")
    public ApiResponse<CapacityRuleVersion> disable(@PathVariable Long versionId) {
        return ApiResponse.ok(ruleService.disableVersion(versionId));
    }

    /** 容量状态总览：生效版本、窗口钟点与状态、容量与实时计数/余量。 */
    @GetMapping("/{ruleId}/state")
    public ApiResponse<CapacityStateView> state(@PathVariable Long ruleId) {
        return ApiResponse.ok(waitlistService.stateView(ruleId));
    }
}
