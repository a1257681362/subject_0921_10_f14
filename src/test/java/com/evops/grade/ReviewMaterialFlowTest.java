package com.evops.grade;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.common.BusinessException;
import com.evops.exam.entity.ExamBatch;
import com.evops.exam.entity.ExamSite;
import com.evops.exam.entity.Registration;
import com.evops.exam.mapper.ExamBatchMapper;
import com.evops.exam.mapper.ExamSiteMapper;
import com.evops.exam.mapper.RegistrationMapper;
import com.evops.exam.service.WorkspaceVersionService;
import com.evops.grade.common.GradeConstants;
import com.evops.grade.dto.GradeBand;
import com.evops.grade.dto.GradeRuleRequest;
import com.evops.grade.dto.GradeRuleVersionRequest;
import com.evops.grade.dto.MaterialRegisterRequest;
import com.evops.grade.dto.MaterialReplaceRequest;
import com.evops.grade.dto.ReviewApplyRequest;
import com.evops.grade.dto.ReviewConclusionRequest;
import com.evops.grade.dto.ScoreImportItem;
import com.evops.grade.dto.ScoreImportRequest;
import com.evops.grade.entity.ExamScore;
import com.evops.grade.entity.ReviewMaterial;
import com.evops.grade.entity.ReviewMaterialRead;
import com.evops.grade.entity.ReviewMaterialVersion;
import com.evops.grade.entity.ScoreNotification;
import com.evops.grade.entity.ScoreReview;
import com.evops.grade.mapper.ExamScoreMapper;
import com.evops.grade.mapper.ReviewMaterialMapper;
import com.evops.grade.mapper.ScoreNotificationMapper;
import com.evops.grade.mapper.ScoreReviewMapper;
import com.evops.grade.service.GradeRuleService;
import com.evops.grade.service.ReviewExportService;
import com.evops.grade.service.ReviewMaterialService;
import com.evops.grade.service.ScoreNotificationService;
import com.evops.grade.service.ScoreReviewService;
import com.evops.grade.service.ScoreService;
import com.evops.grade.vo.ReviewSelfVO;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * grade-review-1 端到端：
 * 申请流转与材料阅读分离 / 材料按时间排列 / 附件替换留摘要 / 窗口关闭仅禁新受理历史可查 /
 * 学员只读本人 / 结论状态写明依据 / 通知回执挂回原申请 / 导出不带内部批注 / 快照回退带新表。
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReviewMaterialFlowTest {

    @Autowired private ScoreReviewService reviewService;
    @Autowired private ReviewMaterialService materialService;
    @Autowired private ScoreNotificationService notificationService;
    @Autowired private ReviewExportService exportService;
    @Autowired private ScoreService scoreService;
    @Autowired private GradeRuleService gradeRuleService;
    @Autowired private WorkspaceVersionService workspaceVersionService;
    @Autowired private ExamSiteMapper siteMapper;
    @Autowired private ExamBatchMapper batchMapper;
    @Autowired private RegistrationMapper registrationMapper;
    @Autowired private ExamScoreMapper scoreMapper;
    @Autowired private ScoreReviewMapper reviewMapper;
    @Autowired private ReviewMaterialMapper materialMapper;
    @Autowired private ScoreNotificationMapper notificationMapper;

    private static final String SECRET = "内部批注-绝密勿外泄";
    private ExamBatch batch;
    private ScoreReview reviewA;
    private ScoreReview reviewB;
    private ReviewMaterial matA1;
    private ReviewMaterial matA2;
    private ReviewMaterial matB1;

    private void ensureSeed() {
        // 幂等造数：JUnit 每方法新建实例但共享同一 Spring 上下文与文件外的内存库，且快照回退后仍可重建，
        // 因此一律按编码/报名号查库，缺什么补什么（与 DisciplineFlowTest 同一套路）。
        ExamSite site = siteMapper.selectOne(new QueryWrapper<ExamSite>().eq("code", "SGR"));
        if (site == null) {
            site = new ExamSite();
            site.setCode("SGR");
            site.setName("复核考点");
            site.setStatus("ENABLED");
            siteMapper.insert(site);
        }
        batch = batchMapper.selectOne(new QueryWrapper<ExamBatch>().eq("code", "BGR"));
        boolean fresh = batch == null;
        if (fresh) {
            batch = new ExamBatch();
            batch.setCode("BGR");
            batch.setName("复核批次");
            batch.setSiteId(site.getId());
            batch.setProjectCode("GRREV");
            batch.setStatus("CREATED");
            LocalDateTime now = LocalDateTime.now();
            batch.setReviewStartAt(now.minusDays(1));
            batch.setReviewEndAt(now.plusDays(1));
            batchMapper.insert(batch);
        }

        if (registrationMapper.selectOne(new QueryWrapper<Registration>().eq("reg_no", "GR001")) == null) {
            reg("GR001", "张三");
        }
        if (registrationMapper.selectOne(new QueryWrapper<Registration>().eq("reg_no", "GR002")) == null) {
            reg("GR002", "李四");
        }

        if (gradeRuleService.listRules("GRREV").stream().noneMatch(r -> "R-GR".equals(r.getCode()))) {
            GradeRuleRequest ruleReq = new GradeRuleRequest();
            ruleReq.setCode("R-GR");
            ruleReq.setName("复核测试规则");
            ruleReq.setProjectCode("GRREV");
            ruleReq.setMaxScore(100);
            Long ruleId = gradeRuleService.createRule(ruleReq).getId();

            GradeRuleVersionRequest verReq = new GradeRuleVersionRequest();
            GradeBand failBand = new GradeBand();
            failBand.setMinScore(new BigDecimal("0"));
            failBand.setMaxScore(new BigDecimal("60"));
            failBand.setGrade("不合格");
            GradeBand passBand = new GradeBand();
            passBand.setMinScore(new BigDecimal("60"));
            passBand.setMaxScore(new BigDecimal("100"));
            passBand.setGrade("合格");
            verReq.setBands(new ArrayList<>(Arrays.asList(failBand, passBand)));
            Long versionId = gradeRuleService.createDraftVersion(ruleId, verReq).getId();
            gradeRuleService.publishVersion(versionId);
        }

        if (scoreOf("GR001") == null) {
            importScore("GR001", new BigDecimal("55"));
        }
        if (scoreOf("GR002") == null) {
            importScore("GR002", new BigDecimal("70"));
        }

        reviewA = latestReview("GR001");
        reviewB = latestReview("GR002");
        if (fresh) {
            // 首次建批窗口期内提交；历史回放过窗口时申请已在库，直接复用
            if (reviewA == null) {
                reviewA = reviewService.apply(apply(scoreOf("GR001").getId()));
            }
            if (reviewB == null) {
                reviewB = reviewService.apply(apply(scoreOf("GR002").getId()));
            }
        }

        matA1 = ensureMaterial(reviewA.getId(), "A-申请表");
        matA2 = ensureMaterial(reviewA.getId(), "B-身份证扫描件");
        matB1 = ensureMaterial(reviewB.getId(), "A-申请表");
        if (materialService.getMaterial(matA1.getId()).getInternalAnnotation() == null) {
            materialService.annotate(matA1.getId(), SECRET);
        }
    }

    private ScoreReview latestReview(String regNo) {
        List<ScoreReview> list = reviewMapper.selectList(new QueryWrapper<ScoreReview>()
                .eq("reg_no", regNo).orderByDesc("applied_at", "id"));
        return list.isEmpty() ? null : list.get(0);
    }

    private ReviewMaterial ensureMaterial(Long reviewId, String title) {
        ReviewMaterial m = materialMapper.selectOne(new QueryWrapper<ReviewMaterial>()
                .eq("review_id", reviewId).eq("title", title));
        if (m != null) {
            return m;
        }
        return materialService.register(material(reviewId, title), "tester");
    }

    @Test
    @Order(1)
    void flow_and_reading_separated_ordered_then_replace_keeps_digest() {
        ensureSeed();
        // 1) 流转与材料阅读分离：审核岗与学员各读一次，复核单仍是 SUBMITTED，成绩仍 REVIEWING
        materialService.read(matA1.getId(), "auditor01", GradeConstants.READER_STAFF, null);
        materialService.read(matA1.getId(), "GR001", GradeConstants.READER_SELF, "GR001");
        List<ReviewMaterialRead> reads = materialService.listReads(matA1.getId());
        assertEquals(2, reads.size());
        assertEquals(GradeConstants.READER_STAFF, reads.get(0).getReaderKind());
        assertEquals(GradeConstants.READER_SELF, reads.get(1).getReaderKind());
        ScoreReview freshA = reviewService.getReview(reviewA.getId());
        assertEquals(GradeConstants.REVIEW_SUBMITTED, freshA.getStatus());
        assertEquals(GradeConstants.SCORE_REVIEWING, scoreService.getScore(freshA.getScoreId()).getStatus());

        // 2) 材料按进入时间排列（A-申请表 先于 B-身份证扫描件）
        List<ReviewMaterial> ordered = materialService.listByReview(reviewA.getId());
        assertEquals(2, ordered.size());
        assertEquals("A-申请表", ordered.get(0).getTitle());
        assertEquals("B-身份证扫描件", ordered.get(1).getTitle());

        // 3) 附件版本替换：旧版留摘要，新版为当前版
        MaterialReplaceRequest replace = new MaterialReplaceRequest();
        replace.setFileRef("oss://review/idcard-v2.jpg");
        replace.setContentHash("hash-v2");
        replace.setFileSize(2048L);
        replace.setOriginalFilename("idcard-v2.jpg");
        replace.setReason("首版扫描不清晰，学员补交清晰版");
        ReviewMaterialVersion v2 = materialService.replace(matA2.getId(), replace, "auditor01");
        assertEquals(2, v2.getVersionNo());
        assertEquals(GradeConstants.MATERIAL_ACTIVE, v2.getStatus());

        List<ReviewMaterialVersion> versions = materialService.listVersions(matA2.getId());
        assertEquals(2, versions.size());
        ReviewMaterialVersion v1 = versions.get(0);
        assertEquals(GradeConstants.MATERIAL_SUPERSEDED, v1.getStatus());
        assertNotNull(v1.getDigestSummary());
        assertEquals("首版扫描不清晰，学员补交清晰版", v1.getChangeReason());
        // 旧版正文/引用不删除（留摘要留档）
        assertNotNull(v1.getFileRef());

        ReviewMaterial replaced = materialService.getMaterial(matA2.getId());
        assertEquals(2, replaced.getCurrentVersion());
        assertEquals(GradeConstants.MATERIAL_ACTIVE, replaced.getStatus());

        // 学员视图看到两版摘要，且内部批注不出现在视图里
        ReviewSelfVO self = reviewService.selfView(reviewA.getId(), "GR001");
        ReviewSelfVO.MaterialSelfVO mvo = self.getMaterials().stream()
                .filter(m -> m.getMaterialId().equals(matA2.getId())).findFirst().orElseThrow(AssertionError::new);
        assertEquals(2, mvo.getVersions().size());
        assertEquals(GradeConstants.MATERIAL_SUPERSEDED, mvo.getVersions().get(0).getStatus());
        assertNotNull(mvo.getVersions().get(0).getDigestSummary());
    }

    @Test
    @Order(2)
    void conclusion_requires_basis_and_receipt_hangs_on_review() {
        ensureSeed();
        // 4) 审核岗修改结论状态必须写明依据：空依据拒收，状态不变
        ReviewConclusionRequest noBasis = new ReviewConclusionRequest();
        noBasis.setApproved(true);
        noBasis.setNewScore(new BigDecimal("88"));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> reviewService.conclude(reviewA.getId(), noBasis));
        assertEquals("REVIEW_CONCLUSION_BASIS_REQUIRED", ex.getCode());
        assertEquals(GradeConstants.REVIEW_SUBMITTED, reviewService.getReview(reviewA.getId()).getStatus());

        // 写明依据后通过改分
        ReviewConclusionRequest ok = new ReviewConclusionRequest();
        ok.setApproved(true);
        ok.setNewScore(new BigDecimal("88"));
        ok.setConclusion("经核验原卷第3、5题漏统，合计应加33分，依据阅卷评分细则改分");
        ScoreReview done = reviewService.conclude(reviewA.getId(), ok);
        assertEquals(GradeConstants.REVIEW_APPROVED, done.getStatus());
        assertEquals(0, new BigDecimal("88").compareTo(done.getScoreAfter()));

        // 5) 通知回执挂回原申请（reviewId）
        List<ScoreNotification> receipts = notificationService.listByReview(reviewA.getId());
        assertEquals(1, receipts.size());
        assertEquals(reviewA.getId(), receipts.get(0).getReviewId());
        assertTrue(receipts.get(0).getContent().contains("结论依据"));

        ReviewSelfVO self = reviewService.selfView(reviewA.getId(), "GR001");
        assertEquals(1, self.getReceipts().size());
        assertEquals(receipts.get(0).getId(), self.getReceipts().get(0).getNotificationId());

        // 学员标记自己的回执已读成功
        notificationService.markReadForSelf(receipts.get(0).getId(), "GR001");
        assertEquals(GradeConstants.NOTIFY_READ,
                notificationMapper.selectById(receipts.get(0).getId()).getStatus());
    }

    @Test
    @Order(3)
    void student_can_only_read_own_data() {
        ensureSeed();
        // A 看 B 的申请 → 统一按不存在处理，不暴露存在性
        BusinessException e1 = assertThrows(BusinessException.class,
                () -> reviewService.selfView(reviewB.getId(), "GR001"));
        assertEquals("REVIEW_NOT_FOUND", e1.getCode());

        // A 读 B 的材料 → 按不存在处理
        BusinessException e2 = assertThrows(BusinessException.class,
                () -> materialService.read(matB1.getId(), "GR001",
                        GradeConstants.READER_SELF, "GR001"));
        assertEquals("REVIEW_MATERIAL_NOT_FOUND", e2.getCode());

        // A 只能列出自己的申请
        List<ReviewSelfVO> mine = reviewService.selfList("GR001", null);
        assertEquals(1, mine.size());
        assertEquals(reviewA.getId(), mine.get(0).getId());
        assertEquals("张*", mine.get(0).getMaskedName());

        // A 不能把 B 的通知标已读（B 的窗口关闭通知在后面用例产生，这里先验 A 的通知不允许 B 操作）
        ScoreNotification aReceipt = notificationService.listByReview(reviewA.getId()).get(0);
        BusinessException e3 = assertThrows(BusinessException.class,
                () -> notificationService.markReadForSelf(aReceipt.getId(), "GR002"));
        assertEquals("NOTIFICATION_NOT_FOUND", e3.getCode());
    }

    @Test
    @Order(4)
    void window_close_blocks_new_intake_but_history_remains_readable() {
        ensureSeed();
        // 复核结论用例可能已把 A 处理掉；这里以 B（仍 SUBMITTED）验证窗口语义
        ScoreReview b = reviewService.getReview(reviewB.getId());
        assertEquals(GradeConstants.REVIEW_SUBMITTED, b.getStatus());

        // 关闭批次窗口
        LocalDateTime now = LocalDateTime.now();
        batch.setReviewEndAt(now.minusMinutes(1));
        batchMapper.updateById(batch);

        // 窗口关闭只禁止新受理：窗口校验先于重复校验，已有申请的 B 再提交也被窗口拒收
        ExamScore scoreB = scoreOf("GR002");
        assertEquals("REVIEW_WINDOW_CLOSED", assertThrows(BusinessException.class,
                () -> reviewService.apply(apply(scoreB.getId()))).getCode());

        // 另一名无申请的学员在关闭窗口提交同样被拒收
        reg("GR003", "王五");
        importScore("GR003", new BigDecimal("66"));
        BusinessException closed = assertThrows(BusinessException.class,
                () -> reviewService.apply(apply(scoreOf("GR003").getId())));
        assertEquals("REVIEW_WINDOW_CLOSED", closed.getCode());

        // 历史内容仍可查看：申请详情、学员视图、材料阅读都不受影响
        assertDoesNotThrow(() -> reviewService.getReview(reviewB.getId()));
        assertDoesNotThrow(() -> reviewService.selfView(reviewB.getId(), "GR002"));
        assertDoesNotThrow(() -> materialService.read(matB1.getId(), "GR002",
                GradeConstants.READER_SELF, "GR002"));

        // 超窗自动关闭：B 进 CLOSED 并发通知；关闭后历史依旧可查
        reviewService.closeExpired();
        ScoreReview closedB = reviewService.getReview(reviewB.getId());
        assertEquals(GradeConstants.REVIEW_CLOSED, closedB.getStatus());
        assertEquals(1, notificationService.listByReview(reviewB.getId()).size());
        assertDoesNotThrow(() -> reviewService.selfView(reviewB.getId(), "GR002"));
        assertDoesNotThrow(() -> materialService.read(matB1.getId(), "GR002",
                GradeConstants.READER_SELF, "GR002"));
    }

    @Test
    @Order(5)
    void exports_never_carry_internal_annotations() {
        ensureSeed();
        materialService.annotate(matA1.getId(), SECRET);

        String adminCsv = new String(exportService.exportBatch(batch.getId()), StandardCharsets.UTF_8);
        assertTrue(adminCsv.contains("结论依据"));
        assertTrue(adminCsv.contains("A-申请表"));
        assertFalse(adminCsv.contains(SECRET), "管理端导出不得带出内部批注");

        String selfCsv = new String(exportService.exportSelf("GR001"), StandardCharsets.UTF_8);
        assertTrue(selfCsv.contains("GR001"));
        assertTrue(selfCsv.contains("张*"));
        assertFalse(selfCsv.contains("张三"), "学员导出姓名应掩码");
        assertFalse(selfCsv.contains(SECRET), "学员导出不得带出内部批注");
    }

    @Test
    @Order(6)
    void snapshot_and_rollback_covers_review_materials() {
        ensureSeed();
        int beforeCount = materialMapper.selectCount(null).intValue();
        String versionNo = workspaceVersionService.createSnapshot("复核材料快照", "测试").getVersionNo();

        // 快照后新增材料并替换版本
        ReviewMaterial extra = materialService.register(material(reviewA.getId(), "C-补交说明"), "tester");
        MaterialReplaceRequest replace = new MaterialReplaceRequest();
        replace.setContent("更新后的补交说明正文");
        replace.setReason("补充更正");
        materialService.replace(extra.getId(), replace, "tester");
        assertEquals(beforeCount + 1, materialMapper.selectCount(null).intValue());

        workspaceVersionService.rollback(versionNo, "回退到材料新增前");
        assertEquals(beforeCount, materialMapper.selectCount(null).intValue(), "回退后新表应恢复为快照状态");
        assertNotNull(materialMapper.selectById(matA1.getId()), "快照内材料应原样写回");
        assertEquals(2, materialService.listVersions(matA2.getId()).size(),
                "快照发生在替换之后的版本链应保留；此断言针对快照时点已替换的材料");
        // 回退后重新置一个开放窗口，避免影响后续（本方法最后执行，无后续断言依赖窗口）
    }

    // ---------- helpers ----------

    private void reg(String regNo, String name) {
        Registration r = new Registration();
        r.setRegNo(regNo);
        r.setName(name);
        r.setBatchId(batch.getId());
        r.setStatus("REGISTERED");
        registrationMapper.insert(r);
    }

    private void importScore(String regNo, BigDecimal score) {
        ScoreImportRequest req = new ScoreImportRequest();
        ScoreImportItem item = new ScoreImportItem();
        item.setRegNo(regNo);
        item.setPaperScore(score);
        req.setBatchId(batch.getId());
        req.setScores(new ArrayList<>(java.util.Collections.singletonList(item)));
        scoreService.importScores(req);
    }

    private ExamScore scoreOf(String regNo) {
        return scoreMapper.selectOne(new QueryWrapper<ExamScore>().eq("reg_no", regNo));
    }

    private ReviewApplyRequest apply(Long scoreId) {
        ReviewApplyRequest req = new ReviewApplyRequest();
        req.setScoreId(scoreId);
        req.setReason("认为分数与自评不符，申请复核");
        return req;
    }

    private MaterialRegisterRequest material(Long reviewId, String title) {
        MaterialRegisterRequest m = new MaterialRegisterRequest();
        m.setReviewId(reviewId);
        m.setTitle(title);
        m.setMaterialType("FORM");
        m.setFileRef("oss://review/" + reviewId + "/" + title);
        m.setContentHash("hash-" + title);
        m.setFileSize(1024L);
        m.setOriginalFilename(title + ".jpg");
        m.setDigestSummary("文件：" + title + ".jpg；大小：1024 字节；摘要：hash-" + title);
        return m;
    }
}
