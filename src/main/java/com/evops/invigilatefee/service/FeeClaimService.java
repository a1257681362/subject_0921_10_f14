package com.evops.invigilatefee.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.appoint.entity.Invigilator;
import com.evops.appoint.service.MasterDataService;
import com.evops.common.BusinessException;
import com.evops.common.OperatorHolder;
import com.evops.exam.entity.ExamBatch;
import com.evops.exam.entity.ExamSite;
import com.evops.exam.mapper.ExamSiteMapper;
import com.evops.exam.service.ExamBatchService;
import com.evops.invigilatefee.common.FeeSelfHolder;
import com.evops.invigilatefee.common.InvigilateFeeConstants;
import com.evops.invigilatefee.dto.FeeAdjustRequest;
import com.evops.invigilatefee.dto.FeeClaimSubmitRequest;
import com.evops.invigilatefee.dto.FeeManualReviewRequest;
import com.evops.invigilatefee.dto.FeeReassignRequest;
import com.evops.invigilatefee.dto.FeeRejectRequest;
import com.evops.invigilatefee.entity.FeeClaim;
import com.evops.invigilatefee.entity.FeeClaimAdjustment;
import com.evops.invigilatefee.entity.FeeClaimVersion;
import com.evops.invigilatefee.entity.FeeDistance;
import com.evops.invigilatefee.entity.FeeReceipt;
import com.evops.invigilatefee.entity.FeeRegion;
import com.evops.invigilatefee.entity.FeeTransportRule;
import com.evops.invigilatefee.entity.FeeTransportRuleVersion;
import com.evops.invigilatefee.mapper.FeeClaimAdjustmentMapper;
import com.evops.invigilatefee.mapper.FeeClaimMapper;
import com.evops.invigilatefee.mapper.FeeClaimVersionMapper;
import com.evops.invigilatefee.mapper.FeeReceiptMapper;
import com.evops.invigilatefee.vo.FeeClaimPreview;
import com.evops.invigilatefee.vo.FeeEstimate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 跨区交通补贴报销申请：
 * - 教师只能提交本人凭证（X-Self-Code 强制归属），财务（不带本人头）看全量 / 代提交；
 * - 提交两步：preview 按批次考点 + 区域距离 + 出行日期选规则版本估算（不落库），submit 落库；
 *   应付 = min(里程估算（封顶后）, 票面合计)；异常（偏差超阈值 / 超上限 / 无票）自动转人工；
 * - 状态机：SUBMITTED 待审核 → CONFIRMED 确认只读（财务确认）；异常先进 MANUAL_REVIEW，人工放行即确认；
 *   REJECTED 驳回终结；CONFIRMED 后修订一律走调整单（只追加）；
 * - 改派：原单冻结 REASSIGNED（只读留版本），按新批次考点 / 区域 / 出行日期新建一条重新估算。
 */
@Service
public class FeeClaimService {

    private final FeeClaimMapper claimMapper;
    private final FeeClaimVersionMapper versionMapper;
    private final FeeReceiptMapper receiptMapper;
    private final FeeClaimAdjustmentMapper adjustmentMapper;
    private final FeeDistanceService distanceService;
    private final FeeRuleService ruleService;
    private final MasterDataService masterData;
    private final ExamBatchService batchService;
    private final ExamSiteMapper siteMapper;

    public FeeClaimService(FeeClaimMapper claimMapper,
                           FeeClaimVersionMapper versionMapper,
                           FeeReceiptMapper receiptMapper,
                           FeeClaimAdjustmentMapper adjustmentMapper,
                           FeeDistanceService distanceService,
                           FeeRuleService ruleService,
                           MasterDataService masterData,
                           ExamBatchService batchService,
                           ExamSiteMapper siteMapper) {
        this.claimMapper = claimMapper;
        this.versionMapper = versionMapper;
        this.receiptMapper = receiptMapper;
        this.adjustmentMapper = adjustmentMapper;
        this.distanceService = distanceService;
        this.ruleService = ruleService;
        this.masterData = masterData;
        this.batchService = batchService;
        this.siteMapper = siteMapper;
    }

    // ==================== 提交：预检 / 执行（两步） ====================

