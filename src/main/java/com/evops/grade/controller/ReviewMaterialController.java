package com.evops.grade.controller;

import com.evops.common.ApiResponse;
import com.evops.common.OperatorHolder;
import com.evops.grade.common.GradeConstants;
import com.evops.grade.dto.MaterialRegisterRequest;
import com.evops.grade.dto.MaterialReplaceRequest;
import com.evops.grade.entity.ReviewMaterial;
import com.evops.grade.entity.ReviewMaterialRead;
import com.evops.grade.entity.ReviewMaterialVersion;
import com.evops.grade.service.ReviewMaterialService;
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
 * 复核材料管理端（内部 system）：进入留档、附件版本替换（旧版留摘要）、内部批注、阅读留痕。
 * 材料处理与申请流转分开：任何状态/窗口下材料都可登记、可阅读；窗口关闭只影响新受理。
 * 学员本人入口见 /api/reviews/my/**。
 */
@RestController
@RequestMapping("/api/review-materials")
public class ReviewMaterialController {

    private final ReviewMaterialService materialService;

    public ReviewMaterialController(ReviewMaterialService materialService) {
        this.materialService = materialService;
    }

    /** 材料进入 evops-base 留档（按进入时间排列）。 */
    @PostMapping
    public ApiResponse<ReviewMaterial> register(@Valid @RequestBody MaterialRegisterRequest request) {
        return ApiResponse.ok(materialService.register(request, OperatorHolder.current()));
    }

    /** 附件版本替换：旧版不删、保留摘要置 SUPERSEDED，追加 ACTIVE 新版。 */
    @PostMapping("/{id}/replace")
    public ApiResponse<ReviewMaterialVersion> replace(@PathVariable Long id,
                                                      @Valid @RequestBody MaterialReplaceRequest request) {
        return ApiResponse.ok(materialService.replace(id, request, OperatorHolder.current()));
    }

    /** 写/改内部批注（仅内部视图可见，学员视图与导出都不带出）。 */
    @PostMapping("/{id}/annotation")
    public ApiResponse<ReviewMaterial> annotate(@PathVariable Long id,
                                                @RequestParam String annotation) {
        return ApiResponse.ok(materialService.annotate(id, annotation));
    }

    /** 审核岗阅读当前版并留痕（STAFF，不改变复核单状态）。 */
    @PostMapping("/{id}/read")
    public ApiResponse<ReviewMaterialVersion> read(@PathVariable Long id) {
        return ApiResponse.ok(materialService.read(id, OperatorHolder.current(),
                GradeConstants.READER_STAFF, null));
    }

    @GetMapping
    public ApiResponse<List<ReviewMaterial>> listByReview(@RequestParam Long reviewId) {
        return ApiResponse.ok(materialService.listByReview(reviewId));
    }

    @GetMapping("/{id}")
    public ApiResponse<ReviewMaterial> get(@PathVariable Long id) {
        return ApiResponse.ok(materialService.getMaterial(id));
    }

    /** 版本链（含 SUPERSEDED 旧版摘要）。 */
    @GetMapping("/{id}/versions")
    public ApiResponse<List<ReviewMaterialVersion>> versions(@PathVariable Long id) {
        return ApiResponse.ok(materialService.listVersions(id));
    }

    /** 阅读记录（审核岗/学员阅读都在此，只追加）。 */
    @GetMapping("/{id}/reads")
    public ApiResponse<List<ReviewMaterialRead>> reads(@PathVariable Long id) {
        return ApiResponse.ok(materialService.listReads(id));
    }
}
