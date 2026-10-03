package com.evops.invigilatefee.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.common.BusinessException;
import com.evops.common.OperatorHolder;
import com.evops.invigilatefee.common.InvigilateFeeConstants;
import com.evops.invigilatefee.dto.ModeTariff;
import com.evops.invigilatefee.dto.ReceiptSubmitRequest;
import com.evops.invigilatefee.entity.TravelAllowanceRuleVersion;
import com.evops.invigilatefee.entity.TravelReimbursement;
import com.evops.invigilatefee.entity.TravelReceipt;
import com.evops.invigilatefee.mapper.TravelReceiptMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 报销票据：教师（X-Self-Code）只能提交本人凭证；草稿期可多次提交，金额合计回填报销单。
 * 教师点“提交报销”时合计票据并做异常判定：偏离试算超阈 → MANUAL_REVIEW 转人工，否则 SUBMITTED 待财务确认。
 * 财务（不带本人头）可查看全量票据；票据只追加，改派后旧路线票据置 REJECTED 留痕。
 */
@Service
public class TravelReceiptService {

    private final TravelReceiptMapper receiptMapper;
    private final TravelReimbursementService reimbursementService;
    private final TravelRuleService ruleService;

    public TravelReceiptService(TravelReceiptMapper receiptMapper,
                                TravelReimbursementService reimbursementService,
                                TravelRuleService ruleService) {
        this.receiptMapper = receiptMapper;
        this.reimbursementService = reimbursementService;
        this.ruleService = ruleService;
    }

    /** 提交一张本人票据：仅草稿（DRAFT）可补凭证。 */
    @Transactional
    public TravelReceipt submit(Long reimbursementId, ReceiptSubmitRequest request) {
        TravelReimbursement reimb = reimbursementService.requireOwnReimbursement(reimbursementId);
        if (!InvigilateFeeConstants.REIMB_DRAFT.equals(reimb.getStatus())) {
            throw new BusinessException("RECEIPT_NOT_ACCEPTED",
                    "报销 " + reimb.getReimbursementNo() + " 当前状态 " + reimb.getStatus()
                            + "，仅草稿状态可提交票据（改派后需重新提交）");
        }
        String receiptType = normalizeReceiptType(request.getReceiptType());
        String operator = OperatorHolder.current();
        LocalDateTime now = LocalDateTime.now();

        TravelReceipt receipt = new TravelReceipt();
        receipt.setReimbursementId(reimb.getId());
        receipt.setInvigilatorId(reimb.getInvigilatorId());
        receipt.setInvigilatorCode(reimb.getInvigilatorCode());
        receipt.setInvigilatorName(reimb.getInvigilatorName());
        receipt.setReceiptType(receiptType);
        receipt.setReceiptNo(request.getReceiptNo() == null ? null : request.getReceiptNo().trim());
        receipt.setAmount(request.getAmount().setScale(2, RoundingMode.HALF_UP));
        receipt.setTravelDate(request.getTravelDate() == null ? reimb.getTravelDate() : request.getTravelDate());
        receipt.setAttachmentRef(request.getAttachmentRef());
        receipt.setStatus(InvigilateFeeConstants.RECEIPT_SUBMITTED);
        receipt.setSubmitRemark(request.getSubmitRemark());
        receipt.setOperator(operator);
        receipt.setSubmittedAt(now);
        receiptMapper.insert(receipt);
        return receipt;
    }