    @Transactional(readOnly = true)
    public FeeClaimPreview preview(FeeClaimSubmitRequest request) {
        ResolvedContext ctx = resolve(request);
        FeeEstimate estimate = calculate(ctx);

        FeeClaimPreview preview = new FeeClaimPreview();
        preview.setBatchId(ctx.batch.getId());
        preview.setBatchCode(ctx.batch.getCode());
        preview.setProjectCode(ctx.projectCode);
        preview.setSiteId(ctx.site.getId());
        preview.setSiteCode(ctx.site.getCode());
        preview.setSiteName(ctx.site.getName());
        preview.setInvigilatorCode(ctx.invigilator.getCode());
        preview.setInvigilatorName(ctx.invigilator.getName());
        preview.setRegionId(ctx.region.getId());
        preview.setRegionCode(ctx.region.getCode());
        preview.setRegionName(ctx.region.getName());
        preview.setTravelDate(ctx.travelDate);
        preview.setReceiptCount(ctx.receipts.size());
        preview.setReceiptTotal(estimate.getDeclaredAmount());
        preview.setEstimate(estimate);

        String nextStatus = estimate.isAbnormal()
                ? InvigilateFeeConstants.CLAIM_MANUAL_REVIEW
                : InvigilateFeeConstants.CLAIM_SUBMITTED;
        preview.setNextStatus(nextStatus);
        preview.setActionable(true);
        preview.setSummary(String.format(
                "批次 %s：%s 于 %s 自 %s 前往考点 %s，%s 里程 %s 公里，系统估算 %s 元，票面合计 %s 元，应付 %s 元 → %s",
                ctx.batch.getCode(), ctx.invigilator.getCode(), ctx.travelDate, ctx.region.getCode(),
                ctx.site.getCode(), estimate.getTransportMode(), estimate.getDistanceKm().toPlainString(),
                estimate.getEstimatedAmount().toPlainString(), estimate.getDeclaredAmount().toPlainString(),
                estimate.getPayableAmount().toPlainString(),
                estimate.isAbnormal() ? "金额异常转人工审核" : "待财务审核"));
        preview.getNotes().addAll(estimate.getAnomalyReasons());
        return preview;
    }

    @Transactional
    public FeeClaim submit(FeeClaimSubmitRequest request) {
        ResolvedContext ctx = resolve(request);
        FeeEstimate estimate = calculate(ctx);
        String operator = OperatorHolder.current();
        LocalDateTime now = LocalDateTime.now();

        boolean abnormal = estimate.isAbnormal();
        String status = abnormal ? InvigilateFeeConstants.CLAIM_MANUAL_REVIEW
                : InvigilateFeeConstants.CLAIM_SUBMITTED;

        FeeClaim claim = new FeeClaim();
        claim.setClaimNo(nextClaimNo(ctx.batch));
        claim.setBatchId(ctx.batch.getId());
        claim.setBatchCode(ctx.batch.getCode());
        claim.setProjectCode(ctx.projectCode);
        claim.setSiteId(ctx.site.getId());
        claim.setSiteCode(ctx.site.getCode());
        claim.setSiteName(ctx.site.getName());
        claim.setInvigilatorId(ctx.invigilator.getId());
        claim.setInvigilatorCode(ctx.invigilator.getCode());
        claim.setInvigilatorName(ctx.invigilator.getName());
        claim.setRegionId(ctx.region.getId());
        claim.setRegionCode(ctx.region.getCode());
        claim.setRegionName(ctx.region.getName());
        claim.setDistanceKm(estimate.getDistanceKm());
        claim.setTravelDate(ctx.travelDate);
        claim.setRuleId(ctx.rule.getId());
        claim.setRuleCode(ctx.rule.getCode());
        claim.setRuleVersionId(ctx.version.getId());
        claim.setRuleVersionNo(ctx.version.getVersionNo());
        claim.setTransportMode(estimate.getTransportMode());
        claim.setEstimatedAmount(estimate.getEstimatedAmount());
        claim.setDeclaredAmount(estimate.getDeclaredAmount());
        claim.setPayableAmount(estimate.getPayableAmount());
        claim.setCurrentVersion(1);
        claim.setStatus(status);
        claim.setAbnormal(abnormal);
        claim.setOperator(operator);
        claim.setRemark(request.getRemark());
        claimMapper.insert(claim);

        insertReceipts(claim, ctx.receipts, operator);
        // 提交首版：正常 SUBMITTED；异常即在首版快照标 MANUAL_REVIEW
        appendVersion(claim, 1, InvigilateFeeConstants.CHANGE_SUBMIT, status, estimate,
                abnormal ? "提交后金额异常，自动转人工：" + String.join("；", estimate.getAnomalyReasons())
                        : "教师提交本人交通补贴报销凭证",
                operator, now);
        return claim;
    }

    // ==================== 财务确认 / 驳回 / 人工审核 ====================

