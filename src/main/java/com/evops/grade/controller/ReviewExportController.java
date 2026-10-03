package com.evops.grade.controller;

import com.evops.grade.service.ReviewExportService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;

/**
 * 复核导出（内部管理端）：按批次导出申请流转结论、结论依据与材料版本摘要。
 * 导出不带内部批注；历史内容不受复核窗口关闭影响。
 */
@RestController
@RequestMapping("/api/reviews")
public class ReviewExportController {

    private final ReviewExportService exportService;

    public ReviewExportController(ReviewExportService exportService) {
        this.exportService = exportService;
    }

    @GetMapping("/batch/{batchId}/export.csv")
    public ResponseEntity<byte[]> exportBatch(@PathVariable Long batchId) {
        return csv(exportService.exportBatch(batchId), "grade-review-batch-" + batchId + ".csv");
    }

    static ResponseEntity<byte[]> csv(byte[] body, String filename) {
        String encoded;
        try {
            encoded = URLEncoder.encode(filename, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            encoded = filename;
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv; charset=UTF-8"));
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + filename + "\"; filename*=UTF-8''" + encoded);
        return ResponseEntity.ok().headers(headers).body(body);
    }
}
