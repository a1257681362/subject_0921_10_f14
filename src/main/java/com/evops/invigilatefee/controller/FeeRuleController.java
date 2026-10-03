package com.evops.invigilatefee.controller;

import com.evops.common.ApiResponse;
import com.evops.invigilatefee.dto.FeeRuleRequest;
import com.evops.invigilatefee.dto.FeeRuleVersionRequest;
import com.evops.invigilatefee.entity.FeeTransportRule;
import com.evops.invigilatefee.entity.FeeTransportRuleVersion;
import com.evops.invigilatefee.service.FeeRuleService;
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
 * 交通补贴规则（模块 + 版本 + 状态）：按项目建档，版本 DRAFT→ACTIVE→DISABLED；
 * 报销金额按出行日期选生效版本，发布即冻结各交通方式单价 / 封顶与异常阈值。
 */
@RestController
@RequestMapping("/api/fee-rules")
public class FeeRuleController {

    private final FeeRuleService ruleService;

    public FeeRuleController(FeeRuleService ruleService) {
        this.ruleService = ruleService;
    }

    // ---------- 规则模块 ----------

    @PostMapping
    public ApiResponse<FeeTransportRule> createRule(@Valid @RequestBody FeeRuleRequest request) {
        return ApiResponse.ok(ruleService.createRule(request));
    }

    @GetMapping
    public ApiResponse<List<FeeTransportRule>> listRules(@RequestParam(required = false) String projectCode) {
        return ApiResponse.ok(ruleService.listRules(projectCode));
    }

    @GetMapping("/{id}")
    public ApiResponse<FeeTransportRule> getRule(@PathVariable Long id) {
        return ApiResponse.ok(ruleService.getRule(id));
    }

    // ---------- 规则版本 ----------

    @PostMapping("/{ruleId}/versions")
    public ApiResponse<FeeTransportRuleVersion> createDraft(@PathVariable Long ruleId,
                                                            @Valid @RequestBody FeeRuleVersionRequest request) {
        return ApiResponse.ok(ruleService.createDraftVersion(ruleId, request));
    }

    @GetMapping("/{ruleId}/versions")
    public ApiResponse<List<FeeTransportRuleVersion>> listVersions(@PathVariable Long ruleId) {
        return ApiResponse.ok(ruleService.listVersions(ruleId));
    }

    @GetMapping("/versions/{versionId}")
    public ApiResponse<FeeTransportRuleVersion> getVersion(@PathVariable Long versionId) {
        return ApiResponse.ok(ruleService.getVersion(versionId));
    }

    /** DRAFT→ACTIVE，同规则旧生效版自动 DISABLED。 */
    @PostMapping("/versions/{versionId}/publish")
    public ApiResponse<FeeTransportRuleVersion> publish(@PathVariable Long versionId) {
        return ApiResponse.ok(ruleService.publishVersion(versionId));
    }

    /** 人工停用生效版。 */
    @PostMapping("/versions/{versionId}/disable")
    public ApiResponse<FeeTransportRuleVersion> disable(@PathVariable Long versionId) {
        return ApiResponse.ok(ruleService.disableVersion(versionId));
    }
}