    /** 财务确认金额正常的报销：SUBMITTED → CONFIRMED，应付锁定只读。 */
    @Transactional
    public FeeClaim confirm(Long claimId) {
        requireFinance("确认报销");
        FeeClaim claim = requireClaim(claimId);
        String operator = OperatorHolder.current();
        LocalDateTime now = LocalDateTime.now();
        if (InvigilateFeeConstants.CLAIM_CONFIRMED.equals(claim.getStatus())) {
            throw new BusinessException("FEE_CLAIM_ALREADY_CONFIRMED",
                    "报销 " + claim.getClaimNo() + " 已确认，不能重复确认");
        }
        if (!InvigilateFeeConstants.CLAIM_SUBMITTED.equals(claim.getStatus())) {
            throw new BusinessException("FEE_CLAIM_CONFIRM_STATUS_INVALID",
                    "仅待财务审核（SUBMITTED）状态可确认，当前状态 " + claim.getStatus()
                            + "（异常单请走人工审核；驳回 / 已改派单不可确认）");
        }
        claim.setStatus(InvigilateFeeConstants.CLAIM_CONFIRMED);
        claim.setConfirmedBy(operator);
        claim.setConfirmedAt(now);
        claim.setOperator(operator);
        claim.setCurrentVersion(claim.getCurrentVersion() + 1);
        claimMapper.updateById(claim);
        appendVersion(claim, claim.getCurrentVersion(), InvigilateFeeConstants.CHANGE_CONFIRM,
                InvigilateFeeConstants.CLAIM_CONFIRMED, snapshotEstimate(claim),
                "财务确认，应付锁定（修订走调整单）", operator, now);
        return claim;
    }

    /** 财务 / 人工驳回：SUBMITTED / MANUAL_REVIEW → REJECTED（终结，只读），须填原因。 */
    @Transactional
    public FeeClaim reject(Long claimId, FeeRejectRequest request) {
        requireFinance("驳回报销");
        FeeClaim claim = requireClaim(claimId);
        String operator = OperatorHolder.current();
        LocalDateTime now = LocalDateTime.now();
        if (InvigilateFeeConstants.CLAIM_REJECTED.equals(claim.getStatus())) {
            throw new BusinessException("FEE_CLAIM_ALREADY_REJECTED", "报销 " + claim.getClaimNo() + " 已驳回");
        }
        if (InvigilateFeeConstants.CLAIM_CONFIRMED.equals(claim.getStatus())
                || InvigilateFeeConstants.CLAIM_REASSIGNED.equals(claim.getStatus())) {
            throw new BusinessException("FEE_CLAIM_REJECT_STATUS_INVALID",
                    "已确认 / 已改派的报销不能驳回，当前状态 " + claim.getStatus());
        }
        String reason = request.getReason().trim();
        claim.setStatus(InvigilateFeeConstants.CLAIM_REJECTED);
        claim.setReviewedBy(operator);
        claim.setReviewedAt(now);
        claim.setReviewComment(reason);
        claim.setClosedRemark(reason);
        claim.setOperator(operator);
        claim.setCurrentVersion(claim.getCurrentVersion() + 1);
        claimMapper.updateById(claim);
        appendVersion(claim, claim.getCurrentVersion(), InvigilateFeeConstants.CHANGE_REJECT,
                InvigilateFeeConstants.CLAIM_REJECTED, snapshotEstimate(claim), reason, operator, now);
        return claim;
    }

    /**
     * 异常单人工审核：仅 MANUAL_REVIEW。
     * approved=true 放行并直接确认（人工对金额负责，按当前应付锁定）；false 驳回（须填意见）。
     */
    @Transactional
    public FeeClaim manualReview(Long claimId, FeeManualReviewRequest request) {
        requireFinance("人工审核");
        FeeClaim claim = requireClaim(claimId);
        String operator = OperatorHolder.current();
        LocalDateTime now = LocalDateTime.now();
        if (!InvigilateFeeConstants.CLAIM_MANUAL_REVIEW.equals(claim.getStatus())) {
            throw new BusinessException("FEE_CLAIM_NOT_MANUAL",
                    "仅异常转人工（MANUAL_REVIEW）状态可人工审核，当前状态 " + claim.getStatus());
        }
        String comment = request.getComment() == null ? null : request.getComment().trim();
        if (!request.getApproved() && (comment == null || comment.isEmpty())) {
            throw new BusinessException("FEE_MANUAL_REJECT_COMMENT_REQUIRED", "人工驳回必须填写意见 / 依据");
        }

        int nextNo = claim.getCurrentVersion() + 1;
        if (request.getApproved()) {
            claim.setStatus(InvigilateFeeConstants.CLAIM_CONFIRMED);
            claim.setConfirmedBy(operator);
            claim.setConfirmedAt(now);
            claim.setReviewedBy(operator);
            claim.setReviewedAt(now);
            claim.setReviewComment(comment);
            claim.setOperator(operator);
            claim.setCurrentVersion(nextNo);
            claimMapper.updateById(claim);
            appendVersion(claim, nextNo, InvigilateFeeConstants.CHANGE_MANUAL_APPROVE,
                    InvigilateFeeConstants.CLAIM_CONFIRMED, snapshotEstimate(claim),
                    "人工审核放行并确认：" + (comment == null ? "人工核定金额无误" : comment), operator, now);
        } else {
            claim.setStatus(InvigilateFeeConstants.CLAIM_REJECTED);
            claim.setReviewedBy(operator);
            claim.setReviewedAt(now);
            claim.setReviewComment(comment);
            claim.setClosedRemark(comment);
            claim.setOperator(operator);
            claim.setCurrentVersion(nextNo);
            claimMapper.updateById(claim);
            appendVersion(claim, nextNo, InvigilateFeeConstants.CHANGE_REJECT,
                    InvigilateFeeConstants.CLAIM_REJECTED, snapshotEstimate(claim),
                    "人工审核驳回：" + comment, operator, now);
        }
        return claim;
    }