    /**
     * 教师提交报销（结束补凭证）：合计已提交票据 → 回填并做异常判定。
     * - 方式要求附票据却一张未交：拒绝提交；
     * - 票据合计与试算偏离超版本阈值：转 MANUAL_REVIEW；
     * - 否则进入 SUBMITTED 待财务确认。
     */
    @Transactional
    public TravelReimbursement submitForReview(Long reimbursementId) {
        TravelReimbursement reimb = reimbursementService.requireOwnReimbursement(reimbursementId);
        if (!InvigilateFeeConstants.REIMB_DRAFT.equals(reimb.getStatus())) {
            throw new BusinessException("SUBMIT_STATUS_INVALID",
                    "报销 " + reimb.getReimbursementNo() + " 当前状态 " + reimb.getStatus() + "，仅草稿可提交");
        }
        String operator = OperatorHolder.current();
        LocalDateTime now = LocalDateTime.now();

        List<TravelReceipt> receipts = submittedReceipts(reimb.getId());
        BigDecimal receiptTotal = sum(receipts);

        TravelAllowanceRuleVersion version = ruleService.requireVersion(reimb.getRuleVersionId());
        ModeTariff tariff = ruleService.requireTariff(version, reimb.getTransportMode());

        boolean hasReceipt = receiptTotal != null;
        if (Boolean.TRUE.equals(tariff.getReceiptRequired()) && !hasReceipt) {
            throw new BusinessException("RECEIPT_REQUIRED",
                    "交通方式 " + reimb.getTransportMode()
                            + " 按规则必须附审批票据，请先提交本人凭证再提交报销");
        }

        reimb.setReceiptAmount(receiptTotal);

        boolean abnormal = hasReceipt
                && ruleService.isAbnormal(version, reimb.getEstimatedAmount(), receiptTotal);
        if (abnormal) {
            reimb.setStatus(InvigilateFeeConstants.REIMB_MANUAL_REVIEW);
            reimb.setAbnormalReason("票据合计 " + receiptTotal.toPlainString() + " 元与试算 "
                    + reimb.getEstimatedAmount().toPlainString() + " 元偏离超过规则版本 "
                    + version.getVersionNo() + " 阈值，转人工审核");
            reimb.setCurrentVersion(reimb.getCurrentVersion() + 1);
            reimb.setOperator(operator);
            // 先持久化票据合计，再落版本快照
            updateReimb(reimb);
            reimbursementService.appendVersion(reimb, InvigilateFeeConstants.REIMB_MANUAL_REVIEW,
                    InvigilateFeeConstants.CHANGE_MANUAL_REVIEW, reimb.getAbnormalReason(),
                    null, operator, now);
        } else {
            reimb.setAbnormalReason(null);
            reimb.setStatus(InvigilateFeeConstants.REIMB_SUBMITTED);
            reimb.setCurrentVersion(reimb.getCurrentVersion() + 1);
            reimb.setOperator(operator);
            updateReimb(reimb);
            reimbursementService.appendVersion(reimb, InvigilateFeeConstants.REIMB_SUBMITTED,
                    InvigilateFeeConstants.CHANGE_RECEIPT,
                    hasReceipt ? "提交票据合计 " + receiptTotal.toPlainString()
                            + " 元，核对未超阈值，待财务确认"
                            : "按里程试算，规则不强制附票据，待财务确认",
                    null, operator, now);
        }
        return reimb;
    }

    /** 票据列表：本人只看本单（教师），不带本人头为财务全量。 */
    @Transactional(readOnly = true)
    public List<TravelReceipt> listReceipts(Long reimbursementId) {
        TravelReimbursement reimb = reimbursementService.requireReimbursement(reimbursementId);
        reimbursementService.assertSelf(reimb);
        return receiptMapper.selectList(new QueryWrapper<TravelReceipt>()
                .eq("reimbursement_id", reimb.getId())
                .orderByAsc("id"));
    }

    /** 已提交（待核）票据合计；无票据返回 null（表示未附凭证）。 */
    public List<TravelReceipt> submittedReceipts(Long reimbursementId) {
        return receiptMapper.selectList(new QueryWrapper<TravelReceipt>()
                .eq("reimbursement_id", reimbursementId)
                .eq("status", InvigilateFeeConstants.RECEIPT_SUBMITTED)
                .orderByAsc("id"));
    }

    public BigDecimal sum(List<TravelReceipt> receipts) {
        if (receipts == null || receipts.isEmpty()) {
            return null;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (TravelReceipt r : receipts) {
            total = total.add(r.getAmount() == null ? BigDecimal.ZERO : r.getAmount());
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    /** 供财务确认时把已提交票据置为已核入。 */
    public void markApproved(List<TravelReceipt> receipts) {
        for (TravelReceipt r : receipts) {
            r.setStatus(InvigilateFeeConstants.RECEIPT_APPROVED);
            receiptMapper.updateById(r);
        }
    }

    private void updateReimb(TravelReimbursement reimb) {
        // 通过协作的 mapper 更新交由 Service 持有者；这里用其暴露的领域方法更稳妥，
        // 但为避免循环，直接由报销服务提供持久化入口。
        reimbursementService.update(reimb);
    }

    private String normalizeReceiptType(String type) {
        if (type == null || type.trim().isEmpty()) {
            throw new BusinessException("RECEIPT_TYPE_REQUIRED", "票据类型不能为空");
        }
        String t = type.trim().toUpperCase();
        if (!InvigilateFeeConstants.RECEIPT_INVOICE.equals(t)
                && !InvigilateFeeConstants.RECEIPT_TICKET.equals(t)
                && !InvigilateFeeConstants.RECEIPT_E_TICKET.equals(t)
                && !InvigilateFeeConstants.RECEIPT_OTHER.equals(t)) {
            throw new BusinessException("RECEIPT_TYPE_NOT_SUPPORTED",
                    "不支持的票据类型：" + t + "（仅 INVOICE / TICKET / E_TICKET / OTHER）");
        }
        return t;
    }
}
