package com.evops.grade.controller;

import com.evops.appoint.common.SelfHolder;
import com.evops.common.ApiResponse;
import com.evops.grade.common.GradeConstants;
import com.evops.grade.entity.ReviewMaterialVersion;
import com.evops.grade.entity.ScoreNotification;
import com.evops.grade.service.ReviewExportService;
import com.evops.grade.service.ReviewMaterialService;
import com.evops.grade.service.ScoreNotificationService;
import com.evops.grade.service.ScoreReviewService;
import com.evops.grade.vo.ReviewSelfVO;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.util.List;

/**
 * 学员本人复核窗口（X-Self-Code=报名号）：
 * - 只能读取本人资料：非本人的复核单/材料/通知统一按不存在处理，不暴露存在性；
 * - 窗口关闭后历史内容（申请、材料、回执）仍可查看，仅新受理被禁止（提交仍只在窗口 OPEN）；
 * - 材料阅读单独留痕，不影响申请流转；视图与导出均不含内部批注。
 */
@RestController
@RequestMapping("/api/reviews/my")
public class ReviewSelfController {

    private final ScoreReviewService reviewService;
    private final ReviewMaterialService materialService;
    private final ScoreNotificationService notificationService;
    private final ReviewExportService exportService;

    public ReviewSelfController(ScoreReviewService reviewService,
                                ReviewMaterialService materialService,
                                ScoreNotificationService notificationService,
                                ReviewExportService exportService) {
        this.reviewService = reviewService;
        this.materialService = materialService;
        this.notificationService = notificationService;
        this.exportService = exportService;
    }

    /** 本人的复核申请列表（窗口关闭后历史仍可查看；脱敏视图，不含审核岗经办信息）。 */
    @GetMapping
    public ApiResponse<List<ReviewSelfVO>> myReviews(@RequestParam(required = false) String status) {
        return ApiResponse.ok(reviewService.selfList(SelfHolder.currentSelfCode(), status));
    }

    /** 本人复核详情：含材料（按进入时间、旧版留摘要、无内部批注）与挂回原申请的通知回执。 */
    @GetMapping("/{id}")
    public ApiResponse<ReviewSelfVO> myReview(@PathVariable Long id) {
        return ApiResponse.ok(reviewService.selfView(id, SelfHolder.currentSelfCode()));
    }

    /** 学员阅读本人材料当前版并留痕（SELF；独立于申请流转，窗口关闭后仍可阅读）。 */
    @PostMapping("/materials/{materialId}/read")
    public ApiResponse<ReviewMaterialVersion> readMaterial(@PathVariable Long materialId) {
        String regNo = SelfHolder.currentSelfCode();
        return ApiResponse.ok(materialService.read(materialId, regNo,
                GradeConstants.READER_SELF, regNo));
    }

    /** 本人的通知回执（结论 / 超窗关闭），按时间倒序。 */
    @GetMapping("/notifications")
    public ApiResponse<List<ScoreNotification>> myNotifications(
            @RequestParam(required = false, defaultValue = "false") boolean unreadOnly) {
        return ApiResponse.ok(notificationService.list(SelfHolder.currentSelfCode(), unreadOnly));
    }

    /** 本人标记通知回执已读：非本人通知统一按不存在处理。 */
    @PostMapping("/notifications/{id}/read")
    public ApiResponse<ScoreNotification> readNotification(@PathVariable Long id) {
        return ApiResponse.ok(notificationService.markReadForSelf(id, SelfHolder.currentSelfCode()));
    }

    /** 导出本人复核资料 CSV（姓名掩码、含材料版本摘要、不含内部批注）。 */
    @GetMapping("/export.csv")
    public ResponseEntity<byte[]> exportMine() {
        byte[] body = exportService.exportSelf(SelfHolder.currentSelfCode());
        return ReviewExportController.csv(body, "my-grade-reviews.csv");
    }
}
