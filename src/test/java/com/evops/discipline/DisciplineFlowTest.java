package com.evops.discipline;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.common.BusinessException;
import com.evops.discipline.common.DisciplineConstants;
import com.evops.discipline.dto.CaseDecisionRequest;
import com.evops.discipline.dto.CaseReportRequest;
import com.evops.discipline.dto.CaseRevokeRequest;
import com.evops.discipline.dto.CaseRevokeReviewRequest;
import com.evops.discipline.entity.DisciplineCase;
import com.evops.discipline.entity.DisciplineConsequence;
import com.evops.discipline.entity.DisciplineEvidence;
import com.evops.discipline.entity.DisciplineUrge;
import com.evops.discipline.mapper.DisciplineCaseMapper;
import com.evops.discipline.mapper.DisciplineConsequenceMapper;
import com.evops.discipline.mapper.DisciplineUrgeMapper;
import com.evops.discipline.service.DisciplineCaseService;
import com.evops.discipline.service.DisciplineUrgeService;
import com.evops.discipline.vo.StudentCaseVO;
import com.evops.exam.entity.ExamBatch;
import com.evops.exam.entity.ExamSite;
import com.evops.exam.entity.Registration;
import com.evops.exam.mapper.ExamBatchMapper;
import com.evops.exam.mapper.ExamSiteMapper;
import com.evops.exam.mapper.RegistrationMapper;
import com.evops.exam.service.WorkspaceVersionService;
import com.evops.grade.dto.GradeBand;
import com.evops.grade.dto.GradeRuleRequest;
import com.evops.grade.dto.GradeRuleVersionRequest;
import com.evops.grade.dto.ScoreImportItem;
import com.evops.grade.dto.ScoreImportRequest;
import com.evops.grade.entity.ExamScore;
import com.evops.grade.mapper.ExamScoreMapper;
import com.evops.grade.service.GradeRuleService;
import com.evops.grade.service.ScoreService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 违纪立案端到端：
 * 上报附证据立案 → 限期决定（超时自动催办/间隔升级）→ 成绩作废+禁考执行回写成绩档案
 * → 撤销立案走双人复核（通过同步还原成绩与后果）→ 结案封存；
 * 晚到成绩挂起自动回写；学员脱敏自查；纳入工作区快照/回退。
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DisciplineFlowTest {

    @Autowired
    private DisciplineCaseService caseService;
    @Autowired
    private DisciplineUrgeService urgeService;
    @Autowired
    private DisciplineCaseMapper caseMapper;
    @Autowired
    private DisciplineConsequenceMapper consequenceMapper;
    @Autowired
    private DisciplineUrgeMapper urgeMapper;
    @Autowired
    private ExamScoreMapper scoreMapper;
    @Autowired
    private ScoreService scoreService;
    @Autowired
    private GradeRuleService gradeRuleService;
    @Autowired
    private WorkspaceVersionService workspaceVersionService;
    @Autowired
    private ExamSiteMapper siteMapper;
    @Autowired
    private ExamBatchMapper batchMapper;
    @Autowired
    private RegistrationMapper registrationMapper;

    private ExamBatch batch;

    /**
     * 幂等造基础数据：多个测试方法共享同一 Spring 上下文（AFTER_CLASS 才清），
     * 且快照回退会清空业务表，因此每方法开头调用，缺什么补什么。
     */
    private void ensureBase() {
        ExamSite site = siteMapper.selectOne(new QueryWrapper<ExamSite>().eq("code", "SDISC"));
        if (site == null) {
            site = new ExamSite();
            site.setCode("SDISC");
            site.setName("违纪考点");
            site.setStatus("ENABLED");
            siteMapper.insert(site);
        }
        batch = batchMapper.selectOne(new QueryWrapper<ExamBatch>().eq("code", "BDISC"));
        if (batch == null) {
            batch = new ExamBatch();
            batch.setCode("BDISC");
            batch.setName("违纪批次");
            batch.setSiteId(site.getId());
            batch.setProjectCode("DEFAULT");
            batch.setExamDate(LocalDate.of(2026, 10, 1));
            batch.setStatus("CREATED");
            batchMapper.insert(batch);
        }
        String[][] roster = {{"D001", "张三"}, {"D002", "李四"},
                {"D003", "王五"}, {"D004", "赵六"}, {"D005", "钱七"}, {"D006", "孙八"}};
        for (String[] rn : roster) {
            Long count = registrationMapper.selectCount(new QueryWrapper<Registration>().eq("reg_no", rn[0]));
            if (count != null && count > 0) {
                continue;
            }
            Registration reg = new Registration();
            reg.setRegNo(rn[0]);
            reg.setName(rn[1]);
            reg.setIdNo("ID" + rn[0]);
            reg.setBatchId(batch.getId());
            reg.setStatus("REGISTERED");
            registrationMapper.insert(reg);
        }
        if (gradeRuleService.listRules("DEFAULT").isEmpty()) {
            publishGradeRule();
        }
    }

    private void publishGradeRule() {
        GradeRuleRequest rr = new GradeRuleRequest();
        rr.setCode("GRDISC");
        rr.setName("违纪测试等级规则");
        rr.setProjectCode("DEFAULT");
        rr.setMaxScore(100);
        gradeRuleService.createRule(rr);
        GradeBand fail = new GradeBand();
        fail.setMinScore(BigDecimal.ZERO);
        fail.setMaxScore(new BigDecimal("60"));
        fail.setGrade("不合格");
        GradeBand pass = new GradeBand();
        pass.setMinScore(new BigDecimal("60"));
        pass.setMaxScore(new BigDecimal("100"));
        pass.setGrade("合格");
        GradeRuleVersionRequest vr = new GradeRuleVersionRequest();
        vr.setBands(Arrays.asList(fail, pass));
        Long ruleId = gradeRuleService.listRules("DEFAULT").get(0).getId();
        gradeRuleService.publishVersion(gradeRuleService.createDraftVersion(ruleId, vr).getId());
    }

    private void importScore(String regNo, int score) {
        ScoreImportItem item = new ScoreImportItem();
        item.setRegNo(regNo);
        item.setPaperScore(new BigDecimal(score));
        ScoreImportRequest req = new ScoreImportRequest();
        req.setBatchId(batch.getId());
        req.setScores(Collections.singletonList(item));
        scoreService.importScores(req);
    }

    private void runAs(String operator, Runnable action) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Operator", operator);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            action.run();
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    private List<StudentCaseVO> myCasesAs(String regNo) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Self-Code", regNo);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            return caseService.studentCases(regNo);
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    private CaseReportRequest reportRequest(String regNo, String type, String assignee) {
        CaseReportRequest req = new CaseReportRequest();
        req.setBatchId(batch.getId());
        req.setRegNo(regNo);
        req.setViolationType(type);
        req.setDescription("考试中" + type + "，当场查实");
        req.setAssignee(assignee);
        CaseReportRequest.EvidenceItem statement = new CaseReportRequest.EvidenceItem();
        statement.setEvidenceType("STATEMENT");
        statement.setTitle("监考情况说明");
        statement.setContent("该生夹带纸条并被当场收缴，情节清楚。");
        CaseReportRequest.EvidenceItem photo = new CaseReportRequest.EvidenceItem();
        photo.setEvidenceType("PHOTO");
        photo.setTitle("物证照片");
        photo.setFileRef("vault://evidence/" + regNo + "/1.jpg");
        photo.setContentHash("9f32ccd1a8");
        req.setEvidences(new ArrayList<>(Arrays.asList(statement, photo)));
        return req;
    }

    private DisciplineCase report(String regNo, String type, String assignee, String reporter) {
        final DisciplineCase[] holder = new DisciplineCase[1];
        runAs(reporter, () -> holder[0] = caseService.report(reportRequest(regNo, type, assignee)));
        return holder[0];
    }

    @Test
    void fullFlow_report_decide_execute_revoke_seal() {
        ensureBase();
        // 张三：先有成绩 85，后立案，决定成绩作废并禁考，执行即回写成绩档案
        importScore("D001", 85);
        DisciplineCase c1 = report("D001", "CHEAT", "承办张", "监考王");
        assertEquals(DisciplineConstants.CASE_REPORTED, c1.getStatus());
        assertTrue(c1.getDecisionDeadline().isAfter(c1.getReportedAt()));
        assertEquals(2, caseService.evidences(c1.getId()).size());

        // 同一学员在办案件不能重复立案
        BusinessException dup = assertThrows(BusinessException.class,
                () -> report("D001", "CHEAT", "承办张", "监考王"));
        assertEquals("CASE_EXISTS", dup.getCode());

        // 非 REPORTED 状态不能出决定（先验错误状态）；正常出决定
        CaseDecisionRequest decision = new CaseDecisionRequest();
        decision.setDecisionType("SCORE_VOID_AND_BAN");
        decision.setDecisionContent("认定作弊事实清楚，本批次成绩作废，禁考一年。");
        decision.setBanFrom(LocalDate.of(2026, 10, 1));
        decision.setBanUntil(LocalDate.of(2027, 9, 30));
        // 非 DECIDED 状态不能执行（REPORTED 执行抛错，状态不变）
        runAs("承办张", () -> {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> caseService.execute(c1.getId(), null));
            assertEquals("EXECUTE_STATUS_INVALID", ex.getCode());
        });
        assertEquals(DisciplineConstants.CASE_REPORTED, caseService.requireCase(c1.getId()).getStatus());
        runAs("承办张", () -> caseService.decide(c1.getId(), decision));
        runAs("承办张", () -> caseService.execute(c1.getId(), null));

        DisciplineCase afterExecute = caseService.requireCase(c1.getId());
        assertEquals(DisciplineConstants.CASE_EXECUTED, afterExecute.getStatus());
        ExamScore score = scoreMapper.selectOne(new QueryWrapper<ExamScore>().eq("reg_no", "D001"));
        assertFalse(score.getValid());
        assertEquals(c1.getCaseNo(), score.getDisciplineCaseNo());

        List<DisciplineConsequence> consList = caseService.consequences(c1.getId());
        assertEquals(2, consList.size());
        DisciplineConsequence ban = consList.stream()
                .filter(x -> DisciplineConstants.CONS_BAN.equals(x.getConsType())).findFirst().orElse(null);
        DisciplineConsequence voidC = consList.stream()
                .filter(x -> DisciplineConstants.CONS_SCORE_VOID.equals(x.getConsType())).findFirst().orElse(null);
        assertNotNull(ban);
        assertNotNull(voidC);
        assertEquals(DisciplineConstants.CONS_EXECUTED, ban.getStatus());
        assertEquals(DisciplineConstants.CONS_EXECUTED, voidC.getStatus());
        assertEquals(0, new BigDecimal("85").compareTo(voidC.getScoreBefore()));

        // 撤销立案走双人复核：申请人不能复核自己
        CaseRevokeRequest revoke = new CaseRevokeRequest();
        revoke.setReason("事后查实监控盲区，认定依据不足");
        runAs("监考王", () -> caseService.applyRevoke(c1.getId(), revoke));
        assertEquals(DisciplineConstants.CASE_REVOKE_PENDING, caseService.requireCase(c1.getId()).getStatus());
        CaseRevokeReviewRequest review = new CaseRevokeReviewRequest();
        review.setApproved(true);
        review.setComment("复核属实，撤销");
        runAs("监考王", () -> {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> caseService.reviewRevoke(c1.getId(), review));
            assertEquals("REVIEWER_SAME_AS_APPLICANT", ex.getCode());
        });
        runAs("考务李", () -> caseService.reviewRevoke(c1.getId(), review));
        assertEquals(DisciplineConstants.CASE_REVOKED, caseService.requireCase(c1.getId()).getStatus());

        // 撤销通过：成绩恢复有效、锚点清空、后果全部取消
        ExamScore restored = scoreMapper.selectOne(new QueryWrapper<ExamScore>().eq("reg_no", "D001"));
        assertTrue(restored.getValid());
        assertNull(restored.getDisciplineCaseNo());
        for (DisciplineConsequence x : caseService.consequences(c1.getId())) {
            assertEquals(DisciplineConstants.CONS_CANCELLED, x.getStatus());
        }

        // 已撤销案件可封存；封存后只读
        runAs("档案员", () -> caseService.seal(c1.getId(), "复核撤销结案归档"));
        assertEquals(DisciplineConstants.CASE_SEALED, caseService.requireCase(c1.getId()).getStatus());
        runAs("档案员", () -> {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> caseService.addEvidence(c1.getId(),
                            Collections.singletonList(reportRequest("D001", "CHEAT", null).getEvidences().get(0))));
            assertEquals("CASE_SEALED", ex.getCode());
        });

        // 轨迹留全：CREATE/DECIDE/EXECUTE/REVOKE_APPLY/REVOKE_APPROVE/SEAL
        List<String> changes = new ArrayList<>();
        caseService.versions(c1.getId()).forEach(v -> changes.add(v.getChangeType()));
        assertTrue(changes.containsAll(Arrays.asList(
                DisciplineConstants.CHANGE_CREATE, DisciplineConstants.CHANGE_DECIDE,
                DisciplineConstants.CHANGE_EXECUTE, DisciplineConstants.CHANGE_REVOKE_APPLY,
                DisciplineConstants.CHANGE_REVOKE_APPROVE, DisciplineConstants.CHANGE_SEAL)));
    }

    @Test
    void lateScore_pendingScoreVoid_autoWrittenBackOnImport() {
        ensureBase();
        // 李四：立案时成绩尚未导入；决定成绩作废并执行 → 挂起 PENDING_SCORE，案件仍结案
        DisciplineCase c2 = report("D002", "IMPERSONATE", "承办张", "监考王");
        Long regId2 = registrationMapper.selectOne(new QueryWrapper<Registration>().eq("reg_no", "D002")).getId();
        // 仅立案、未出决定，尚不构成成绩作废
        assertFalse(caseService.isScoreVoided(regId2));
        CaseDecisionRequest decision = new CaseDecisionRequest();
        decision.setDecisionType("SCORE_VOID");
        decision.setDecisionContent("替考认定，成绩作废。");
        runAs("承办张", () -> {
            caseService.decide(c2.getId(), decision);
            caseService.execute(c2.getId(), null);
        });
        // 决定已出即视为成绩作废在效
        assertTrue(caseService.isScoreVoided(regId2));
        DisciplineConsequence pending = consequenceMapper.selectOne(
                new QueryWrapper<DisciplineConsequence>().eq("case_id", c2.getId()));
        assertEquals(DisciplineConstants.CONS_PENDING_SCORE, pending.getStatus());
        assertEquals(DisciplineConstants.CASE_EXECUTED, caseService.requireCase(c2.getId()).getStatus());

        // 成绩晚到导入：落库即无效并自动回写挂起后果
        importScore("D002", 90);
        ExamScore score = scoreMapper.selectOne(new QueryWrapper<ExamScore>().eq("reg_no", "D002"));
        assertFalse(score.getValid());
        assertEquals(c2.getCaseNo(), score.getDisciplineCaseNo());
        DisciplineConsequence done = consequenceMapper.selectById(pending.getId());
        assertEquals(DisciplineConstants.CONS_EXECUTED, done.getStatus());
        assertEquals(score.getId(), done.getScoreId());
    }

    @Test
    void overdueDecision_autoUrges_andEscalatesByInterval() {
        ensureBase();
        DisciplineCase c3 = report("D003", "DISORDER", "承办张", "监考王");
        // 未超时：不催办
        assertEquals(0, urgeService.sweep().get("urgedCases"));

        // 把决定期限改到过去：首轮自动催办
        c3.setDecisionDeadline(LocalDateTime.now().minusHours(2));
        caseMapper.updateById(c3);
        assertEquals(1, urgeService.sweep().get("urgedCases"));
        DisciplineCase urged = caseService.requireCase(c3.getId());
        assertEquals(1, urged.getUrgedCount());
        List<DisciplineUrge> urges = urgeMapper.selectList(
                new QueryWrapper<DisciplineUrge>().eq("case_id", c3.getId()));
        assertEquals(1, urges.size());
        assertEquals("承办张", urges.get(0).getRecipient());

        // 未到间隔不重复催
        assertEquals(0, urgeService.sweep().get("urgedCases"));

        // 间隔已过（把上轮催办时间提前 25 小时）：第二轮升级催办
        urged.setLastUrgedAt(LocalDateTime.now().minusHours(25));
        caseMapper.updateById(urged);
        assertEquals(1, urgeService.sweep().get("urgedCases"));
        assertEquals(2, caseService.requireCase(c3.getId()).getUrgedCount());

        // 出决定后不再催办
        CaseDecisionRequest decision = new CaseDecisionRequest();
        decision.setDecisionType("WARNING");
        decision.setDecisionContent("情节较轻，给予警告。");
        runAs("承办张", () -> caseService.decide(c3.getId(), decision));
        caseMapper.updateById(caseService.requireCase(c3.getId()));
        assertEquals(0, urgeService.sweep().get("urgedCases"));
    }

    @Test
    void studentView_isMasked_andSealGuards() {
        ensureBase();
        DisciplineCase c4 = report("D004", "PROHIBITED", "承办张", "监考王");
        List<StudentCaseVO> vos = myCasesAs("D004");
        assertEquals(1, vos.size());
        StudentCaseVO vo = vos.get(0);
        assertEquals(c4.getCaseNo(), vo.getCaseNo());
        assertEquals("赵*", vo.getMaskedName());
        assertEquals(DisciplineConstants.CASE_REPORTED, vo.getStatus());
        assertNull(vo.getDecisionContent());
        assertEquals(1, vo.getTimeline().size());
        // VO 本身不含证件号/上报人/承办人等内部字段
        assertFalse(java.util.Arrays.stream(StudentCaseVO.class.getDeclaredFields())
                .anyMatch(f -> "idNo".equals(f.getName()) || "reporter".equals(f.getName())
                        || "assignee".equals(f.getName())));

        // 查别人的案子按查无处理，不暴露存在性
        BusinessException notFound = assertThrows(BusinessException.class,
                () -> {
                    MockHttpServletRequest request = new MockHttpServletRequest();
                    request.addHeader("X-Self-Code", "R999");
                    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
                    try {
                        caseService.studentByCaseNo("R999", c4.getCaseNo());
                    } finally {
                        RequestContextHolder.resetRequestAttributes();
                    }
                });
        assertEquals("CASE_NOT_FOUND", notFound.getCode());

        // 未结案（REPORTED）不能封存
        runAs("档案员", () -> {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> caseService.seal(c4.getId(), null));
            assertEquals("SEAL_STATUS_INVALID", ex.getCode());
        });
    }

    @Test
    void snapshotRollback_disciplineTablesRestored() {
        ensureBase();
        DisciplineCase c5 = report("D005", "OTHER", "承办张", "监考王");
        com.evops.exam.entity.WorkspaceVersion snapshot =
                workspaceVersionService.createSnapshot("违纪快照", null);
        long afterSnapshot = caseMapper.selectCount(null);

        // 快照后再立一案（用全新学员 R006）
        DisciplineCase extra = report("D006", "CHEAT", "承办张", "监考王");
        assertEquals(afterSnapshot + 1, caseMapper.selectCount(null));

        workspaceVersionService.rollback(snapshot.getVersionNo(), "回退验证");
        assertEquals(afterSnapshot, caseMapper.selectCount(null));
        assertNull(caseMapper.selectById(extra.getId()));
        assertNotNull(caseMapper.selectById(c5.getId()));
        // 证据/版本/催办配置一并还原
        assertTrue(evidenceCount(c5.getId()) >= 2);
        assertNotNull(workspaceVersionService.currentVersion());
    }

    private long evidenceCount(Long caseId) {
        return caseService.evidences(caseId).size();
    }

    @Test
    void revokeRejected_keepsDecision_andWritesBackLateScoreAfterReview() {
        ensureBase();
        // 孙八：决定成绩作废时成绩未导入（挂起），撤销复核期间成绩导入不回写，驳回后补回写
        DisciplineCase c6 = report("D006", "CHEAT", "承办张", "监考王");
        CaseDecisionRequest decision = new CaseDecisionRequest();
        decision.setDecisionType("SCORE_VOID");
        decision.setDecisionContent("认定作弊，成绩作废。");
        runAs("承办张", () -> {
            caseService.decide(c6.getId(), decision);
            caseService.execute(c6.getId(), null);
        });
        DisciplineConsequence cons = consequenceMapper.selectOne(
                new QueryWrapper<DisciplineConsequence>().eq("case_id", c6.getId()));
        assertEquals(DisciplineConstants.CONS_PENDING_SCORE, cons.getStatus());

        CaseRevokeRequest revoke = new CaseRevokeRequest();
        revoke.setReason("学员提出异议");
        runAs("监考王", () -> caseService.applyRevoke(c6.getId(), revoke));
        assertEquals(DisciplineConstants.CASE_REVOKE_PENDING, caseService.requireCase(c6.getId()).getStatus());

        // 复核期间晚到成绩：原决定暂停，不回写 → 成绩仍有效、后果仍挂起
        importScore("D006", 77);
        ExamScore during = scoreMapper.selectOne(new QueryWrapper<ExamScore>().eq("reg_no", "D006"));
        assertTrue(during.getValid());
        assertNull(during.getDisciplineCaseNo());
        assertEquals(DisciplineConstants.CONS_PENDING_SCORE,
                consequenceMapper.selectById(cons.getId()).getStatus());

        // 复核驳回（须非申请人、须填意见）：维持原决定，补回写作废，案件推进到已执行
        CaseRevokeReviewRequest reject = new CaseRevokeReviewRequest();
        reject.setApproved(false);
        reject.setComment("证据确凿，维持原处理");
        runAs("考务李", () -> caseService.reviewRevoke(c6.getId(), reject));
        DisciplineCase kept = caseService.requireCase(c6.getId());
        assertEquals(DisciplineConstants.CASE_EXECUTED, kept.getStatus());
        ExamScore after = scoreMapper.selectOne(new QueryWrapper<ExamScore>().eq("reg_no", "D006"));
        assertFalse(after.getValid());
        assertEquals(c6.getCaseNo(), after.getDisciplineCaseNo());
        assertEquals(DisciplineConstants.CONS_EXECUTED, consequenceMapper.selectById(cons.getId()).getStatus());

        List<String> changes = new ArrayList<>();
        caseService.versions(c6.getId()).forEach(v -> changes.add(v.getChangeType()));
        assertTrue(changes.contains(DisciplineConstants.CHANGE_REVOKE_APPLY));
        assertTrue(changes.contains(DisciplineConstants.CHANGE_REVOKE_REJECT));
        assertFalse(changes.contains(DisciplineConstants.CHANGE_REVOKE_APPROVE));
    }
}