    // ==================== 改派：原单冻结，新路线重新估算 ====================

    /**
     * 改派：原申请追加 REASSIGN 版冻结为只读（REASSIGNED），
     * 按新批次考点 / 新区域 / 新出行日期 / 新交通方式新建一条申请重新估算并指向原单。
     * 请求可带新票据：带则新单直接进入待审核（异常转人工）；不带则新单先 DRAFT，待教师补票再提交。
     */
    @Transactional
    public FeeClaim reassign(Long claimId, FeeReassignRequest request) {
        requireFinance("办理改派");
        FeeClaim origin = requireClaim(claimId);
        if (InvigilateFeeConstants.CLAIM_CONFIRMED.equals(origin.getStatus())
                || InvigilateFeeConstants.CLAIM_REASSIGNED.equals(origin.getStatus())) {
            throw new BusinessException("FEE_CLAIM_REASSIGN_STATUS_INVALID",
                    "已确认 / 已改派的报销不能再改派，当前状态 " + origin.getStatus()
                            + "（已确认单如需冲销请走调整）");
        }
        if (origin.getReassignedToClaimId() != null) {
            throw new BusinessException("FEE_CLAIM_ALREADY_REASSIGNED",
                    "报销 " + origin.getClaimNo() + " 已改派，原申请保持只读");
        }
        String operator = OperatorHolder.current();
        LocalDateTime now = LocalDateTime.now();

        // 1) 原单冻结
        origin.setStatus(InvigilateFeeConstants.CLAIM_REASSIGNED);
        origin.setCurrentVersion(origin.getCurrentVersion() + 1);
        origin.setOperator(operator);
        origin.setClosedRemark(request.getReason().trim());
        claimMapper.updateById(origin);
        appendVersion(origin, origin.getCurrentVersion(), InvigilateFeeConstants.CHANGE_REASSIGN,
                InvigilateFeeConstants.CLAIM_REASSIGNED, snapshotEstimate(origin),
                "改派：原申请冻结只读，新路线重新估算。" + request.getReason().trim(), operator, now);

        // 2) 解析新路线并重新估算（教师沿用原申请人）
        Long newBatchId = request.getNewBatchId() == null ? origin.getBatchId() : request.getNewBatchId();
        ExamBatch newBatch = batchService.getBatch(newBatchId);
        ExamSite newSite = siteMapper.selectById(newBatch.getSiteId());
        if (newSite == null) {
            throw new BusinessException("SITE_NOT_FOUND", "新批次考点不存在：id=" + newBatch.getSiteId());
        }
        String newRegionCode = (request.getNewRegionCode() == null || request.getNewRegionCode().trim().isEmpty())
                ? origin.getRegionCode() : request.getNewRegionCode().trim();
        String newMode = (request.getNewTransportMode() == null || request.getNewTransportMode().trim().isEmpty())
                ? origin.getTransportMode() : request.getNewTransportMode().trim();
        LocalDate newTravelDate = request.getNewTravelDate() != null ? request.getNewTravelDate()
                : (newBatch.getExamDate() != null ? newBatch.getExamDate() : origin.getTravelDate());

        FeeRegion region = distanceService.requireEnabledRegion(newRegionCode);
        FeeDistance distance = distanceService.requireDistance(region, newSite.getId());
        String projectCode = newBatch.getProjectCode() == null
                ? InvigilateFeeConstants.PROJECT_DEFAULT : newBatch.getProjectCode();
        FeeTransportRuleVersion version = ruleService.requireActiveVersion(projectCode, newTravelDate);
        FeeTransportRule rule = ruleService.getRule(version.getRuleId());

        boolean hasReceipts = request.getNewReceipts() != null && !request.getNewReceipts().isEmpty();
        BigDecimal declaredTotal = hasReceipts ? sumReceipts(request.getNewReceipts()) : BigDecimal.ZERO;
        FeeEstimate estimate = ruleService.estimate(version, newMode, distance.getDistanceKm(), declaredTotal);

        FeeClaim fresh = new FeeClaim();
        fresh.setClaimNo(nextClaimNo(newBatch));
        fresh.setBatchId(newBatch.getId());
        fresh.setBatchCode(newBatch.getCode());
        fresh.setProjectCode(projectCode);
        fresh.setSiteId(newSite.getId());
        fresh.setSiteCode(newSite.getCode());
        fresh.setSiteName(newSite.getName());
        fresh.setInvigilatorId(origin.getInvigilatorId());
        fresh.setInvigilatorCode(origin.getInvigilatorCode());
        fresh.setInvigilatorName(origin.getInvigilatorName());
        fresh.setRegionId(region.getId());
        fresh.setRegionCode(region.getCode());
        fresh.setRegionName(region.getName());
        fresh.setDistanceKm(estimate.getDistanceKm());
        fresh.setTravelDate(newTravelDate);
        fresh.setRuleId(rule.getId());
        fresh.setRuleCode(rule.getCode());
        fresh.setRuleVersionId(version.getId());
        fresh.setRuleVersionNo(version.getVersionNo());
        fresh.setTransportMode(estimate.getTransportMode());
        fresh.setEstimatedAmount(estimate.getEstimatedAmount());
        fresh.setDeclaredAmount(estimate.getDeclaredAmount());
        fresh.setPayableAmount(hasReceipts ? estimate.getPayableAmount() : BigDecimal.ZERO.setScale(2));
        fresh.setCurrentVersion(1);
        fresh.setReassignOfClaimId(origin.getId());
        fresh.setOperator(operator);
        if (hasReceipts) {
            boolean abnormal = estimate.isAbnormal();
            fresh.setStatus(abnormal ? InvigilateFeeConstants.CLAIM_MANUAL_REVIEW
                    : InvigilateFeeConstants.CLAIM_SUBMITTED);
            fresh.setAbnormal(abnormal);
        } else {
            // 新路线尚未补票：先建草稿，待教师补票提交
            fresh.setStatus(InvigilateFeeConstants.CLAIM_DRAFT);
            fresh.setAbnormal(false);
        }
        claimMapper.insert(fresh);
        if (hasReceipts) {
            insertReceipts(fresh, request.getNewReceipts(), operator);
        }

        // 原单回指新单
        origin.setReassignedToClaimId(fresh.getId());
        claimMapper.updateById(origin);

        appendVersion(fresh, 1, InvigilateFeeConstants.CHANGE_REASSIGN_NEW, fresh.getStatus(), estimate,
                "改派新路线重新估算（改自原申请 " + origin.getClaimNo() + "）"
                        + (hasReceipts ? "" : "：尚未补票，待教师补票后提交"),
                operator, now);
        return fresh;
    }

