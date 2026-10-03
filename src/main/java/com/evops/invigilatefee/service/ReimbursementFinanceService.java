package com.evops.invigilatefee.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.common.BusinessException;
import com.evops.common.OperatorHolder;
import com.evops.invigilatefee.common.FeeSelfHolder;
import com.evops.invigilatefee.common.InvigilateFeeConstants;
import com.evops.invigilatefee.dto.FeeManualReviewRequest;
import com.evops.invigilatefee.dto.ReimbursementAdjustRequest;
import com.evops.invigilatefee.entity.ReimbursementAdjustment;
import com.evops.invigilatefee.entity.TravelReceipt;
import com.evops.invigilatefee.entity.TravelReimbursement;
import com.evops.invigilatefee.mapper.ReimbursementAdjustmentMapper;
import com.evops.invigilatefee.mapper.TravelReceiptMapper;
import com.evops.invigilatefee.mapper.TravelReimbursementMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 财务全量视图：查看所有教师的报销与凭证；人工核定异常金额；确认锁定正常单；
 * 确认后修订走调整单（只追加台账）。写操作仅财务全量视图（不带 X-Self-Code）可执行。
 */
@Service
public class ReimbursementFinanceService {

    private final TravelReimbursementMapper reimbursementMapper;
    private final TravelReceiptMapper receiptMapper;
    private final ReimbursementAdjustmentMapper adjustmentMapper;
    private final TravelReimbursementService reimbursementService;
    private final TravelReceiptService receiptService;

    public ReimbursementFinanceService(TravelReimbursementMapper reimbursementMapper,
                                       TravelReceiptMapper receiptMapper,
                                       ReimbursementAdjustmentMapper adjustmentMapper,
                                       TravelReimbursementService reimbursementService,
                                       TravelReceiptService receiptService) {
        this.reimbursementMapper = reimbursementMapper;
        this.receiptMapper = receiptMapper;
        this.adjustmentMapper = adjustmentMapper;
        this.reimbursementService = reimbursementService;
        this.receiptService = receiptService;
    }

    // ---------- 查询（财务全量） ----------

    /** 财务列表：可按状态 / 批次过滤；强制全量视图。 */
    @Transactional(readOnly = true)
    public List<TravelReimbursement> listAll(String status, Long batchId) {
        reimbursementService.assertFinance();
        QueryWrapper<TravelReimbursement> wrapper = new QueryWrapper<TravelReimbursement>()
                .orderByDesc("travel_date", "id");
        if (status != null && !status.trim().isEmpty()) {
            wrapper.eq("status", status.trim().toUpperCase());
        }
        if (batchId != null) {
            wrapper.eq("batch_id", batchId);
        }
        return reimbursementMapper.selectList(wrapper);
    }

    /** 异常待人工清单：财务全局看 MANUAL_REVIEW。 */
    @Transactional(readOnly = true)
    public List<TravelReimbursement> listManualQueue() {
        reimbursementService.assertFinance();
        return reimbursementMapper.selectList(new QueryWrapper<TravelReimbursement>()
                .eq("status", InvigilateFeeConstants.REIMB_MANUAL_REVIEW)
                .orderByAsc("id"));
    }

