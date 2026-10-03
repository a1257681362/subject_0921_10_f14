package com.evops.discipline.controller;

import com.evops.appoint.common.SelfHolder;
import com.evops.common.ApiResponse;
import com.evops.discipline.dto.CaseDecisionRequest;
import com.evops.discipline.dto.CaseExecuteRequest;
import com.evops.discipline.dto.CaseReportRequest;
import com.evops.discipline.dto.CaseRevokeRequest;
import com.evops.discipline.dto.CaseRevokeReviewRequest;
import com.evops.discipline.dto.CaseSealRequest;
import com.evops.discipline.dto.EvidenceAddRequest;
import com.evops.discipline.entity.DisciplineCase;
import com.evops.discipline.entity.DisciplineCaseVersion;
import com.evops.discipline.entity.DisciplineConsequence;
import com.evops.discipline.entity.DisciplineEvidence;
import com.evops.discipline.entity.DisciplineUrge;
import com.evops.discipline.service.DisciplineCaseService;
import com.evops.discipline.service.DisciplineConfigService;
import com.evops.discipline.service.DisciplineUrgeService;
import com.evops.discipline.vo.StudentCaseVO;
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
 * 违纪立案流程（模块/版本/状态）：上报立案附证据 → 限期决定（超时自动催办）→ 后果执行回写成绩档案
 * → 撤销走双人复核 → 结案封存；学员凭报名号（X-Self-Code）脱敏自查。
 * 管理接口走内部 system 角色；封存记录见 /api/discipline/sealed（授权岗窗口）。
 */
@RestController
@RequestMapping("/api/discipline")
public class DisciplineCaseController {

    private final DisciplineCaseService caseService;
    private final DisciplineUrgeService urgeService;
    private final DisciplineConfigService configService;

    public DisciplineCaseController(DisciplineCaseService caseService,
                                    DisciplineUrgeService urgeService,
                                    DisciplineConfigService configService) {
        this.caseService = caseService;
        this.urgeService = urgeService;
        this.configService = configService;
    }

    // ---------- 监考上报附证据立案 ----------

    @PostMapping("/cases")
    public ApiResponse<DisciplineCase> report(@Valid @RequestBody CaseReportRequest request) {
        return ApiResponse.ok(caseService.report(request));
    }

    /** 立案后补充证据（只追加）。 */
    @PostMapping("/cases/{id}/evidences")
    public ApiResponse<List<DisciplineEvidence>> addEvidence(@PathVariable Long id,
                                                             @Valid @RequestBody EvidenceAddRequest request) {
        return ApiResponse.ok(caseService.addEvidence(id, request.getEvidences()));
    }

    // ---------- 限期处理决定 / 执行 / 撤销复核 / 封存 ----------

    @PostMapping("/cases/{id}/decision")
    public ApiResponse<DisciplineCase> decide(@PathVariable Long id,
                                              @Valid @RequestBody CaseDecisionRequest request) {
        return ApiResponse.ok(caseService.decide(id, request));
    }

    @PostMapping("/cases/{id}/execute")
    public ApiResponse<DisciplineCase> execute(@PathVariable Long id,
                                               @RequestBody(required = false) CaseExecuteRequest request) {
        String remark = request == null ? null : request.getRemark();
        return ApiResponse.ok(caseService.execute(id, remark));
    }

    @PostMapping("/cases/{id}/revoke")
    public ApiResponse<DisciplineCase> applyRevoke(@PathVariable Long id,
                                                   @Valid @RequestBody CaseRevokeRequest request) {
        return ApiResponse.ok(caseService.applyRevoke(id, request));
    }

    @PostMapping("/cases/{id}/revoke-review")
    public ApiResponse<DisciplineCase> reviewRevoke(@PathVariable Long id,
                                                    @Valid @RequestBody CaseRevokeReviewRequest request) {
        return ApiResponse.ok(caseService.reviewRevoke(id, request));
    }

    @PostMapping("/cases/{id}/seal")
    public ApiResponse<DisciplineCase> seal(@PathVariable Long id,
                                            @RequestBody(required = false) CaseSealRequest request) {
        String remark = request == null ? null : request.getRemark();
        return ApiResponse.ok(caseService.seal(id, remark));
    }

    // ---------- 管理查询（全量内部视图，含证据/后果/版本轨迹） ----------

    @GetMapping("/cases")
    public ApiResponse<List<DisciplineCase>> list(@RequestParam(required = false) Long batchId,
                                                  @RequestParam(required = false) String status,
                                                  @RequestParam(required = false) String violationType,
                                                  @RequestParam(required = false) Boolean sealed) {
        return ApiResponse.ok(caseService.list(batchId, status, violationType, sealed));
    }

    @GetMapping("/cases/{id}")
    public ApiResponse<DisciplineCase> get(@PathVariable Long id) {
        return ApiResponse.ok(caseService.requireCase(id));
    }

    @GetMapping("/cases/{id}/evidences")
    public ApiResponse<List<DisciplineEvidence>> evidences(@PathVariable Long id) {
        return ApiResponse.ok(caseService.evidences(id));
    }

    @GetMapping("/cases/{id}/consequences")
    public ApiResponse<List<DisciplineConsequence>> consequences(@PathVariable Long id) {
        return ApiResponse.ok(caseService.consequences(id));
    }

    @GetMapping("/cases/{id}/versions")
    public ApiResponse<List<DisciplineCaseVersion>> versions(@PathVariable Long id) {
        return ApiResponse.ok(caseService.versions(id));
    }

    // ---------- 学员脱敏自查（X-Self-Code=报名号，只回自己案件的脱敏状态） ----------

    @GetMapping("/my-cases")
    public ApiResponse<List<StudentCaseVO>> myCases() {
        return ApiResponse.ok(caseService.studentCases(SelfHolder.currentSelfCode()));
    }

    @GetMapping("/my-cases/by-no/{caseNo}")
    public ApiResponse<StudentCaseVO> myCaseByNo(@PathVariable String caseNo) {
        return ApiResponse.ok(caseService.studentByCaseNo(SelfHolder.currentSelfCode(), caseNo));
    }

    // ---------- 超时催办（自动调度 + 人工立即执行 + 查询/已读） ----------

    @PostMapping("/urge/sweep")
    public ApiResponse<Map<String, Object>> sweep() {
        return ApiResponse.ok(urgeService.sweep());
    }

    @GetMapping("/urges")
    public ApiResponse<List<DisciplineUrge>> urges(@RequestParam(required = false) Long caseId,
                                                   @RequestParam(required = false) String status,
                                                   @RequestParam(required = false) String recipient) {
        return ApiResponse.ok(urgeService.listUrges(caseId, status, recipient));
    }

    @PostMapping("/urges/{id}/read")
    public ApiResponse<DisciplineUrge> readUrge(@PathVariable Long id) {
        return ApiResponse.ok(urgeService.markRead(id));
    }

    // ---------- 处理期限/催办配置 ----------

    @GetMapping("/config")
    public ApiResponse<List<?>> listConfig() {
        return ApiResponse.ok(configService.list());
    }

    @org.springframework.web.bind.annotation.PutMapping("/config/{key}")
    public ApiResponse<?> updateConfig(@PathVariable String key,
                                       @RequestParam String value,
                                       @RequestParam(required = false) String remark) {
        return ApiResponse.ok(configService.update(key, value, remark));
    }
}