    /** 改派新单（DRAFT）补票并提交：教师本人或财务均可；提交后进入待审核 / 异常转人工。 */
    @Transactional
    public FeeClaim submitReceipts(Long claimId, FeeClaimSubmitRequest request) {
        FeeClaim claim = requireClaim(claimId);
        String selfCode = FeeSelfHolder.currentSelfCode();
        if (selfCode != null && !selfCode.trim().equals(claim.getInvigilatorCode())) {
            throw new BusinessException("FORBIDDEN_NOT_OWN", "本人视图只能为本人的改派报销补票");
        }
        if (!InvigilateFeeConstants.CLAIM_DRAFT.equals(claim.getStatus())) {
            throw new BusinessException("FEE_CLAIM_NOT_DRAFT",
                    "仅待补票（DRAFT）的改派新单可补票提交，当前状态 " + claim.getStatus());
        }
        if (request.getReceipts() == null || request.getReceipts().isEmpty()) {
            throw new BusinessException("FEE_RECEIPTS_REQUIRED", "补票提交至少须附一张审批票据");
        }
        String operator = OperatorHolder.current();
        LocalDateTime now = LocalDateTime.now();

        for (FeeClaimSubmitRequest.FeeReceiptItem r : request.getReceipts()) {
            if (r.getAmount() == null || r.getAmount().compareTo(BigDecimal.ZERO) < 0) {
                throw new BusinessException("FEE_RECEIPT_AMOUNT_INVALID", "票据金额不能为空或为负");
            }
        }
        BigDecimal declaredTotal = sumReceipts(request.getReceipts());
        FeeTransportRuleVersion version = ruleService.getVersion(claim.getRuleVersionId());
        FeeTransportRule rule = ruleService.getRule(version.getRuleId());
        FeeEstimate estimate = ruleService.estimate(version, claim.getTransportMode(),
                claim.getDistanceKm(), declaredTotal);

        boolean abnormal = estimate.isAbnormal();
        claim.setEstimatedAmount(estimate.getEstimatedAmount());
        claim.setDeclaredAmount(estimate.getDeclaredAmount());
        claim.setPayableAmount(estimate.getPayableAmount());
        claim.setAbnormal(abnormal);
        claim.setStatus(abnormal ? InvigilateFeeConstants.CLAIM_MANUAL_REVIEW
                : InvigilateFeeConstants.CLAIM_SUBMITTED);
        claim.setCurrentVersion(claim.getCurrentVersion() + 1);
        claim.setOperator(operator);
        if (request.getRemark() != null) {
            claim.setRemark(request.getRemark());
        }
        claimMapper.updateById(claim);
        insertReceipts(claim, request.getReceipts(), operator);
        appendVersion(claim, claim.getCurrentVersion(), InvigilateFeeConstants.CHANGE_SUBMIT,
                claim.getStatus(), estimate,
                abnormal ? "补票提交后金额异常，自动转人工：" + String.join("；", estimate.getAnomalyReasons())
                        : "改派新单补票提交", operator, now);
        return claim;
    }

