package com.evops.invigilatefee.controller;

import com.evops.common.ApiResponse;
import com.evops.invigilatefee.dto.FeeAdjustRequest;
import com.evops.invigilatefee.dto.FeeClaimSubmitRequest;
import com.evops.invigilatefee.dto.FeeManualReviewRequest;
import com.evops.invigilatefee.dto.FeeReassignRequest;
import com.evops.invigilatefee.dto.FeeRejectRequest;
import com.evops.invigilatefee.entity.FeeClaim;
import com.evops.invigilatefee.entity.FeeClaimAdjustment;
import com.evops.invigilatefee.entity.FeeClaimVersion;
import com.evops.invigilatefee.entity.FeeReceipt;
import com.evops.invigilatefee.service.FeeClaimService;
import com.evops.invigilatefee.vo.FeeClaimPreview;
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
 * 跨区监考交通补贴报销：
 * 教师（X-Self-Code）只能提交本人凭证、查本人；财务（不带本人头）看全量、确认 / 人工审核 / 改派 / 调整。
 * 金额 = 里程单价×距离（每程封顶）与审批票据取小；规则版本按出行日期选择；异常金额自动转人工；
 * 确认只读，修订走调整单；改派原单冻结、新路线重新估算。
 */
@RestController
@RequestMapping("/api/fee-claims")
public class FeeClaimController {

    private final FeeClaimService claimService;

    public FeeClaimController(FeeClaimService claimService) {
        this.claimService = claimService;
    }

    // ---------- 提交（两步预检） ----------

    /** 第一步：预检估算（命中距离 / 规则版本 / 应付 / 异常判定），不落库。 */
    @PostMapping("/preview")
    public ApiResponse<FeeClaimPreview> preview(@Valid @RequestBody FeeClaimSubmitRequest request) {
        return ApiResponse.ok(claimService.preview(request));
    }

    /** 第二步：提交本人报销凭证（金额正常待财务审核，异常自动转人工）。 */
    @PostMapping
    public ApiResponse<FeeClaim> submit(@Valid @RequestBody FeeClaimSubmitRequest request) {
        return ApiResponse.ok(claimService.submit(request));
    }

    /** 改派新单（DRAFT）补票并提交（只需 receipts / remark，其余沿用新单冻结口径）。 */
    @PostMapping("/{id}/submit-receipts")
    public ApiResponse<FeeClaim> submitReceipts(@PathVariable Long id,
                                                @RequestBody FeeClaimSubmitRequest request) {
        return ApiResponse.ok(claimService.submitReceipts(id, request));
    }

    // ---------- 查询：财务全量 / 教师本人 ----------

    /** 列表：财务全量；带 X-Self-Code 强制只看本人。 */
    @GetMapping
    public ApiResponse<List<FeeClaim>> list(@RequestParam(required = false) Long batchId,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(required = false) String invigilatorCode) {
        return ApiResponse.ok(claimService.list(batchId, status, invigilatorCode));
    }

    @GetMapping("/{id}")
    public ApiResponse<FeeClaim> get(@PathVariable Long id) {
        return ApiResponse.ok(claimService.get(id));
    }

    @GetMapping("/{id}/versions")
    public ApiResponse<List<FeeClaimVersion>> versions(@PathVariable Long id) {
        return ApiResponse.ok(claimService.versions(id));
    }

    @GetMapping("/{id}/receipts")
    public ApiResponse<List<FeeReceipt>> receipts(@PathVariable Long id) {
        return ApiResponse.ok(claimService.receipts(id));
    }

    // ---------- 财务确认 / 驳回 / 人工审核 ----------

    /** 财务确认金额正常的报销（SUBMITTED→CONFIRMED，应付锁定只读）。 */
    @PostMapping("/{id}/confirm")
    public ApiResponse<FeeClaim> confirm(@PathVariable Long id) {
        return ApiResponse.ok(claimService.confirm(id));
    }

    /** 财务 / 人工驳回（须填原因）。 */
    @PostMapping("/{id}/reject")
    public ApiResponse<FeeClaim> reject(@PathVariable Long id, @Valid @RequestBody FeeRejectRequest request) {
        return ApiResponse.ok(claimService.reject(id, request));
    }

    /** 异常单人工审核：放行即确认，驳回须填意见（仅 MANUAL_REVIEW）。 */
    @PostMapping("/{id}/manual-review")
    public ApiResponse<FeeClaim> manualReview(@PathVariable Long id,
                                              @Valid @RequestBody FeeManualReviewRequest request) {
        return ApiResponse.ok(claimService.manualReview(id, request));
    }

    // ---------- 改派：原单冻结，新路线重新估算 ----------

    /** 办理改派：原申请冻结只读，按新批次 / 区域 / 出行日期 / 交通方式新建申请重新估算。 */
    @PostMapping("/{id}/reassign")
    public ApiResponse<FeeClaim> reassign(@PathVariable Long id, @Valid @RequestBody FeeReassignRequest request) {
        return ApiResponse.ok(claimService.reassign(id, request));
    }

    // ---------- 确认后调整（只追加） ----------

    /** 确认锁定后唯一修订入口：追加调整单，变动额（可正可负）累加到应付。 */
    @PostMapping("/{id}/adjustments")
    public ApiResponse<FeeClaimAdjustment> adjust(@PathVariable Long id,
                                                  @Valid @RequestBody FeeAdjustRequest request) {
        return ApiResponse.ok(claimService.adjust(id, request));
    }

    @GetMapping("/{id}/adjustments")
    public ApiResponse<List<FeeClaimAdjustment>> adjustments(@PathVariable Long id) {
        return ApiResponse.ok(claimService.adjustments(id));
    }
}