    /** 详情：回票据 + 调整台账 + 最终应付；本人视图仅能看本人单，财务看全量。 */
    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long id) {
        TravelReimbursement reimb = reimbursementService.requireReimbursement(id);
        reimbursementService.assertSelf(reimb);
        List<TravelReceipt> receipts = receiptMapper.selectList(new QueryWrapper<TravelReceipt>()
                .eq("reimbursement_id", id)
                .orderByAsc("id"));
        List<ReimbursementAdjustment> adjustments = adjustmentMapper.selectList(
                new QueryWrapper<ReimbursementAdjustment>()
                        .eq("reimbursement_id", id)
                        .orderByAsc("id"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reimbursement", reimb);
        result.put("receipts", receipts);
        result.put("adjustments", adjustments);
        result.put("payableAmount", reimbursementService.payable(reimb));
        result.put("view", FeeSelfHolder.currentSelfCode() == null ? "FINANCE_ALL" : "SELF");
        return result;
    }

    // ---------- 异常人工核定 ----------

    /**
     * 人工核定异常金额：仅 MANUAL_REVIEW。
     * 放行 → SUBMITTED（核定金额可显式给，缺省取票据合计；无票据取试算），随后由财务统一确认锁定；
     * 驳回 → REJECTED，必须写原因。
     */
    @Transactional
    public TravelReimbursement manualReview(Long id, FeeManualReviewRequest request) {
        reimbursementService.assertFinance();
        TravelReimbursement reimb = reimbursementService.requireReimbursement(id);
        if (!InvigilateFeeConstants.REIMB_MANUAL_REVIEW.equals(reimb.getStatus())) {
            throw new BusinessException("NOT_MANUAL_REVIEW",
                    "仅异常待人工（MANUAL_REVIEW）的报销单可人工核定，当前状态：" + reimb.getStatus());
        }
        if (request == null || request.getApproved() == null) {
            throw new BusinessException("MANUAL_DECISION_REQUIRED", "人工核定必须明确放行或驳回");
        }
        String operator = OperatorHolder.current();
        LocalDateTime now = LocalDateTime.now();

        if (Boolean.TRUE.equals(request.getApproved())) {
            BigDecimal amount = resolveApprovedAmount(reimb, request.getApprovedAmount());
            reimb.setApprovedAmount(amount);
            reimb.setStatus(InvigilateFeeConstants.REIMB_SUBMITTED);
            reimb.setReviewer(operator);
            reimb.setReviewedAt(now);
            reimb.setReviewComment(blankToNull(request.getComment()));
            reimb.setCurrentVersion(reimb.getCurrentVersion() + 1);
            reimb.setOperator(operator);
            reimbursementService.update(reimb);
            reimbursementService.appendVersion(reimb, InvigilateFeeConstants.REIMB_SUBMITTED,
                    InvigilateFeeConstants.CHANGE_MANUAL_APPROVE,
                    "人工核定放行，核定金额 " + amount.toPlainString() + " 元", null, operator, now);
        } else {
            if (request.getComment() == null || request.getComment().trim().isEmpty()) {
                throw new BusinessException("MANUAL_REJECT_REASON_REQUIRED", "人工驳回必须写明原因");
            }
            reimb.setStatus(InvigilateFeeConstants.REIMB_REJECTED);
            reimb.setReviewer(operator);
            reimb.setReviewedAt(now);
            reimb.setReviewComment(request.getComment().trim());
            reimb.setCurrentVersion(reimb.getCurrentVersion() + 1);
            reimb.setOperator(operator);
            reimbursementService.update(reimb);
            reimbursementService.appendVersion(reimb, InvigilateFeeConstants.REIMB_REJECTED,
                    InvigilateFeeConstants.CHANGE_REJECT, request.getComment().trim(), null, operator, now);
        }
        return reimb;
    }

    // ---------- 确认锁定 ----------

    /**
     * 财务确认：仅 SUBMITTED。核定金额取已提交票据合计（有票）或试算金额（无票按里程）；
     * 确认后 CONFIRMED 只读、票据置 APPROVED，之后修订只能走调整单。
     */
    @Transactional
    public TravelReimbursement confirm(Long id) {
        reimbursementService.assertFinance();
        TravelReimbursement reimb = reimbursementService.requireReimbursement(id);
        if (InvigilateFeeConstants.REIMB_CONFIRMED.equals(reimb.getStatus())) {
            throw new BusinessException("REIMBURSEMENT_ALREADY_CONFIRMED",
                    "报销 " + reimb.getReimbursementNo() + " 已确认，不能重复确认");
        }
        if (!InvigilateFeeConstants.REIMB_SUBMITTED.equals(reimb.getStatus())) {
            throw new BusinessException("CONFIRM_STATUS_INVALID",
                    "仅已提交（SUBMITTED）的报销单可确认，当前状态 " + reimb.getStatus()
                            + "（异常需先人工核定，草稿需教师先提交）");
        }
        String operator = OperatorHolder.current();
        LocalDateTime now = LocalDateTime.now();

        List<TravelReceipt> pending = receiptService.submittedReceipts(reimb.getId());
        // 已人工核定（异常单放行）时沿用人工核定金额；否则按票据合计（有票）或试算（无票）
        BigDecimal amount = reimb.getApprovedAmount() != null
                ? reimb.getApprovedAmount()
                : (pending.isEmpty() ? reimb.getEstimatedAmount() : receiptService.sum(pending));
        reimb.setApprovedAmount(amount);
        reimb.setAbnormalReason(null);
        reimb.setConfirmedBy(operator);
        reimb.setConfirmedAt(now);
        reimb.setStatus(InvigilateFeeConstants.REIMB_CONFIRMED);
        reimb.setCurrentVersion(reimb.getCurrentVersion() + 1);
        reimb.setOperator(operator);
        reimbursementService.update(reimb);
        receiptService.markApproved(pending);
        reimbursementService.appendVersion(reimb, InvigilateFeeConstants.REIMB_CONFIRMED,
                InvigilateFeeConstants.CHANGE_CONFIRM,
                "财务确认，核定 " + amount.toPlainString() + " 元并锁定，修订走调整单", null, operator, now);
        return reimb;
    }

    // ---------- 确认后调整（只追加） ----------

    @Transactional
    public ReimbursementAdjustment adjust(Long id, ReimbursementAdjustRequest request) {
        reimbursementService.assertFinance();
        TravelReimbursement reimb = reimbursementService.requireReimbursement(id);
        if (!InvigilateFeeConstants.REIMB_CONFIRMED.equals(reimb.getStatus())) {
            throw new BusinessException("ADJUST_ONLY_AFTER_CONFIRM",
                    "报销 " + reimb.getReimbursementNo() + " 尚未确认锁定，无需调整"
                            + "（草稿可改派/重提，待确认单直接确认即可）");
        }
        if (request.getAdjustAmount() == null || request.getAdjustAmount().compareTo(BigDecimal.ZERO) == 0) {
            throw new BusinessException("ADJUST_AMOUNT_ZERO", "调整金额不能为 0");
        }
        String operator = OperatorHolder.current();
        LocalDateTime now = LocalDateTime.now();

        ReimbursementAdjustment adj = new ReimbursementAdjustment();
        adj.setReimbursementId(reimb.getId());
        adj.setInvigilatorId(reimb.getInvigilatorId());
        adj.setInvigilatorCode(reimb.getInvigilatorCode());
        adj.setInvigilatorName(reimb.getInvigilatorName());
        adj.setAdjustAmount(request.getAdjustAmount().setScale(2, RoundingMode.HALF_UP));
        adj.setReason(request.getReason().trim());
        adj.setOperator(operator);
        adj.setAdjustedAt(now);
        adjustmentMapper.insert(adj);

        reimb.setAdjustTotal((reimb.getAdjustTotal() == null ? BigDecimal.ZERO : reimb.getAdjustTotal())
                .add(adj.getAdjustAmount()));
        reimb.setAdjustCount(reimb.getAdjustCount() == null ? 1 : reimb.getAdjustCount() + 1);
        reimbursementService.update(reimb);
        return adj;
    }

    /** 调整台账（只追加）：本人单与财务均可查本单台账（本人经归属校验）。 */
    @Transactional(readOnly = true)
    public List<ReimbursementAdjustment> adjustments(Long id) {
        reimbursementService.assertSelf(reimbursementService.requireReimbursement(id));
        return adjustmentMapper.selectList(new QueryWrapper<ReimbursementAdjustment>()
                .eq("reimbursement_id", id)
                .orderByAsc("id"));
    }

    // ---------- 内部 ----------

    private BigDecimal resolveApprovedAmount(TravelReimbursement reimb, BigDecimal override) {
        if (override != null) {
            if (override.signum() < 0) {
                throw new BusinessException("APPROVED_AMOUNT_NEGATIVE", "核定金额不能为负");
            }
            return override.setScale(2, RoundingMode.HALF_UP);
        }
        if (reimb.getReceiptAmount() != null) {
            return reimb.getReceiptAmount();
        }
        return reimb.getEstimatedAmount();
    }

    private String blankToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }
}