    // ==================== 确认后调整（只追加） ====================

    @Transactional
    public FeeClaimAdjustment adjust(Long claimId, FeeAdjustRequest request) {
        requireFinance("调整报销");
        FeeClaim claim = requireClaim(claimId);
        if (!InvigilateFeeConstants.CLAIM_CONFIRMED.equals(claim.getStatus())) {
            throw new BusinessException("FEE_CLAIM_NOT_CONFIRMED",
                    "报销 " + claim.getClaimNo() + " 尚未确认，不能调整；确认锁定后修订一律走调整单");
        }
        if (request.getAdjustAmount() == null || request.getAdjustAmount().compareTo(BigDecimal.ZERO) == 0) {
            throw new BusinessException("FEE_ADJUST_AMOUNT_ZERO", "调整金额不能为 0");
        }
        String operator = OperatorHolder.current();
        LocalDateTime now = LocalDateTime.now();

        FeeClaimAdjustment adj = new FeeClaimAdjustment();
        adj.setClaimId(claim.getId());
        adj.setInvigilatorId(claim.getInvigilatorId());
        adj.setInvigilatorCode(claim.getInvigilatorCode());
        adj.setInvigilatorName(claim.getInvigilatorName());
        adj.setAdjustAmount(request.getAdjustAmount().setScale(2, BigDecimal.ROUND_HALF_UP));
        adj.setReason(request.getReason().trim());
        adj.setOperator(operator);
        adj.setAdjustedAt(now);
        adjustmentMapper.insert(adj);

        claim.setPayableAmount(claim.getPayableAmount().add(adj.getAdjustAmount()));
        claim.setOperator(operator);
        claimMapper.updateById(claim);
        return adj;
    }

    @Transactional(readOnly = true)
    public List<FeeClaimAdjustment> adjustments(Long claimId) {
        FeeClaim claim = requireClaim(claimId);
        assertSelf(claim);
        return adjustmentMapper.selectList(new QueryWrapper<FeeClaimAdjustment>()
                .eq("claim_id", claim.getId()).orderByAsc("id"));
    }

    // ==================== 查询：财务全量 / 教师本人 ====================

    @Transactional(readOnly = true)
    public List<FeeClaim> list(Long batchId, String status, String invigilatorCode) {
        String selfCode = FeeSelfHolder.currentSelfCode();
        QueryWrapper<FeeClaim> wrapper = new QueryWrapper<FeeClaim>().orderByDesc("travel_date", "id");
        if (batchId != null) {
            wrapper.eq("batch_id", batchId);
        }
        if (status != null && !status.trim().isEmpty()) {
            wrapper.eq("status", status.trim().toUpperCase());
        }
        if (selfCode != null) {
            // 本人视图：强制只看自己，忽略任何试图查看他人的入参
            wrapper.eq("invigilator_code", selfCode.trim());
        } else if (invigilatorCode != null && !invigilatorCode.trim().isEmpty()) {
            wrapper.eq("invigilator_code", invigilatorCode.trim());
        }
        return claimMapper.selectList(wrapper);
    }

    @Transactional(readOnly = true)
    public FeeClaim get(Long claimId) {
        FeeClaim claim = requireClaim(claimId);
        assertSelf(claim);
        return claim;
    }

    @Transactional(readOnly = true)
    public List<FeeClaimVersion> versions(Long claimId) {
        FeeClaim claim = requireClaim(claimId);
        assertSelf(claim);
        return versionMapper.selectList(new QueryWrapper<FeeClaimVersion>()
                .eq("claim_id", claim.getId()).orderByAsc("version_no"));
    }

