package com.evops.papersel.controller;

import com.evops.common.ApiResponse;
import com.evops.papersel.dto.PaperSelAlgorithmRequest;
import com.evops.papersel.dto.PaperSelAlgorithmVersionRequest;
import com.evops.papersel.entity.PaperSelAlgorithm;
import com.evops.papersel.entity.PaperSelAlgorithmVersion;
import com.evops.papersel.service.PaperSelAlgorithmService;
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
 * 抽选算法模块（算法 + 版本 + 状态）。
 */
@RestController
@RequestMapping("/api/paper-sel-algorithms")
public class PaperSelAlgorithmController {

    private final PaperSelAlgorithmService algorithmService;

    public PaperSelAlgorithmController(PaperSelAlgorithmService algorithmService) {
        this.algorithmService = algorithmService;
    }

    @PostMapping
    public ApiResponse<PaperSelAlgorithm> create(@Valid @RequestBody PaperSelAlgorithmRequest request) {
        return ApiResponse.ok(algorithmService.createAlgorithm(request));
    }

    @GetMapping
    public ApiResponse<List<PaperSelAlgorithm>> list(@RequestParam(required = false) String projectCode) {
        return ApiResponse.ok(algorithmService.listAlgorithms(projectCode));
    }

    @GetMapping("/{algorithmId}")
    public ApiResponse<PaperSelAlgorithm> get(@PathVariable Long algorithmId) {
        return ApiResponse.ok(algorithmService.getAlgorithm(algorithmId));
    }

    /** 建草稿版本：参数校验通过后以 JSON 冻结。 */
    @PostMapping("/{algorithmId}/versions")
    public ApiResponse<PaperSelAlgorithmVersion> createVersion(
            @PathVariable Long algorithmId,
            @Valid @RequestBody PaperSelAlgorithmVersionRequest request) {
        return ApiResponse.ok(algorithmService.createDraftVersion(algorithmId, request));
    }

    @GetMapping("/{algorithmId}/versions")
    public ApiResponse<List<PaperSelAlgorithmVersion>> listVersions(@PathVariable Long algorithmId) {
        return ApiResponse.ok(algorithmService.listVersions(algorithmId));
    }

    @GetMapping("/versions/{versionId}")
    public ApiResponse<PaperSelAlgorithmVersion> getVersion(@PathVariable Long versionId) {
        return ApiResponse.ok(algorithmService.getVersion(versionId));
    }

    /** 发布：DRAFT → ACTIVE，旧生效版自动置 DISABLED。 */
    @PostMapping("/versions/{versionId}/publish")
    public ApiResponse<PaperSelAlgorithmVersion> publish(@PathVariable Long versionId) {
        return ApiResponse.ok(algorithmService.publishVersion(versionId));
    }

    /** 人工停用生效版。 */
    @PostMapping("/versions/{versionId}/disable")
    public ApiResponse<PaperSelAlgorithmVersion> disable(@PathVariable Long versionId) {
        return ApiResponse.ok(algorithmService.disableVersion(versionId));
    }
}
