package com.evops.invigilatefee.controller;

import com.evops.common.ApiResponse;
import com.evops.invigilatefee.dto.FeeManualReviewRequest;
import com.evops.invigilatefee.dto.ReimbursementAdjustRequest;
import com.evops.invigilatefee.dto.ReimbursementApplyRequest;
import com.evops.invigilatefee.dto.ReceiptSubmitRequest;
import com.evops.invigilatefee.dto.RerouteRequest;
import com.evops.invigilatefee.entity.ReimbursementAdjustment;
import com.evops.invigilatefee.entity.TravelReimbursement;
import com.evops.invigilatefee.entity.TravelReimbVersion;
import com.evops.invigilatefee.entity.TravelReceipt;
import com.evops.invigilatefee.entity.TravelRouteChange;
import com.evops.invigilatefee.service.ReimbursementFinanceService;
import com.evops.invigilatefee.service.TravelReceiptService;
import com.evops.invigilatefee.service.TravelReimbursementService;
import com.evops.invigilatefee.vo.EstimatePreview;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * 跨区监考交通补贴报销（模块 + 版本 + 状态）。
 *
 * 视图口径：所有接口走 Shiro HTTP Basic；请求头 X-Self-Code 传监考人员编码即“教师本人视图”，
 * 只能为本人申请 / 改派 / 提交凭证 / 查询；不传为财务全量视图，可人工核定、确认、调整、看全量。
 * 经办人由 X-Operator 记录。
 */
@RestController
@RequestMapping("/api/travel-reimbursements")
public class TravelReimbursementController {

    private final TravelReimbursementService reimbursementService;
    private final TravelReceiptService receiptService;
    private final ReimbursementFinanceService financeService;

    public TravelReimbursementController(TravelReimbursementService reimbursementService,
                                         TravelReceiptService receiptService,
                                         ReimbursementFinanceService financeService) {
        this.reimbursementService = reimbursementService;
        this.receiptService = receiptService;
        this.financeService = financeService;
    }

    // ---------- 试算 / 申请 / 改派 ----------

    /** 试算：按考点里程 + 交通方式 + 出行日期命中规则版本估金额，不落库。 */
    @PostMapping("/preview")
    public ApiResponse<EstimatePreview> preview(@Valid @RequestBody ReimbursementApplyRequest request) {
        return ApiResponse.ok(reimbursementService.preview(request));
    }

    /** 申请：冻结规则版本/口径/里程，金额独立核算，生成 DRAFT 与 CREATE 版本。 */
    @PostMapping
    public ApiResponse<TravelReimbursement> apply(@Valid @RequestBody ReimbursementApplyRequest request) {
        return ApiResponse.ok(reimbursementService.apply(request));
    }

    /** 改派：原申请只读保留为历史版本，按新路线重新选版本、重新估算。 */
    @PostMapping("/{id}/reroute")
    public ApiResponse<TravelReimbursement> reroute(@PathVariable Long id,
                                                    @Valid @RequestBody RerouteRequest request) {
        return ApiResponse.ok(reimbursementService.reroute(id, request));
    }

    // ---------- 票据（教师提交本人凭证） ----------

    /** 提交一张本人票据（仅草稿可补；改派后需重新提交）。 */
    @PostMapping("/{id}/receipts")
    public ApiResponse<TravelReceipt> submitReceipt(@PathVariable Long id,
                                                    @Valid @RequestBody ReceiptSubmitRequest request) {
        return ApiResponse.ok(receiptService.submit(id, request));
    }

    @GetMapping("/{id}/receipts")
    public ApiResponse<List<TravelReceipt>> receipts(@PathVariable Long id) {
        return ApiResponse.ok(receiptService.listReceipts(id));
    }

    /** 教师提交报销：合计票据判异常——超阈转 MANUAL_REVIEW，否则 SUBMITTED 待财务确认。 */
    @PostMapping("/{id}/submit")
    public ApiResponse<TravelReimbursement> submit(@PathVariable Long id) {
        return ApiResponse.ok(receiptService.submitForReview(id));
    }

    // ---------- 查询（本人 / 财务全量） ----------

    /** 列表：本人视图强制只看本人；财务全量可按批次 / 状态 / 人员过滤。 */
    @GetMapping
    public ApiResponse<List<TravelReimbursement>> list(@RequestParam(required = false) Long batchId,
                                                       @RequestParam(required = false) String status,
                                                       @RequestParam(required = false) String invigilatorCode) {
        return ApiResponse.ok(reimbursementService.list(batchId, status, invigilatorCode));
    }

    @GetMapping("/{id}")
    public ApiResponse<TravelReimbursement> get(@PathVariable Long id) {
        return ApiResponse.ok(reimbursementService.get(id));
    }

    /** 详情：报销单 + 票据 + 调整台账 + 最终应付（本人仅本单，财务全量）。 */
    @GetMapping("/{id}/detail")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.ok(financeService.detail(id));
    }

    /** 版本链：新建 / 改派 / 提交票据 / 转人工 / 核定 / 确认全程只追加。 */
    @GetMapping("/{id}/versions")
    public ApiResponse<List<TravelReimbVersion>> versions(@PathVariable Long id) {
        return ApiResponse.ok(reimbursementService.versions(id));
    }

    /** 改派记录：按批次查；本人视图只返回本人报销的改派。 */
    @GetMapping("/route-changes")
    public ApiResponse<List<TravelRouteChange>> routeChangesByBatch(@RequestParam(required = false) Long batchId) {
        return ApiResponse.ok(reimbursementService.routeChanges(batchId, null));
    }

    @GetMapping("/{id}/route-changes")
    public ApiResponse<List<TravelRouteChange>> routeChanges(@PathVariable Long id) {
        return ApiResponse.ok(reimbursementService.routeChanges(null, id));
    }

    // ---------- 财务：异常人工核定 / 确认锁定 / 调整 ----------

    /** 异常待人工清单（财务全量）。 */
    @GetMapping("/manual-queue")
    public ApiResponse<List<TravelReimbursement>> manualQueue() {
        return ApiResponse.ok(financeService.listManualQueue());
    }

    /** 人工核定异常金额：放行（可给核定金额）转待确认；驳回写原因。 */
    @PostMapping("/{id}/manual-review")
    public ApiResponse<TravelReimbursement> manualReview(@PathVariable Long id,
                                                         @RequestBody(required = false) FeeManualReviewRequest request) {
        return ApiResponse.ok(financeService.manualReview(id, request));
    }

    /** 财务确认并锁定：核定金额落定，票据置已核入；之后修订走调整单。 */
    @PostMapping("/{id}/confirm")
    public ApiResponse<TravelReimbursement> confirm(@PathVariable Long id) {
        return ApiResponse.ok(financeService.confirm(id));
    }

    /** 确认锁定后唯一修订入口：追加调整单（金额可正可负）。 */
    @PostMapping("/{id}/adjustments")
    public ApiResponse<ReimbursementAdjustment> adjust(@PathVariable Long id,
                                                       @Valid @RequestBody ReimbursementAdjustRequest request) {
        return ApiResponse.ok(financeService.adjust(id, request));
    }

    @GetMapping("/{id}/adjustments")
    public ApiResponse<List<ReimbursementAdjustment>> adjustments(@PathVariable Long id) {
        return ApiResponse.ok(financeService.adjustments(id));
    }
}