    @Transactional(readOnly = true)
    public List<FeeReceipt> receipts(Long claimId) {
        FeeClaim claim = requireClaim(claimId);
        assertSelf(claim);
        return receiptMapper.selectList(new QueryWrapper<FeeReceipt>()
                .eq("claim_id", claim.getId()).orderByAsc("id"));
    }

    // ==================== 解析与估算 ====================

    /** 提交预检 / 执行共用解析：本人头强制归属，财务代提交取报文编码；校验批次 / 区域距离 / 票据。 */
    private ResolvedContext resolve(FeeClaimSubmitRequest request) {
        ExamBatch batch = batchService.getBatch(request.getBatchId());
        ExamSite site = siteMapper.selectById(batch.getSiteId());
        if (site == null) {
            throw new BusinessException("SITE_NOT_FOUND", "批次考点不存在：id=" + batch.getSiteId());
        }

        // 归属：教师本人头优先且唯一；财务代提交须在报文给 invigilatorCode
        String selfCode = FeeSelfHolder.currentSelfCode();
        String code = selfCode != null ? selfCode.trim() : trimToNull(request.getInvigilatorCode());
        if (code == null) {
            throw new BusinessException("FEE_INVIGILATOR_CODE_REQUIRED",
                    "财务代提交须指定 invigilatorCode；教师本人请带请求头 X-Self-Code");
        }
        Invigilator invigilator = masterData.requireEnabledByCode(code);

        FeeRegion region = distanceService.requireEnabledRegion(request.getRegionCode());
        FeeDistance distance = distanceService.requireDistance(region, site.getId());

        LocalDate travelDate = request.getTravelDate() != null ? request.getTravelDate()
                : (batch.getExamDate() != null ? batch.getExamDate() : LocalDate.now());
        String projectCode = batch.getProjectCode() == null
                ? InvigilateFeeConstants.PROJECT_DEFAULT : batch.getProjectCode();
        FeeTransportRuleVersion version = ruleService.requireActiveVersion(projectCode, travelDate);
        FeeTransportRule rule = ruleService.getRule(version.getRuleId());

        if (request.getReceipts() == null || request.getReceipts().isEmpty()) {
            throw new BusinessException("FEE_RECEIPTS_REQUIRED", "报销至少须附一张审批票据");
        }
        List<FeeClaimSubmitRequest.FeeReceiptItem> receipts = request.getReceipts();
        String mode = request.getTransportMode().trim().toUpperCase();
        for (FeeClaimSubmitRequest.FeeReceiptItem r : receipts) {
            if (r.getAmount() == null || r.getAmount().compareTo(BigDecimal.ZERO) < 0) {
                throw new BusinessException("FEE_RECEIPT_AMOUNT_INVALID", "票据金额不能为空或为负");
            }
            if (r.getOccurredAt() != null && r.getOccurredAt().toLocalDate().isAfter(travelDate)) {
                throw new BusinessException("FEE_RECEIPT_AFTER_TRAVEL",
                        "存在票据发生时间晚于出行日期 " + travelDate + "，请核对");
            }
            if (r.getTransportMode() == null || r.getTransportMode().trim().isEmpty()) {
                r.setTransportMode(mode);
            }
        }

        ResolvedContext ctx = new ResolvedContext();
        ctx.batch = batch;
        ctx.site = site;
        ctx.projectCode = projectCode;
        ctx.invigilator = invigilator;
        ctx.region = region;
        ctx.distance = distance;
        ctx.travelDate = travelDate;
        ctx.version = version;
        ctx.rule = rule;
        ctx.receipts = receipts;
        ctx.mainMode = mode;
        return ctx;
    }

    private FeeEstimate calculate(ResolvedContext ctx) {
        BigDecimal declaredTotal = sumReceipts(ctx.receipts);
        FeeEstimate estimate = ruleService.estimate(ctx.version, ctx.mainMode,
                ctx.distance.getDistanceKm(), declaredTotal);
        ruleService.fillRuleInfo(estimate, ctx.rule, ctx.version);
        return estimate;
    }

    private BigDecimal sumReceipts(List<FeeClaimSubmitRequest.FeeReceiptItem> receipts) {
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (FeeClaimSubmitRequest.FeeReceiptItem r : receipts) {
            total = total.add(r.getAmount()).setScale(2, BigDecimal.ROUND_HALF_UP);
        }
        return total;
    }

    private void insertReceipts(FeeClaim claim, List<FeeClaimSubmitRequest.FeeReceiptItem> items, String operator) {
        for (FeeClaimSubmitRequest.FeeReceiptItem r : items) {
            FeeReceipt receipt = new FeeReceipt();
            receipt.setClaimId(claim.getId());
            receipt.setTransportMode(r.getTransportMode() == null ? claim.getTransportMode()
                    : r.getTransportMode().trim().toUpperCase());
            receipt.setAmount(r.getAmount().setScale(2, BigDecimal.ROUND_HALF_UP));
            receipt.setOccurredAt(r.getOccurredAt());
            receipt.setFileRef(r.getFileRef());
            receipt.setContentHash(r.getContentHash());
            receipt.setOriginalFilename(r.getOriginalFilename());
            receipt.setContent(r.getContent());
            receipt.setRemark(r.getRemark());
            receipt.setOperator(operator);
            receiptMapper.insert(receipt);
        }
    }

