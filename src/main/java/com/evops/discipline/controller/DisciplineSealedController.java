package com.evops.discipline.controller;

import com.evops.common.ApiResponse;
import com.evops.discipline.entity.DisciplineCase;
import com.evops.discipline.entity.DisciplineCaseVersion;
import com.evops.discipline.entity.DisciplineConsequence;
import com.evops.discipline.entity.DisciplineEvidence;
import com.evops.discipline.service.DisciplineCaseService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 违纪封存档案窗口：独立路径 /api/discipline/sealed/**，仅 system 或 discipline_archiver 授权岗可访问。
 * 只回已封存（SEALED）记录，含证据、后果、完整版本轨迹；未封存案件在此窗口查不到。
 * 本窗口只读，不提供任何变更入口。
 */
@RestController
@RequestMapping("/api/discipline/sealed")
public class DisciplineSealedController {

    private final DisciplineCaseService caseService;

    public DisciplineSealedController(DisciplineCaseService caseService) {
        this.caseService = caseService;
    }

    @GetMapping("/cases")
    public ApiResponse<List<DisciplineCase>> list(@RequestParam(required = false) Long batchId,
                                                  @RequestParam(required = false) String violationType) {
        return ApiResponse.ok(caseService.listSealed(batchId, violationType));
    }

    @GetMapping("/cases/{id}")
    public ApiResponse<DisciplineCase> get(@PathVariable Long id) {
        return ApiResponse.ok(caseService.requireSealedCase(id));
    }

    @GetMapping("/cases/{id}/evidences")
    public ApiResponse<List<DisciplineEvidence>> evidences(@PathVariable Long id) {
        caseService.requireSealedCase(id);
        return ApiResponse.ok(caseService.evidences(id));
    }

    @GetMapping("/cases/{id}/consequences")
    public ApiResponse<List<DisciplineConsequence>> consequences(@PathVariable Long id) {
        caseService.requireSealedCase(id);
        return ApiResponse.ok(caseService.consequences(id));
    }

    @GetMapping("/cases/{id}/versions")
    public ApiResponse<List<DisciplineCaseVersion>> versions(@PathVariable Long id) {
        caseService.requireSealedCase(id);
        return ApiResponse.ok(caseService.versions(id));
    }
}
