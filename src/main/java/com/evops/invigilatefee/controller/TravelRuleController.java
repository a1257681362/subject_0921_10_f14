package com.evops.invigilatefee.controller;

import com.evops.common.ApiResponse;
import com.evops.invigilatefee.dto.TravelRuleRequest;
import com.evops.invigilatefee.dto.TravelRuleVersionRequest;
import com.evops.invigilatefee.entity.TravelAllowanceRule;
import com.evops.invigilatefee.entity.TravelAllowanceRuleVersion;
import com.evops.invigilatefee.service.TravelRuleService;
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
 * 交通补贴规则（模块 + 版本 + 状态）：按项目建档；版本发布即冻结计费口径，按出行日期闭区间选取，同区间不重叠。
 */
@RestController
@RequestMapping("/api/travel-allowance-rules")
public class TravelRuleController {

    private final TravelRuleService ruleService;

    public TravelRuleController(TravelRuleService ruleService) {
        this.ruleService = ruleService;
    }

    @PostMapping
    public ApiResponse<TravelAllowanceRule> createRule(@Valid @RequestBody TravelRuleRequest request) {
        return ApiResponse.ok(ruleService.createRule(request));
    }

    @GetMapping
    public ApiResponse<List<TravelAllowanceRule>> listRules(
            @RequestParam(required = false) String projectCode) {
        return ApiResponse.ok(ruleService.listRules(projectCode));
    }

    @GetMapping("/{id}")
    public ApiResponse<TravelAllowanceRule> getRule(@PathVariable Long id) {
        return ApiResponse.ok(ruleService.getRule(id));
    }

    /** 新建草稿版本（校验各交通方式口径与偏离阈值，JSON 冻结落库）。 */
    @PostMapping("/{id}/versions")
    public ApiResponse<TravelAllowanceRuleVersion> createDraftVersion(
            @PathVariable Long id, @Valid @RequestBody TravelRuleVersionRequest request) {
        return ApiResponse.ok(ruleService.createDraftVersion(id, request));
    }

    @GetMapping("/{id}/versions")
    public ApiResponse<List<TravelAllowanceRuleVersion>> listVersions(@PathVariable Long id) {
        return ApiResponse.ok(ruleService.listVersions(id));
    }

    /** 发布：DRAFT → ACTIVE，生效区间与在版版本重叠则拒绝。 */
    @PostMapping("/versions/{versionId}/publish")
    public ApiResponse<TravelAllowanceRuleVersion> publish(@PathVariable Long versionId) {
        return ApiResponse.ok(ruleService.publishVersion(versionId));
    }

    /** 停用：不删版本，历史报销保留版本号。 */
    @PostMapping("/versions/{versionId}/disable")
    public ApiResponse<TravelAllowanceRuleVersion> disable(@PathVariable Long versionId) {
        return ApiResponse.ok(ruleService.disableVersion(versionId));
    }
}
