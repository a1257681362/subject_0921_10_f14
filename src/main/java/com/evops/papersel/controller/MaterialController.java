package com.evops.papersel.controller;

import com.evops.common.ApiResponse;
import com.evops.papersel.dto.MaterialDisableRequest;
import com.evops.papersel.dto.MaterialRequest;
import com.evops.papersel.dto.MaterialUpdateRequest;
import com.evops.papersel.entity.PaperMaterial;
import com.evops.papersel.entity.PaperMaterialChange;
import com.evops.papersel.service.MaterialPoolService;
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
 * 素材池模块（增删改只留痕、不物理删除）。路径在 /api/** 下，自动走 HTTP Basic。
 */
@RestController
@RequestMapping("/api/paper-materials")
public class MaterialController {

    private final MaterialPoolService materialPoolService;

    public MaterialController(MaterialPoolService materialPoolService) {
        this.materialPoolService = materialPoolService;
    }

    @PostMapping
    public ApiResponse<PaperMaterial> create(@Valid @RequestBody MaterialRequest request) {
        return ApiResponse.ok(materialPoolService.create(request));
    }

    /** 修改内容/归类；原因记台账。 */
    @PostMapping("/{id}/update")
    public ApiResponse<PaperMaterial> update(@PathVariable Long id,
                                             @Valid @RequestBody MaterialUpdateRequest request) {
        return ApiResponse.ok(materialPoolService.update(id, request));
    }

    /** 下架（不物理删除，历史卷面不受影响）。 */
    @PostMapping("/{id}/disable")
    public ApiResponse<PaperMaterial> disable(@PathVariable Long id,
                                              @Valid @RequestBody MaterialDisableRequest request) {
        return ApiResponse.ok(materialPoolService.disable(id, request));
    }

    /** 恢复入池。 */
    @PostMapping("/{id}/enable")
    public ApiResponse<PaperMaterial> enable(@PathVariable Long id,
                                             @RequestParam(required = false) String reason) {
        return ApiResponse.ok(materialPoolService.enable(id, reason));
    }

    @GetMapping
    public ApiResponse<List<PaperMaterial>> list(
            @RequestParam(required = false) String projectCode,
            @RequestParam(required = false) String knowledgePoint,
            @RequestParam(required = false) String difficulty,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(materialPoolService.list(projectCode, knowledgePoint, difficulty, status));
    }

    @GetMapping("/{id}")
    public ApiResponse<PaperMaterial> get(@PathVariable Long id) {
        return ApiResponse.ok(materialPoolService.getMaterial(id));
    }

    /** 素材增删改台账（只追加）。 */
    @GetMapping("/{id}/changes")
    public ApiResponse<List<PaperMaterialChange>> changes(@PathVariable Long id) {
        return ApiResponse.ok(materialPoolService.listChanges(id));
    }
}
