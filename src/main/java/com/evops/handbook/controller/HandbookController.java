package com.evops.handbook.controller;

import com.evops.common.ApiResponse;
import com.evops.handbook.common.SiteScopeHolder;
import com.evops.handbook.dto.HandbookAdjustRequest;
import com.evops.handbook.dto.HandbookCreateRequest;
import com.evops.handbook.dto.HandbookReissueRequest;
import com.evops.handbook.dto.HandbookReleaseRequest;
import com.evops.handbook.entity.HandbookNotice;
import com.evops.handbook.entity.HandbookRelease;
import com.evops.handbook.entity.HandbookVersion;
import com.evops.handbook.entity.InvigilationHandbook;
import com.evops.handbook.service.HandbookService;
import org.springframework.http.MediaType;
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
 * 监考手册（模块 + 版本 + 状态）。路径在 /api/** 下走 HTTP Basic。
 * 下载权限跟随考点范围：请求头 X-Site-Code=<考点编码> 仅可访问本考点手册。
 */
@RestController
@RequestMapping("/api/handbooks")
public class HandbookController {

    private final HandbookService handbookService;

    public HandbookController(HandbookService handbookService) {
        this.handbookService = handbookService;
    }

    /** 写入手册：内容落 evops-base 后进入待确认状态。 */
    @PostMapping
    public ApiResponse<InvigilationHandbook> create(@Valid @RequestBody HandbookCreateRequest request) {
        return ApiResponse.ok(handbookService.create(request));
    }

    /** 列表：带 X-Site-Code 仅返回该考点手册。 */
    @GetMapping
    public ApiResponse<List<InvigilationHandbook>> list() {
        return ApiResponse.ok(handbookService.list(SiteScopeHolder.currentSiteCode()));
    }

    @GetMapping("/{handbookId}")
    public ApiResponse<InvigilationHandbook> get(@PathVariable Long handbookId) {
        return ApiResponse.ok(handbookService.get(handbookId, SiteScopeHolder.currentSiteCode()));
    }

    /** 调整：另建版本（先待确认），通知在放行时发出。 */
    @PostMapping("/{handbookId}/adjust")
    public ApiResponse<HandbookVersion> adjust(@PathVariable Long handbookId,
                                               @Valid @RequestBody HandbookAdjustRequest request) {
        return ApiResponse.ok(handbookService.adjust(handbookId, request));
    }

    /** 负责人放行：DRAFT 待确认 → RELEASED，生成打印版本并通知考点。 */
    @PostMapping("/{handbookId}/release")
    public ApiResponse<HandbookRelease> release(@PathVariable Long handbookId,
                                                @RequestBody(required = false) HandbookReleaseRequest request) {
        return ApiResponse.ok(handbookService.release(handbookId, request));
    }

    /** 补发：沿用原发布批次、生效时刻与经办人。 */
    @PostMapping("/{handbookId}/reissue")
    public ApiResponse<HandbookRelease> reissue(@PathVariable Long handbookId,
                                                @RequestBody(required = false) HandbookReissueRequest request) {
        return ApiResponse.ok(handbookService.reissue(handbookId, request));
    }

    /** 人工归档（过期由调度器自动归档）。 */
    @PostMapping("/{handbookId}/archive")
    public ApiResponse<Void> archive(@PathVariable Long handbookId,
                                     @RequestParam(required = false) String reason) {
        handbookService.archive(handbookId, reason);
        return ApiResponse.ok(null);
    }

    @GetMapping("/{handbookId}/versions")
    public ApiResponse<List<HandbookVersion>> versions(@PathVariable Long handbookId) {
        return ApiResponse.ok(handbookService.listVersions(handbookId, SiteScopeHolder.currentSiteCode()));
    }

    @GetMapping("/{handbookId}/releases")
    public ApiResponse<List<HandbookRelease>> releases(@PathVariable Long handbookId) {
        return ApiResponse.ok(handbookService.listReleases(handbookId, SiteScopeHolder.currentSiteCode()));
    }

    @GetMapping("/{handbookId}/notices")
    public ApiResponse<List<HandbookNotice>> notices(@PathVariable Long handbookId) {
        return ApiResponse.ok(handbookService.listNotices(handbookId, SiteScopeHolder.currentSiteCode()));
    }

    @PostMapping("/notices/{noticeId}/read")
    public ApiResponse<HandbookNotice> readNotice(@PathVariable Long noticeId) {
        return ApiResponse.ok(handbookService.markNoticeRead(noticeId, SiteScopeHolder.currentSiteCode()));
    }

    /**
     * 下载打印版本（浏览器直接打印 / 另存 PDF）。
     * 仅已放行未归档手册可下载；releaseId 缺省为当前生效版本冻结的打印版，
     * 指定补发批次时返回带补发抬头的同版内容。
     */
    @GetMapping(value = "/{handbookId}/print", produces = MediaType.TEXT_HTML_VALUE)
    public String print(@PathVariable Long handbookId,
                        @RequestParam(required = false) Long releaseId) {
        return handbookService.viewPrintHtml(handbookId, releaseId, SiteScopeHolder.currentSiteCode());
    }
}
