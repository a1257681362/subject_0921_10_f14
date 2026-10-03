package com.evops.papersel.controller;

import com.evops.common.ApiResponse;
import com.evops.papersel.dto.ComposeRequest;
import com.evops.papersel.entity.PaperSelPaper;
import com.evops.papersel.entity.PaperSelRun;
import com.evops.papersel.service.ComposeService;
import com.evops.papersel.vo.ComposeView;
import com.evops.papersel.vo.FairnessVerifyView;
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
 * 多场并行组卷：预检 → 执行；卷面、种子、指纹留档，事后可复算验公平。
 */
@RestController
@RequestMapping("/api/paper-compose")
public class ComposeController {

    private final ComposeService composeService;

    public ComposeController(ComposeService composeService) {
        this.composeService = composeService;
    }

    /** 第一步：预检配比、池子余量、各套重合率与重抽情况，不落库。 */
    @PostMapping("/preview")
    public ApiResponse<ComposeView> preview(@Valid @RequestBody ComposeRequest request) {
        return ApiResponse.ok(composeService.preview(request));
    }

    /** 第二步：执行组卷，运行记录与各套卷面同事务落库（池子不足整批拒收不落库）。 */
    @PostMapping
    public ApiResponse<ComposeView> compose(@Valid @RequestBody ComposeRequest request) {
        return ApiResponse.ok(composeService.compose(request));
    }

    /** 事后公平核验：按运行记录冻结的参数/种子/池子快照复算，逐套比种子、清单与卷面指纹。 */
    @GetMapping("/runs/{runId}/verify")
    public ApiResponse<FairnessVerifyView> verify(@PathVariable Long runId) {
        return ApiResponse.ok(composeService.verifyFairness(runId));
    }

    /** 历次组卷运行（列表不带快照大字段）。 */
    @GetMapping("/runs")
    public ApiResponse<List<PaperSelRun>> listRuns(
            @RequestParam(required = false) String projectCode,
            @RequestParam(required = false) String groupCode) {
        return ApiResponse.ok(composeService.listRuns(projectCode, groupCode));
    }

    /** 单次组卷运行详情（含配比、参数快照、池子快照与指纹）。 */
    @GetMapping("/runs/{runId}")
    public ApiResponse<PaperSelRun> getRun(@PathVariable Long runId) {
        return ApiResponse.ok(composeService.getRun(runId));
    }

    /** 一次运行的各套卷面（含中选清单快照、卷面指纹、重合明细）。 */
    @GetMapping("/runs/{runId}/papers")
    public ApiResponse<List<PaperSelPaper>> listPapers(@PathVariable Long runId) {
        return ApiResponse.ok(composeService.listPapers(runId));
    }

    @GetMapping("/papers/{paperId}")
    public ApiResponse<PaperSelPaper> getPaper(@PathVariable Long paperId) {
        return ApiResponse.ok(composeService.getPaper(paperId));
    }
}