    private void appendVersion(FeeClaim claim, int versionNo, String changeType, String status,
                               FeeEstimate estimate, String reason, String operator, LocalDateTime now) {
        FeeClaimVersion v = new FeeClaimVersion();
        v.setClaimId(claim.getId());
        v.setVersionNo(versionNo);
        v.setChangeType(changeType);
        v.setStatus(status);
        v.setTransportMode(claim.getTransportMode());
        v.setDistanceKm(claim.getDistanceKm());
        v.setEstimatedAmount(estimate == null ? claim.getEstimatedAmount() : estimate.getEstimatedAmount());
        v.setDeclaredAmount(estimate == null ? claim.getDeclaredAmount() : estimate.getDeclaredAmount());
        v.setPayableAmount(estimate == null ? claim.getPayableAmount() : estimate.getPayableAmount());
        v.setAbnormal(claim.getAbnormal() != null && claim.getAbnormal());
        v.setRuleVersionNo(claim.getRuleVersionNo());
        v.setChangeReason(reason);
        v.setOperator(operator);
        v.setChangedAt(now);
        versionMapper.insert(v);
    }

    /** 用申请主表当前金额构照估算快照（确认 / 驳回等后续版本沿用冻结金额，不重新取价）。 */
    private FeeEstimate snapshotEstimate(FeeClaim claim) {
        FeeEstimate est = new FeeEstimate();
        est.setTransportMode(claim.getTransportMode());
        est.setDistanceKm(claim.getDistanceKm());
        est.setEstimatedAmount(claim.getEstimatedAmount());
        est.setDeclaredAmount(claim.getDeclaredAmount());
        est.setPayableAmount(claim.getPayableAmount());
        est.setRuleVersionNo(claim.getRuleVersionNo());
        est.setAbnormal(Boolean.TRUE.equals(claim.getAbnormal()));
        return est;
    }

    // ==================== 内部 ====================

    private FeeClaim requireClaim(Long id) {
        if (id == null) {
            throw new BusinessException("FEE_CLAIM_ID_REQUIRED", "报销申请 id 不能为空");
        }
        FeeClaim claim = claimMapper.selectById(id);
        if (claim == null) {
            throw new BusinessException("FEE_CLAIM_NOT_FOUND", "报销申请不存在：id=" + id);
        }
        return claim;
    }

    /** 财务动作：不允许带教师本人头（本人头只能提交 / 查本人）。 */
    private void requireFinance(String action) {
        if (FeeSelfHolder.currentSelfCode() != null) {
            throw new BusinessException("FEE_FINANCE_ONLY",
                    action + "为财务岗操作，教师本人视图无权执行（请去掉 X-Self-Code 后由财务账号办理）");
        }
    }

    private void assertSelf(FeeClaim claim) {
        String selfCode = FeeSelfHolder.currentSelfCode();
        if (selfCode != null && !selfCode.trim().equals(claim.getInvigilatorCode())) {
            throw new BusinessException("FORBIDDEN_NOT_OWN", "本人视图只能查看本人的报销申请");
        }
    }

    private String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** 报销号：BX + 批次编码 + 4 位流水（同批次只追加取最大 +1）。 */
    private String nextClaimNo(ExamBatch batch) {
        String prefix = "BX" + batch.getCode() + "-";
        int max = 0;
        for (FeeClaim c : claimMapper.selectList(new QueryWrapper<FeeClaim>().eq("batch_id", batch.getId()))) {
            String no = c.getClaimNo();
            if (no != null && no.startsWith(prefix)) {
                try {
                    max = Math.max(max, Integer.parseInt(no.substring(prefix.length())));
                } catch (NumberFormatException ignored) {
                    // 非本规则流水忽略
                }
            }
        }
        return prefix + String.format("%04d", max + 1);
    }

    /** 提交解析中间结构。 */
    private static class ResolvedContext {
        private ExamBatch batch;
        private ExamSite site;
        private String projectCode;
        private Invigilator invigilator;
        private FeeRegion region;
        private FeeDistance distance;
        private LocalDate travelDate;
        private FeeTransportRule rule;
        private FeeTransportRuleVersion version;
        private String mainMode;
        private List<FeeClaimSubmitRequest.FeeReceiptItem> receipts = new ArrayList<>();
    }
}
