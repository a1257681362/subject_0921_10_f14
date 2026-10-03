package com.evops.invigilatefee;

import com.evops.appoint.dto.InvigilatorRequest;
import com.evops.appoint.service.MasterDataService;
import com.evops.common.BusinessException;
import com.evops.exam.entity.ExamBatch;
import com.evops.exam.entity.ExamSite;
import com.evops.exam.mapper.ExamBatchMapper;
import com.evops.exam.mapper.ExamSiteMapper;
import com.evops.exam.service.WorkspaceVersionService;
import com.evops.invigilatefee.dto.FeeAdjustRequest;
import com.evops.invigilatefee.dto.FeeClaimSubmitRequest;
import com.evops.invigilatefee.dto.FeeDistanceRequest;
import com.evops.invigilatefee.dto.FeeManualReviewRequest;
import com.evops.invigilatefee.dto.FeeModeRate;
import com.evops.invigilatefee.dto.FeeReassignRequest;
import com.evops.invigilatefee.dto.FeeRegionRequest;
import com.evops.invigilatefee.dto.FeeRejectRequest;
import com.evops.invigilatefee.dto.FeeRuleRequest;
import com.evops.invigilatefee.dto.FeeRuleVersionRequest;
import com.evops.invigilatefee.entity.FeeClaim;
import com.evops.invigilatefee.entity.FeeTransportRuleVersion;
import com.evops.invigilatefee.mapper.FeeClaimMapper;
import com.evops.invigilatefee.mapper.FeeTransportRuleVersionMapper;
import com.evops.invigilatefee.service.FeeClaimService;
import com.evops.invigilatefee.service.FeeDistanceService;
import com.evops.invigilatefee.service.FeeRuleService;
import com.evops.invigilatefee.vo.FeeClaimPreview;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨区监考交通补贴报销端到端：
 * 主数据（区域/距离）→ 规则模块/版本/发布（按出行日期选版本）→ 教师本人提交（票据取小/封顶）
 * → 本人/财务两视图 → 财务确认只读、修订走调整单 → 异常偏差/超上限自动转人工（放行确认/驳回留意见）
 * → 改派原单冻结、新路线重估补票 → 工作区快照回退。
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InvigilateFeeFlowTest {

    @Autowired private FeeDistanceService distanceService;
    @Autowired private FeeRuleService ruleService;
    @Autowired private FeeClaimService claimService;
    @Autowired private MasterDataService masterData;
    @Autowired private ExamSiteMapper siteMapper;
    @Autowired private ExamBatchMapper batchMapper;
    @Autowired private FeeTransportRuleVersionMapper ruleVersionMapper;
    @Autowired private FeeClaimMapper claimMapper;
    @Autowired private WorkspaceVersionService workspaceVersionService;

    private static final LocalDate DATE_A = LocalDate.of(2026, 9, 1);
    private static final LocalDate DATE_B = LocalDate.of(2026, 10, 15);

    private ExamSite site(String code) {
        ExamSite s = new ExamSite();
        s.setCode(code);
        s.setName("考点-" + code);
        s.setStatus("ENABLED");
        siteMapper.insert(s);
        return s;
    }

    private ExamBatch batch(String code, ExamSite s, LocalDate examDate) {
        ExamBatch b = new ExamBatch();
        b.setCode(code);
        b.setName("批次-" + code);
        b.setSiteId(s.getId());
        b.setProjectCode("DEFAULT");
        b.setExamDate(examDate);
        b.setStatus("CREATED");
        batchMapper.insert(b);
        return b;
    }

    private void region(String code, String name) {
        FeeRegionRequest r = new FeeRegionRequest();
        r.setCode(code);
        r.setName(name);
        distanceService.createRegion(r);
    }

    private void distance(String regionCode, Long siteId, String km) {
        FeeDistanceRequest d = new FeeDistanceRequest();
        d.setRegionCode(regionCode);
        d.setSiteId(siteId);
        d.setDistanceKm(new BigDecimal(km));
        distanceService.createDistance(d);
    }

    private void invigilator(String code) {
        InvigilatorRequest req = new InvigilatorRequest();
        req.setCode(code);
        req.setName("教师" + code);
        req.setRoleCode("INVIGILATOR");
        req.setStaffKind("PART_TIME");
        masterData.createInvigilator(req);
    }

    private Long createRule() {
        FeeRuleRequest rule = new FeeRuleRequest();
        rule.setCode("JT-RULE");
        rule.setName("跨区交通补贴规则");
        rule.setProjectCode("DEFAULT");
        return ruleService.createRule(rule).getId();
    }

    private Long draftVersion(Long ruleId, String price, String cap, String anomalyRate, String limit) {
        FeeModeRate car = new FeeModeRate();
        car.setTransportMode("CAR");
        car.setPricePerKm(new BigDecimal(price));
        car.setCapPerTrip(cap == null ? null : new BigDecimal(cap));
        FeeModeRate train = new FeeModeRate();
        train.setTransportMode("TRAIN");
        train.setPricePerKm(new BigDecimal(price));
        train.setCapPerTrip(cap == null ? null : new BigDecimal(cap));
        FeeRuleVersionRequest v = new FeeRuleVersionRequest();
        v.setModes(Arrays.asList(car, train));
        v.setAnomalyRate(new BigDecimal(anomalyRate));
        if (limit != null) {
            v.setAnomalyAmountLimit(new BigDecimal(limit));
        }
        return ruleService.createDraftVersion(ruleId, v).getId();
    }

    /** 发布后把生效起点回溯到指定时刻（模拟该时刻起生效的历史版本）。 */
    private void publishAt(Long versionId, LocalDateTime publishedAt) {
        ruleService.publishVersion(versionId);
        FeeTransportRuleVersion upd = new FeeTransportRuleVersion();
        upd.setPublishedAt(publishedAt);
        ruleVersionMapper.update(upd, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<FeeTransportRuleVersion>()
                .eq("id", versionId));
        assertNotNull(ruleVersionMapper.selectById(versionId).getPublishedAt());
    }

    private FeeClaimSubmitRequest.FeeReceiptItem receipt(String amount) {
        FeeClaimSubmitRequest.FeeReceiptItem r = new FeeClaimSubmitRequest.FeeReceiptItem();
        r.setTransportMode("CAR");
        r.setAmount(new BigDecimal(amount));
        return r;
    }

    private FeeClaim submitAsFinance(ExamBatch b, String regionCode, String mode, LocalDate travelDate,
                                     String invigilatorCode, String... receiptAmounts) {
        FeeClaimSubmitRequest req = new FeeClaimSubmitRequest();
        req.setBatchId(b.getId());
        req.setRegionCode(regionCode);
        req.setTransportMode(mode);
        req.setTravelDate(travelDate);
        req.setInvigilatorCode(invigilatorCode);
        req.setReceipts(new java.util.ArrayList<>());
        for (String a : receiptAmounts) {
            req.getReceipts().add(receipt(a));
        }
        return claimService.submit(req);
    }

    private void asSelf(String code, Runnable action) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Self-Code", code);
        request.addHeader("X-Operator", code);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            action.run();
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void fullFlow_versionByDate_selfScope_confirmAdjust_anomalyManual_reassign_restore() {
        // ---------- 主数据：考点 / 批次 / 区域 / 距离 / 教师 ----------
        ExamSite siteA = site("FSITE-A");
        ExamSite siteB = site("FSITE-B");
        ExamBatch batchA = batch("FB2026A", siteA, DATE_A);
        ExamBatch batchB = batch("FB2026B", siteB, DATE_B);
        region("R1", "市区");
        region("R2", "远郊");
        distance("R1", siteA.getId(), "50");
        distance("R2", siteA.getId(), "200");
        distance("R1", siteB.getId(), "300");
        invigilator("T001");
        invigilator("T999");

        // ---------- 规则：V1 单价 1 元 / 封顶 80 / 偏差 20% / 上限 500，发布并回溯生效区间 ----------
        Long ruleId = createRule();
        Long v1 = draftVersion(ruleId, "1", "80", "0.20", "500");
        publishAt(v1, LocalDateTime.of(2026, 8, 1, 0, 0));

        // 无生效版本日期报错（2026-07-01 早于发布）
        FeeClaimSubmitRequest tooEarly = new FeeClaimSubmitRequest();
        tooEarly.setBatchId(batchA.getId());
        tooEarly.setRegionCode("R1");
        tooEarly.setTransportMode("CAR");
        tooEarly.setTravelDate(LocalDate.of(2026, 7, 1));
        tooEarly.setInvigilatorCode("T001");
        tooEarly.setReceipts(Collections.singletonList(receipt("50")));
        BusinessException ex = assertThrows(BusinessException.class, () -> claimService.preview(tooEarly));
        assertEquals("FEE_NO_ACTIVE_VERSION_ON_DATE", ex.getCode());

        // ---------- 1) 教师本人提交：里程 50×1=50（未触顶），票面 50，应付 50，正常 ----------
        FeeClaim c1;
        final FeeClaimSubmitRequest[] selfReq = new FeeClaimSubmitRequest[1];
        FeeClaimPreview[] selfPreview = new FeeClaimPreview[1];
        asSelf("T001", () -> {
            FeeClaimSubmitRequest req = new FeeClaimSubmitRequest();
            req.setBatchId(batchA.getId());
            req.setRegionCode("R1");
            req.setTransportMode("CAR");
            req.setTravelDate(DATE_A);
            // 本人视图报文冒填他人编码，应被请求头强制覆盖为 T001
            req.setInvigilatorCode("T999");
            req.setReceipts(Collections.singletonList(receipt("50")));
            selfReq[0] = req;
            selfPreview[0] = claimService.preview(req);
        });
        assertEquals("T001", selfPreview[0].getInvigilatorCode());
        assertEquals(0, new BigDecimal("50").compareTo(selfPreview[0].getEstimate().getPayableAmount()));
        assertEquals("SUBMITTED", selfPreview[0].getNextStatus());
        c1 = asSelfSubmit(selfReq[0]);
        assertEquals("T001", c1.getInvigilatorCode());
        assertEquals("SUBMITTED", c1.getStatus());
        assertEquals(0, new BigDecimal("50").compareTo(c1.getPayableAmount()));
        assertEquals(ruleService.getVersion(v1).getVersionNo(), c1.getRuleVersionNo());
        assertEquals(1, claimService.receipts(c1.getId()).size());

        // ---------- 2) 本人 / 财务视图隔离 ----------
        final Long c1Id = c1.getId();
        asSelf("T001", () -> {
            assertEquals(1, claimService.list(null, null, null).size());
            assertEquals(c1Id, claimService.get(c1Id).getId());
            // 教师本人不能做财务确认
            BusinessException e = assertThrows(BusinessException.class, () -> claimService.confirm(c1Id));
            assertEquals("FEE_FINANCE_ONLY", e.getCode());
        });
        asSelf("T999", () -> {
            // 他人不能看 T001 的单（不暴露存在性）
            assertEquals(0, claimService.list(null, null, null).size());
            BusinessException e = assertThrows(BusinessException.class, () -> claimService.get(c1Id));
            assertEquals("FORBIDDEN_NOT_OWN", e.getCode());
        });
        // 财务全量看得到
        assertEquals(1, claimService.list(null, null, null).size());

        // ---------- 3) 财务确认 → 只读；修订走调整单 ----------
        FeeClaim confirmed = claimService.confirm(c1Id);
        assertEquals("CONFIRMED", confirmed.getStatus());
        assertNotNull(confirmed.getConfirmedAt());
        assertThrows(BusinessException.class, () -> claimService.confirm(c1Id));
        // 未...已确认单不能驳回
        BusinessException rejectOnConfirmed = assertThrows(BusinessException.class,
                () -> claimService.reject(c1Id, new FeeRejectRequest() {{ setReason("x"); }}));
        assertEquals("FEE_CLAIM_REJECT_STATUS_INVALID", rejectOnConfirmed.getCode());

        FeeAdjustRequest zero = new FeeAdjustRequest();
        zero.setAdjustAmount(BigDecimal.ZERO);
        zero.setReason("零额");
        assertThrows(BusinessException.class, () -> claimService.adjust(c1Id, zero));

        FeeAdjustRequest adj = new FeeAdjustRequest();
        adj.setAdjustAmount(new BigDecimal("20"));
        adj.setReason("跨区过桥费补计");
        claimService.adjust(c1Id, adj);
        assertEquals(0, new BigDecimal("70").compareTo(claimService.get(c1Id).getPayableAmount()));
        assertEquals(1, claimService.adjustments(c1Id).size());
        // 版本链：SUBMIT → CONFIRM（调整不新增申请版本，只追加台账）
        assertEquals(2, claimService.versions(c1Id).size());

        // ---------- 4) 异常：票面远超封顶且偏差超阈值 → MANUAL_REVIEW ----------
        FeeClaim c2 = submitAsFinance(batchA, "R2", "CAR", DATE_A, "T001", "200");
        // 200km×1=200 触顶 80；票面 200，偏差 |200-80|/80=150%>20%
        assertEquals("MANUAL_REVIEW", c2.getStatus());
        assertTrue(c2.getAbnormal());
        assertEquals(0, new BigDecimal("80").compareTo(c2.getEstimatedAmount()));
        assertEquals(0, new BigDecimal("80").compareTo(c2.getPayableAmount()));
        BusinessException confirmManual = assertThrows(BusinessException.class, () -> claimService.confirm(c2.getId()));
        assertEquals("FEE_CLAIM_CONFIRM_STATUS_INVALID", confirmManual.getCode());

        // 人工放行（无意见也可）→ 直接确认
        FeeManualReviewRequest approve = new FeeManualReviewRequest();
        approve.setApproved(true);
        FeeClaim c2Done = claimService.manualReview(c2.getId(), approve);
        assertEquals("CONFIRMED", c2Done.getStatus());

        // ---------- 5) 异常：超绝对上限 → 人工驳回（驳回必须留意见） ----------
        FeeClaim c3 = submitAsFinance(batchA, "R2", "CAR", DATE_A, "T001", "600");
        assertEquals("MANUAL_REVIEW", c3.getStatus());
        FeeManualReviewRequest rejectNoComment = new FeeManualReviewRequest();
        rejectNoComment.setApproved(false);
        BusinessException noComment = assertThrows(BusinessException.class,
                () -> claimService.manualReview(c3.getId(), rejectNoComment));
        assertEquals("FEE_MANUAL_REJECT_COMMENT_REQUIRED", noComment.getCode());
        FeeManualReviewRequest reject = new FeeManualReviewRequest();
        reject.setApproved(false);
        reject.setComment("票面与里程严重不符，不予报销");
        FeeClaim c3Rejected = claimService.manualReview(c3.getId(), reject);
        assertEquals("REJECTED", c3Rejected.getStatus());
        assertEquals("票面与里程严重不符，不予报销", c3Rejected.getReviewComment());

        // ---------- 6) 规则升级 V2：单价 2 元 / 封顶 200，2026-10-01 起生效；历史日期仍取 V1 ----------
        Long v2 = draftVersion(ruleId, "2", "200", "0.20", "500");
        publishAt(v2, LocalDateTime.of(2026, 10, 1, 0, 0));
        // V1 被自动 DISABLED（disabledAt 为发布当天），把停用日修正为 2026-10-01，形成 [08-01,10-01) 口径
        FeeTransportRuleVersion v1Off = new FeeTransportRuleVersion();
        v1Off.setDisabledAt(LocalDateTime.of(2026, 10, 1, 0, 0));
        ruleVersionMapper.update(v1Off, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<FeeTransportRuleVersion>()
                .eq("id", v1));

        // 出行 2026-09-15 仍命中 V1（1 元）：50km → 50
        FeeClaimPreview sep = previewAsFinance(batchA, "R1", "CAR", LocalDate.of(2026, 9, 15), "T001", "50");
        assertEquals(ruleService.getVersion(v1).getVersionNo(), sep.getEstimate().getRuleVersionNo());
        assertEquals(0, new BigDecimal("50").compareTo(sep.getEstimate().getEstimatedAmount()));
        // 出行 2026-10-15 命中 V2（2 元）：50km → 100
        FeeClaimPreview oct = previewAsFinance(batchA, "R1", "CAR", LocalDate.of(2026, 10, 15), "T001", "100");
        assertEquals(ruleService.getVersion(v2).getVersionNo(), oct.getEstimate().getRuleVersionNo());
        assertEquals(0, new BigDecimal("100").compareTo(oct.getEstimate().getEstimatedAmount()));

        // ---------- 7) 改派：原单冻结只读，新路线（siteB 300km）重估，先 DRAFT 待补票 ----------
        FeeClaim c4 = submitAsFinance(batchA, "R1", "CAR", DATE_A, "T001", "50");
        assertEquals("SUBMITTED", c4.getStatus());
        FeeReassignRequest reassign = new FeeReassignRequest();
        reassign.setNewBatchId(batchB.getId());
        reassign.setNewRegionCode("R1");
        reassign.setReason("考点调整，改派至 B 考点");
        FeeClaim fresh = claimService.reassign(c4.getId(), reassign);
        assertEquals("DRAFT", fresh.getStatus());
        assertEquals(c4.getId(), fresh.getReassignOfClaimId());
        FeeClaim originAfter = claimService.get(c4.getId());
        assertEquals("REASSIGNED", originAfter.getStatus());
        assertEquals(fresh.getId(), originAfter.getReassignedToClaimId());
        // 原单只读：确认 / 再改派 / 调整 都拒绝
        assertEquals("FEE_CLAIM_REJECT_STATUS_INVALID",
                assertThrows(BusinessException.class,
                        () -> claimService.reject(c4.getId(), new FeeRejectRequest() {{ setReason("x"); }}))
                        .getCode());
        assertEquals("FEE_CLAIM_REASSIGN_STATUS_INVALID",
                assertThrows(BusinessException.class, () -> claimService.reassign(c4.getId(), reassign)).getCode());
        // 新单：2026-10-15 取 V2，300km×2=600 触顶 200；尚未补票应付 0
        assertEquals(ruleService.getVersion(v2).getVersionNo(), fresh.getRuleVersionNo());
        assertEquals(0, new BigDecimal("200").compareTo(fresh.getEstimatedAmount()));
        assertEquals(0, BigDecimal.ZERO.compareTo(fresh.getPayableAmount()));

        // 非本人不能补票
        asSelf("T999", () -> assertEquals("FORBIDDEN_NOT_OWN",
                assertThrows(BusinessException.class,
                        () -> claimService.submitReceipts(fresh.getId(), receiptOnlyReq("180"))).getCode()));
        // 本人补票：票面 180，估算 200，偏差 10% 不异常 → SUBMITTED，应付 180
        final FeeClaim[] freshDone = new FeeClaim[1];
        asSelf("T001", () -> freshDone[0] = claimService.submitReceipts(fresh.getId(), receiptOnlyReq("180")));
        assertEquals("SUBMITTED", freshDone[0].getStatus());
        assertEquals(0, new BigDecimal("180").compareTo(freshDone[0].getPayableAmount()));
        // 财务确认改派新单
        assertEquals("CONFIRMED", claimService.confirm(freshDone[0].getId()).getStatus());

        // 改派两单版本链：原单 SUBMIT→REASSIGN；新单 REASSIGN_NEW→SUBMIT→CONFIRM
        List<?> originVersions = claimService.versions(c4.getId());
        List<?> freshVersions = claimService.versions(freshDone[0].getId());
        assertEquals(2, originVersions.size());
        assertEquals(3, freshVersions.size());

        // ---------- 8) 工作区快照 / 回退：报销表随工作区一并还原 ----------
        long claimCountBefore = claimMapper.selectCount(null);
        String versionNo = workspaceVersionService.createSnapshot("报销快照", null).getVersionNo();
        workspaceVersionService.rollback(versionNo, "回退验证");
        assertEquals(claimCountBefore, claimMapper.selectCount(null));
        FeeClaim restoredFresh = claimService.get(freshDone[0].getId());
        assertEquals(0, new BigDecimal("180").compareTo(restoredFresh.getPayableAmount()));
        assertEquals("CONFIRMED", restoredFresh.getStatus());
        // 回退后新序列不撞号：再提一条可正常落库
        FeeClaim oneMore = submitAsFinance(batchA, "R1", "CAR", DATE_A, "T999", "50");
        assertNotNull(oneMore.getId());
        assertNull(oneMore.getReassignOfClaimId());
    }

    private FeeClaim asSelfSubmit(FeeClaimSubmitRequest req) {
        java.util.concurrent.atomic.AtomicReference<FeeClaim> ref = new java.util.concurrent.atomic.AtomicReference<>();
        asSelf("T001", () -> ref.set(claimService.submit(req)));
        return ref.get();
    }

    private FeeClaimPreview previewAsFinance(ExamBatch b, String regionCode, String mode, LocalDate date,
                                             String invigilatorCode, String receiptAmount) {
        FeeClaimSubmitRequest req = new FeeClaimSubmitRequest();
        req.setBatchId(b.getId());
        req.setRegionCode(regionCode);
        req.setTransportMode(mode);
        req.setTravelDate(date);
        req.setInvigilatorCode(invigilatorCode);
        req.setReceipts(Collections.singletonList(receipt(receiptAmount)));
        return claimService.preview(req);
    }

    private FeeClaimSubmitRequest receiptOnlyReq(String amount) {
        FeeClaimSubmitRequest req = new FeeClaimSubmitRequest();
        req.setReceipts(Collections.singletonList(receipt(amount)));
        return req;
    }
}
