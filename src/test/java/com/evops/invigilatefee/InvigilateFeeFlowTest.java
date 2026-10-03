package com.evops.invigilatefee;

import com.evops.appoint.entity.Invigilator;
import com.evops.appoint.mapper.InvigilatorMapper;
import com.evops.common.BusinessException;
import com.evops.exam.entity.ExamBatch;
import com.evops.exam.entity.ExamSite;
import com.evops.exam.mapper.ExamBatchMapper;
import com.evops.exam.mapper.ExamSiteMapper;
import com.evops.invigilatefee.common.InvigilateFeeConstants;
import com.evops.invigilatefee.dto.FeeManualReviewRequest;
import com.evops.invigilatefee.dto.ModeTariff;
import com.evops.invigilatefee.dto.ReimbursementAdjustRequest;
import com.evops.invigilatefee.dto.ReimbursementApplyRequest;
import com.evops.invigilatefee.dto.ReceiptSubmitRequest;
import com.evops.invigilatefee.dto.RerouteRequest;
import com.evops.invigilatefee.dto.SiteDistanceRequest;
import com.evops.invigilatefee.dto.TravelRuleRequest;
import com.evops.invigilatefee.dto.TravelRuleVersionRequest;
import com.evops.invigilatefee.entity.ReimbursementAdjustment;
import com.evops.invigilatefee.entity.SiteDistance;
import com.evops.invigilatefee.entity.TravelAllowanceRule;
import com.evops.invigilatefee.entity.TravelAllowanceRuleVersion;
import com.evops.invigilatefee.entity.TravelReimbursement;
import com.evops.invigilatefee.entity.TravelReimbVersion;
import com.evops.invigilatefee.entity.TravelReceipt;
import com.evops.invigilatefee.entity.TravelRouteChange;
import com.evops.invigilatefee.service.ReimbursementFinanceService;
import com.evops.invigilatefee.service.SiteDistanceService;
import com.evops.invigilatefee.service.TravelReceiptService;
import com.evops.invigilatefee.service.TravelReimbursementService;
import com.evops.invigilatefee.service.TravelRuleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨区监考交通补贴端到端：
 * 规则版本按出行日期选取 → 路程+方式试算冻结 → 教师本人凭证/提交 → 异常转人工 → 财务确认锁定 → 调整单修订；
 * 并验证改派新路线重估、原申请只读留版本、本人/财务视图隔离。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class InvigilateFeeFlowTest {

    @Autowired private TravelRuleService ruleService;
    @Autowired private SiteDistanceService distanceService;
    @Autowired private TravelReimbursementService reimbService;
    @Autowired private TravelReceiptService receiptService;
    @Autowired private ReimbursementFinanceService financeService;
    @Autowired private ExamSiteMapper siteMapper;
    @Autowired private ExamBatchMapper batchMapper;
    @Autowired private InvigilatorMapper invigilatorMapper;

    private Long ruleId;
    private TravelAllowanceRuleVersion v1;
    private TravelAllowanceRuleVersion v2;
    private ExamBatch batchA;
    private ExamBatch batchB;

    @BeforeEach
    void setUp() {
        ExamSite siteA = site("SFA", "甲考点");
        ExamSite siteB = site("SFB", "乙考点");
        batchA = batch("BFA", siteA, LocalDate.of(2026, 3, 1));
        batchB = batch("BFB", siteB, LocalDate.of(2026, 9, 1));
        invigilator("T001", "张老师");
        invigilator("T002", "李老师");

        distance("HQ", "校本部", siteA, new BigDecimal("30"));
        distance("HQ", "校本部", siteB, new BigDecimal("60"));

        runAs("setup", () -> {
            TravelRuleRequest rr = new TravelRuleRequest();
            rr.setCode("TRDEF");
            rr.setName("交通补贴规则-默认");
            rr.setProjectCode("DEFAULT");
            TravelAllowanceRule rule = ruleService.createRule(rr);
            ruleId = rule.getId();

            // v1：2026 上半年，BUS 2 元/公里封顶 100（不强制票据）；TAXI 定额 50（强制票据）
            TravelRuleVersionRequest d1 = new TravelRuleVersionRequest();
            d1.setEffectiveFrom(LocalDate.of(2026, 1, 1));
            d1.setEffectiveTo(LocalDate.of(2026, 6, 30));
            d1.setDeviationThreshold(new BigDecimal("0.20"));
            d1.setTariffs(Arrays.asList(bus(new BigDecimal("2"), new BigDecimal("100"), false),
                    taxiFixed(new BigDecimal("50"), true)));
            v1 = ruleService.publishVersion(ruleService.createDraftVersion(ruleId, d1).getId());

            // v2：2026-07-01 起长期有效，BUS 3 元/公里封顶 120；TAXI 定额 60
            TravelRuleVersionRequest d2 = new TravelRuleVersionRequest();
            d2.setEffectiveFrom(LocalDate.of(2026, 7, 1));
            d2.setEffectiveTo(null);
            d2.setTariffs(Arrays.asList(bus(new BigDecimal("3"), new BigDecimal("120"), false),
                    taxiFixed(new BigDecimal("60"), true)));
            v2 = ruleService.publishVersion(ruleService.createDraftVersion(ruleId, d2).getId());
        });
    }

    // ==================== 规则版本按出行日期选取 + 试算 ====================

    @Test
    void ruleVersionSelectedByTravelDateAndEstimateFrozen() {
        TravelReimbursement march = applyAs("T001", batchA.getId(), "BUS", LocalDate.of(2026, 3, 1));
        assertEquals(v1.getVersionNo(), march.getRuleVersionNo());
        // 30km × 2 元 = 60
        assertEquals(0, new BigDecimal("60.00").compareTo(march.getEstimatedAmount()));
        assertEquals(0, new BigDecimal("30.00").compareTo(march.getDistanceKm()));

        TravelReimbursement sept = applyAs("T002", batchB.getId(), "BUS", LocalDate.of(2026, 9, 1));
        assertEquals(v2.getVersionNo(), sept.getRuleVersionNo());
        // 60km × 3 元 = 180，封顶 120
        assertEquals(0, new BigDecimal("120.00").compareTo(sept.getEstimatedAmount()));
        assertEquals(0, new BigDecimal("60.00").compareTo(sept.getDistanceKm()));
    }

    // ==================== 教师只能提交本人凭证；正常无票流 → 确认 ====================

    @Test
    void selfCanOnlyApplyAndReceiptForSelf_thenFinanceConfirms() {
        // 本人视图不能替他人申请
        BusinessException ex = assertThrows(BusinessException.class, () ->
                applyAs("T001", batchA.getId(), "BUS", LocalDate.of(2026, 3, 1), "T002"));
        assertEquals("FORBIDDEN_NOT_OWN", ex.getCode());

        TravelReimbursement r = applyAs("T001", batchA.getId(), "BUS", LocalDate.of(2026, 3, 1));
        assertEquals(InvigilateFeeConstants.REIMB_DRAFT, r.getStatus());

        // 教师本人视图无权确认
        BusinessException noConfirm = assertThrows(BusinessException.class,
                () -> asSelf("T001", () -> financeService.confirm(r.getId())));
        assertEquals("FINANCE_ONLY", noConfirm.getCode());

        // BUS 不强制票据：教师直接提交 → SUBMITTED
        TravelReimbursement submitted = asSelf("T001", () -> receiptService.submitForReview(r.getId()));
        assertEquals(InvigilateFeeConstants.REIMB_SUBMITTED, submitted.getStatus());
        assertNull(submitted.getReceiptAmount());

        // 财务确认锁定：无票按试算核定 60
        TravelReimbursement confirmed = runAs("finance", () -> financeService.confirm(r.getId()));
        assertEquals(InvigilateFeeConstants.REIMB_CONFIRMED, confirmed.getStatus());
        assertEquals(0, new BigDecimal("60.00").compareTo(confirmed.getApprovedAmount()));

        // 确认后不能改派
        BusinessException noReroute = assertThrows(BusinessException.class,
                () -> rerouteAs("T001", r.getId(), batchB.getId(), LocalDate.of(2026, 9, 1)));
        assertEquals("REIMBURSEMENT_LOCKED", noReroute.getCode());
    }

    // ==================== 票据强制 + 异常金额转人工 + 人工核定 ====================

    @Test
    void taxiReceiptRequiredAndAbnormalGoesManualThenApproved() {
        // TAXI 定额 50、强制票据；无票据提交被拒
        TravelReimbursement r = applyAs("T001", batchA.getId(), "TAXI", LocalDate.of(2026, 3, 1));
        assertEquals(0, new BigDecimal("50.00").compareTo(r.getEstimatedAmount()));
        BusinessException noReceipt = assertThrows(BusinessException.class,
                () -> asSelf("T001", () -> receiptService.submitForReview(r.getId())));
        assertEquals("RECEIPT_REQUIRED", noReceipt.getCode());

        // 提交本人票据 90（偏离 40 > 阈值 10）→ 异常转人工
        submitReceiptAs("T001", r.getId(), new BigDecimal("90"));
        TravelReimbursement submitted = asSelf("T001", () -> receiptService.submitForReview(r.getId()));
        assertEquals(InvigilateFeeConstants.REIMB_MANUAL_REVIEW, submitted.getStatus());
        assertNotNull(submitted.getAbnormalReason());

        // 财务人工清单能看到
        List<TravelReimbursement> queue = runAs("finance", () -> financeService.listManualQueue());
        assertTrue(queue.stream().anyMatch(q -> q.getId().equals(r.getId())));

        // 教师无权人工核定
        BusinessException teacherNo = assertThrows(BusinessException.class, () ->
                asSelf("T001", () -> financeService.manualReview(r.getId(), approve(true, new BigDecimal("55")))));
        assertEquals("FINANCE_ONLY", teacherNo.getCode());

        // 财务人工核定 55 放行 → SUBMITTED，再确认锁定
        TravelReimbursement reviewed = runAs("finance",
                () -> financeService.manualReview(r.getId(), approve(true, new BigDecimal("55"))));
        assertEquals(InvigilateFeeConstants.REIMB_SUBMITTED, reviewed.getStatus());
        assertEquals(0, new BigDecimal("55.00").compareTo(reviewed.getApprovedAmount()));
        TravelReimbursement confirmed = runAs("finance", () -> financeService.confirm(r.getId()));
        assertEquals(0, new BigDecimal("55.00").compareTo(confirmed.getApprovedAmount()));

        // 票据置已核入
        List<TravelReceipt> receipts = runAs("finance", () -> receiptService.listReceipts(r.getId()));
        assertEquals(InvigilateFeeConstants.RECEIPT_APPROVED, receipts.get(0).getStatus());
    }

    @Test
    void manualRejectRequiresReasonAndEndsRejected() {
        TravelReimbursement r = applyAs("T001", batchA.getId(), "TAXI", LocalDate.of(2026, 3, 1));
        submitReceiptAs("T001", r.getId(), new BigDecimal("90"));
        asSelf("T001", () -> receiptService.submitForReview(r.getId()));

        BusinessException noReason = assertThrows(BusinessException.class,
                () -> runAs("finance", () -> financeService.manualReview(r.getId(), approve(false, null))));
        assertEquals("MANUAL_REJECT_REASON_REQUIRED", noReason.getCode());

        FeeManualReviewRequest reject = approve(false, null);
        reject.setComment("票据与出行不符");
        TravelReimbursement rejected = runAs("finance", () -> financeService.manualReview(r.getId(), reject));
        assertEquals(InvigilateFeeConstants.REIMB_REJECTED, rejected.getStatus());
    }

    // ==================== 确认后调整单修订（可正可负） ====================

    @Test
    void confirmedReimbursementRevisedOnlyByAdjustment() {
        TravelReimbursement r = applyAs("T001", batchA.getId(), "BUS", LocalDate.of(2026, 3, 1));
        asSelf("T001", () -> receiptService.submitForReview(r.getId()));
        runAs("finance", () -> financeService.confirm(r.getId()));

        // 未... 已确认才能调整：对草稿/待确认单调整会被拒（此处已确认）
        ReimbursementAdjustment plus = runAs("finance",
                () -> financeService.adjust(r.getId(), adjust(new BigDecimal("5"), "漏报过桥费")));
        assertEquals(0, new BigDecimal("5.00").compareTo(plus.getAdjustAmount()));
        ReimbursementAdjustment minus = runAs("finance",
                () -> financeService.adjust(r.getId(), adjust(new BigDecimal("-3"), "核减")));

        TravelReimbursement locked = reimbService.requireReimbursement(r.getId());
        assertEquals(2, locked.getAdjustCount());
        assertEquals(0, new BigDecimal("62.00").compareTo(reimbService.payable(locked))); // 60 + 5 - 3

        List<ReimbursementAdjustment> ledger = runAs("finance", () -> financeService.adjustments(r.getId()));
        assertEquals(2, ledger.size());
        assertEquals(0, new BigDecimal("-3.00").compareTo(minus.getAdjustAmount()));
    }

    // ==================== 改派：新路线重估，原申请只读留版本，票据重交 ====================

    @Test
    void rerouteReEstimatesNewRouteAndKeepsOriginalReadOnly() {
        TravelReimbursement r = applyAs("T001", batchA.getId(), "BUS", LocalDate.of(2026, 3, 1));
        assertEquals(0, new BigDecimal("60.00").compareTo(r.getEstimatedAmount()));
        // 旧路线先交一张票据
        submitReceiptAs("T001", r.getId(), new BigDecimal("60"));

        // 改派到乙考点 9 月出行：v2，60km × 3 封顶 120
        TravelReimbursement rerouted = rerouteAs("T001", r.getId(), batchB.getId(),
                LocalDate.of(2026, 9, 1));
        assertEquals(InvigilateFeeConstants.REIMB_DRAFT, rerouted.getStatus());
        assertEquals(v2.getVersionNo(), rerouted.getRuleVersionNo());
        assertEquals(0, new BigDecimal("120.00").compareTo(rerouted.getEstimatedAmount()));
        assertEquals(batchB.getSiteId(), rerouted.getSiteId());
        assertEquals(2, rerouted.getCurrentVersion());
        assertNull(rerouted.getReceiptAmount());

        // 版本链：v1 CREATE 旧路线 60，v2 REROUTE 新路线 120（原申请只读保留）
        List<TravelReimbVersion> versions = runAs("finance", () -> reimbService.versions(r.getId()));
        assertEquals(2, versions.size());
        assertEquals(InvigilateFeeConstants.CHANGE_CREATE, versions.get(0).getChangeType());
        assertEquals(0, new BigDecimal("60.00").compareTo(versions.get(0).getEstimatedAmount()));
        assertEquals(InvigilateFeeConstants.CHANGE_REROUTE, versions.get(1).getChangeType());
        assertEquals(0, new BigDecimal("120.00").compareTo(versions.get(1).getEstimatedAmount()));
        assertNotNull(versions.get(1).getRouteChangeId());

        // 改派记录完整
        List<TravelRouteChange> changes = runAs("finance",
                () -> reimbService.routeChanges(null, r.getId()));
        assertEquals(1, changes.size());
        TravelRouteChange c = changes.get(0);
        assertEquals(batchA.getSiteId(), c.getFromSiteId());
        assertEquals(batchB.getSiteId(), c.getToSiteId());
        assertEquals(0, new BigDecimal("120.00").compareTo(c.getToEstimatedAmount()));

        // 旧路线票据不予核入新路线
        List<TravelReceipt> oldReceipts = runAs("finance", () -> receiptService.listReceipts(r.getId()));
        assertEquals(InvigilateFeeConstants.RECEIPT_REJECTED, oldReceipts.get(0).getStatus());

        // 新路线重新提交票据并走正常流程
        submitReceiptAs("T001", r.getId(), new BigDecimal("120"));
        TravelReimbursement submitted = asSelf("T001", () -> receiptService.submitForReview(r.getId()));
        assertEquals(InvigilateFeeConstants.REIMB_SUBMITTED, submitted.getStatus());
    }

    // ==================== 本人 / 财务视图隔离 ====================

    @Test
    void selfViewIsolatedFromFinanceAllView() {
        applyAs("T001", batchA.getId(), "BUS", LocalDate.of(2026, 3, 1));
        TravelReimbursement t2 = applyAs("T002", batchA.getId(), "BUS", LocalDate.of(2026, 3, 2));

        // 教师 T001 只看到自己
        List<TravelReimbursement> mine = asSelf("T001", () -> reimbService.list(null, null, null));
        assertEquals(1, mine.size());
        assertEquals("T001", mine.get(0).getInvigilatorCode());

        // 教师不能查看他人单
        BusinessException ex = assertThrows(BusinessException.class,
                () -> asSelf("T001", () -> reimbService.get(t2.getId())));
        assertEquals("FORBIDDEN_NOT_OWN", ex.getCode());

        // 财务全量看两张；财务列表拒绝本人视图
        List<TravelReimbursement> all = runAs("finance", () -> financeService.listAll(null, batchA.getId()));
        assertEquals(2, all.size());
        BusinessException financeOnly = assertThrows(BusinessException.class,
                () -> asSelf("T001", () -> financeService.listAll(null, null)));
        assertEquals("FINANCE_ONLY", financeOnly.getCode());
    }

    // ---------- 造数 / 请求上下文辅助 ----------

    private ExamSite site(String code, String name) {
        ExamSite s = new ExamSite();
        s.setCode(code);
        s.setName(name);
        s.setStatus("ENABLED");
        siteMapper.insert(s);
        return s;
    }

    private ExamBatch batch(String code, ExamSite site, LocalDate date) {
        ExamBatch b = new ExamBatch();
        b.setCode(code);
        b.setName("批次" + code);
        b.setSiteId(site.getId());
        b.setProjectCode("DEFAULT");
        b.setExamDate(date);
        b.setStatus("CREATED");
        batchMapper.insert(b);
        return b;
    }

    private void invigilator(String code, String name) {
        Invigilator inv = new Invigilator();
        inv.setCode(code);
        inv.setName(name);
        inv.setRoleCode("INVIGILATOR");
        inv.setStaffKind("PART_TIME");
        inv.setStatus("ENABLED");
        invigilatorMapper.insert(inv);
    }

    private void distance(String originCode, String originName, ExamSite site, BigDecimal km) {
        runAs("setup", () -> {
            SiteDistanceRequest d = new SiteDistanceRequest();
            d.setOriginCode(originCode);
            d.setOriginName(originName);
            d.setSiteId(site.getId());
            d.setDistanceKm(km);
            SiteDistance saved = distanceService.create(d);
            assertNotNull(saved.getId());
        });
    }

    private ModeTariff bus(BigDecimal rate, BigDecimal cap, boolean receiptRequired) {
        ModeTariff t = new ModeTariff();
        t.setTransportMode("BUS");
        t.setCalcMode("DISTANCE");
        t.setPerKmRate(rate);
        t.setCapAmount(cap);
        t.setMinDistance(BigDecimal.ZERO);
        t.setReceiptRequired(receiptRequired);
        return t;
    }

    private ModeTariff taxiFixed(BigDecimal fixed, boolean receiptRequired) {
        ModeTariff t = new ModeTariff();
        t.setTransportMode("TAXI");
        t.setCalcMode("FIXED");
        t.setFixedAmount(fixed);
        t.setReceiptRequired(receiptRequired);
        return t;
    }

    private ReimbursementApplyRequest applyReq(Long batchId, String code, String mode, LocalDate date) {
        ReimbursementApplyRequest req = new ReimbursementApplyRequest();
        req.setBatchId(batchId);
        req.setInvigilatorCode(code);
        req.setOriginCode("HQ");
        req.setTransportMode(mode);
        req.setTravelDate(date);
        return req;
    }

    private TravelReimbursement applyAs(String selfCode, Long batchId, String mode, LocalDate date) {
        return applyAs(selfCode, batchId, mode, date, selfCode);
    }

    private TravelReimbursement applyAs(String selfCode, Long batchId, String mode, LocalDate date, String forCode) {
        return asSelf(selfCode, () -> reimbService.apply(applyReq(batchId, forCode, mode, date)));
    }

    private TravelReimbursement rerouteAs(String selfCode, Long id, Long toBatchId, LocalDate date) {
        RerouteRequest req = new RerouteRequest();
        req.setBatchId(toBatchId);
        req.setOriginCode("HQ");
        req.setTransportMode("BUS");
        req.setTravelDate(date);
        req.setReason("跨区改派");
        return asSelf(selfCode, () -> reimbService.reroute(id, req));
    }

    private TravelReceipt submitReceiptAs(String selfCode, Long id, BigDecimal amount) {
        ReceiptSubmitRequest req = new ReceiptSubmitRequest();
        req.setReceiptType("INVOICE");
        req.setReceiptNo("R" + id + "-" + amount.toPlainString());
        req.setAmount(amount);
        return asSelf(selfCode, () -> receiptService.submit(id, req));
    }

    private FeeManualReviewRequest approve(boolean approved, BigDecimal amount) {
        FeeManualReviewRequest req = new FeeManualReviewRequest();
        req.setApproved(approved);
        req.setApprovedAmount(amount);
        return req;
    }

    private ReimbursementAdjustRequest adjust(BigDecimal amount, String reason) {
        ReimbursementAdjustRequest req = new ReimbursementAdjustRequest();
        req.setAdjustAmount(amount);
        req.setReason(reason);
        return req;
    }

    private void runAs(String operator, Runnable action) {
        withHeaders(operator, null, action);
    }

    private <T> T runAs(String operator, Supplier<T> action) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Operator", operator);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            return action.get();
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    private <T> T asSelf(String selfCode, Supplier<T> action) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Operator", selfCode);
        request.addHeader("X-Self-Code", selfCode);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            return action.get();
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    private void withHeaders(String operator, String selfCode, Runnable action) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Operator", operator);
        if (selfCode != null) {
            request.addHeader("X-Self-Code", selfCode);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            action.run();
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }
}
