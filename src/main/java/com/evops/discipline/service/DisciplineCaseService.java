package com.evops.discipline.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.absence.service.AbsenceMarkService;
import com.evops.common.BusinessException;
import com.evops.common.OperatorHolder;
import com.evops.discipline.common.DisciplineConstants;
import com.evops.discipline.dto.CaseDecisionRequest;
import com.evops.discipline.dto.CaseReportRequest;
import com.evops.discipline.dto.CaseRevokeRequest;
import com.evops.discipline.dto.CaseRevokeReviewRequest;
import com.evops.discipline.entity.DisciplineCase;
import com.evops.discipline.entity.DisciplineCaseVersion;
import com.evops.discipline.entity.DisciplineConsequence;
import com.evops.discipline.entity.DisciplineEvidence;
import com.evops.discipline.mapper.DisciplineCaseMapper;
import com.evops.discipline.mapper.DisciplineCaseVersionMapper;
import com.evops.discipline.mapper.DisciplineConsequenceMapper;
import com.evops.discipline.mapper.DisciplineEvidenceMapper;
import com.evops.discipline.vo.StudentCaseVO;
import com.evops.exam.entity.ExamBatch;
import com.evops.exam.entity.Registration;
import com.evops.exam.service.ExamBatchService;
import com.evops.exam.service.RegistrationService;
import com.evops.grade.entity.ExamScore;
import com.evops.grade.mapper.ExamScoreMapper;
import com.evops.grade.service.ScoreNotificationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 违纪案件（模块 + 版本 + 状态）：
 * - 监考上报附证据立案（REPORTED），立案即起算处理决定期限，超时由调度自动催办；
 * - 限期出处理决定（DECIDED）：WARNING / SCORE_VOID / BAN / SCORE_VOID_AND_BAN，决定冻结进版本；
 * - 后果执行（EXECUTED）：成绩作废回写成绩档案（valid=false + 案件号锚点），成绩晚到挂起、导入自动回写；
 * - 撤销立案必须走双人复核（REVOKE_PENDING → REVOKED），通过同步取消/还原后果，驳回维持并补回写；
 * - 结案封存（SEALED）只读，封存记录仅授权岗 discipline_archiver 可查；
 * - 每次流转只追加版本，学员凭报名号脱敏自查。
 */
@Service
public class DisciplineCaseService {

    private final DisciplineCaseMapper caseMapper;
    private final DisciplineCaseVersionMapper versionMapper;
    private final DisciplineEvidenceMapper evidenceMapper;
    private final DisciplineConsequenceMapper consequenceMapper;
    private final ExamScoreMapper scoreMapper;
    private final ExamBatchService batchService;
    private final RegistrationService registrationService;
    private final ScoreNotificationService notificationService;
    private final AbsenceMarkService absenceMarkService;
    private final DisciplineConfigService configService;

    public DisciplineCaseService(DisciplineCaseMapper caseMapper,
                                 DisciplineCaseVersionMapper versionMapper,
                                 DisciplineEvidenceMapper evidenceMapper,
                                 DisciplineConsequenceMapper consequenceMapper,
                                 ExamScoreMapper scoreMapper,
                                 ExamBatchService batchService,
                                 RegistrationService registrationService,
                                 ScoreNotificationService notificationService,
                                 AbsenceMarkService absenceMarkService,
                                 DisciplineConfigService configService) {
        this.caseMapper = caseMapper;
        this.versionMapper = versionMapper;
        this.evidenceMapper = evidenceMapper;
        this.consequenceMapper = consequenceMapper;
        this.scoreMapper = scoreMapper;
        this.batchService = batchService;
        this.registrationService = registrationService;
        this.notificationService = notificationService;
        this.absenceMarkService = absenceMarkService;
        this.configService = configService;
    }

    // ==================== 监考上报附证据立案 ====================

    @Transactional
    public DisciplineCase report(CaseReportRequest request) {
        ExamBatch batch = batchService.getBatch(request.getBatchId());
        Registration reg = requireRegistration(batch.getId(), request.getRegNo());
        String violationType = normalizeViolationType(request.getViolationType());
        String operator = OperatorHolder.current();
        LocalDateTime now = LocalDateTime.now();

        if (request.getOccurredAt() != null && request.getOccurredAt().isAfter(now)) {
            throw new BusinessException("OCCURRED_AT_FUTURE", "违纪发生时间不能晚于当前时间");
        }
        DisciplineCase open = findOpenCase(reg.getId());
        if (open != null) {
            throw new BusinessException("CASE_EXISTS",
                    "该学员已有在办案件 " + open.getCaseNo() + "（状态 " + open.getStatus()
                            + "），不能重复立案；补充材料请走补充证据，撤销原案请走撤销复核");
        }

        List<CaseReportRequest.EvidenceItem> items = request.getEvidences();
        for (CaseReportRequest.EvidenceItem item : items) {
            validateEvidence(item);
        }

        String source = request.getSource() == null || request.getSource().trim().isEmpty()
                ? DisciplineConstants.SOURCE_LIVE : request.getSource().trim().toUpperCase();
        if (!DisciplineConstants.SOURCE_LIVE.equals(source)
                && !DisciplineConstants.SOURCE_PAPER.equals(source)) {
            throw new BusinessException("SOURCE_INVALID", "案件来源仅支持 LIVE / PAPER：" + request.getSource());
        }
        String reporter = firstNonBlank(request.getReporter(), operator);
        String assignee = firstNonBlank(request.getAssignee(), configService.getDefaultAssignee());

        DisciplineCase c = new DisciplineCase();
        c.setCaseNo(nextCaseNo(batch));
        c.setBatchId(batch.getId());
        c.setRegistrationId(reg.getId());
        c.setRegNo(reg.getRegNo());
        c.setName(reg.getName());
        c.setIdNo(reg.getIdNo());
        c.setViolationType(violationType);
        c.setDescription(trimToNull(request.getDescription()));
        c.setOccurredAt(request.getOccurredAt() != null ? request.getOccurredAt() : now);
        c.setSource(source);
        c.setStatus(DisciplineConstants.CASE_REPORTED);
        c.setCurrentVersion(1);
        c.setReporter(reporter);
        c.setReportedAt(now);
        c.setAssignee(assignee);
        c.setDecisionDeadline(now.plusHours(configService.getDecisionDeadlineHours()));
        c.setUrgedCount(0);
        c.setOperator(operator);
        c.setRemark(trimToNull(request.getRemark()));
        caseMapper.insert(c);

        int seq = 1;
        for (CaseReportRequest.EvidenceItem item : items) {
            insertEvidence(c, item, reporter, now, seq++);
        }
        appendVersion(c, DisciplineConstants.CHANGE_CREATE,
                "监考上报立案：" + violationLabel(violationType) + "，附证据 " + items.size() + " 条",
                null, operator, now);
        return c;
    }

    /** 立案后补充证据：只追加，不修改不删除；封存后不能补。 */
    @Transactional
    public List<DisciplineEvidence> addEvidence(Long caseId, List<CaseReportRequest.EvidenceItem> items) {
        DisciplineCase c = requireCase(caseId);
        assertNotSealed(c);
        if (DisciplineConstants.CASE_REVOKED.equals(c.getStatus())) {
            throw new BusinessException("CASE_REVOKED", "案件已撤销立案，不能再补充证据：" + c.getCaseNo());
        }
        String operator = OperatorHolder.current();
        LocalDateTime now = LocalDateTime.now();
        Long existCount = evidenceMapper.selectCount(new QueryWrapper<DisciplineEvidence>()
                .eq("case_id", c.getId()));
        int seq = (existCount == null ? 0 : existCount.intValue()) + 1;
        List<DisciplineEvidence> created = new ArrayList<>();
        for (CaseReportRequest.EvidenceItem item : items) {
            validateEvidence(item);
            created.add(insertEvidence(c, item, operator, now, seq++));
        }
        return created;
    }

    private DisciplineEvidence insertEvidence(DisciplineCase c, CaseReportRequest.EvidenceItem item,
                                              String reporter, LocalDateTime now, int seq) {
        LocalDateTime collectedAt = item.getCollectedAt() != null ? item.getCollectedAt() : now;
        if (collectedAt.isAfter(now)) {
            throw new BusinessException("EVIDENCE_AT_FUTURE", "证据取证时间不能晚于当前时间：" + item.getTitle());
        }
        DisciplineEvidence e = new DisciplineEvidence();
        e.setCaseId(c.getId());
        e.setCaseNo(c.getCaseNo());
        e.setEvidenceNo(c.getCaseNo() + "-ZJ" + String.format("%02d", seq));
        e.setEvidenceType(normalizeEvidenceType(item.getEvidenceType()));
        e.setTitle(item.getTitle().trim());
        e.setFileRef(trimToNull(item.getFileRef()));
        e.setContentHash(trimToNull(item.getContentHash()));
        e.setContent(trimToNull(item.getContent()));
        e.setReporter(reporter);
        e.setCollectedAt(collectedAt);
        evidenceMapper.insert(e);
        return e;
    }

    private void validateEvidence(CaseReportRequest.EvidenceItem item) {
        String type = normalizeEvidenceType(item.getEvidenceType());
        boolean hasFile = item.getFileRef() != null && !item.getFileRef().trim().isEmpty();
        boolean hasContent = item.getContent() != null && !item.getContent().trim().isEmpty();
        if (DisciplineConstants.EVIDENCE_STATEMENT.equals(type)) {
            if (!hasContent) {
                throw new BusinessException("EVIDENCE_CONTENT_REQUIRED",
                        "情况说明类证据必须填写正文：" + item.getTitle());
            }
        } else if (!hasFile && !hasContent) {
            throw new BusinessException("EVIDENCE_REF_OR_CONTENT_REQUIRED",
                    "证据需提供附件引用（fileRef）或文字内容：" + item.getTitle());
        }
    }

    // ==================== 限期出处理决定 ====================

    @Transactional
    public DisciplineCase decide(Long caseId, CaseDecisionRequest request) {
        DisciplineCase c = requireCase(caseId);
        assertNotSealed(c);
        String operator = OperatorHolder.current();
        LocalDateTime now = LocalDateTime.now();
        if (!DisciplineConstants.CASE_REPORTED.equals(c.getStatus())) {
            throw new BusinessException("DECIDE_STATUS_INVALID",
                    "案件 " + c.getCaseNo() + " 当前状态 " + c.getStatus() + "，仅已立案待决定（REPORTED）可出处理决定");
        }
        String decisionType = normalizeDecisionType(request.getDecisionType());
        LocalDate banFrom = request.getBanFrom();
        LocalDate banUntil = request.getBanUntil();
        if (banFrom != null && banUntil != null && banFrom.isAfter(banUntil)) {
            throw new BusinessException("BAN_RANGE_INVALID", "禁考起始日不能晚于截止日");
        }
        if (isBanDecision(decisionType) && banFrom == null) {
            banFrom = now.toLocalDate();
        }

        c.setDecisionType(decisionType);
        c.setDecisionContent(request.getDecisionContent().trim());
        c.setDecider(operator);
        c.setDecidedAt(now);
        c.setStatus(DisciplineConstants.CASE_DECIDED);
        c.setCurrentVersion(c.getCurrentVersion() + 1);
        caseMapper.updateById(c);

        if (isScoreVoidDecision(decisionType)) {
            consequenceMapper.insert(newConsequence(c, DisciplineConstants.CONS_SCORE_VOID, now));
        }
        if (isBanDecision(decisionType)) {
            DisciplineConsequence ban = newConsequence(c, DisciplineConstants.CONS_BAN, now);
            ban.setBanFrom(banFrom);
            ban.setBanUntil(banUntil);
            consequenceMapper.insert(ban);
        }

        appendVersion(c, DisciplineConstants.CHANGE_DECIDE,
                "作出处理决定：" + decisionLabel(decisionType), null, operator, now);

        // 决定送达学员（站内落库）
        String content = String.format("案件号 %s（%s）：处理决定为「%s」。%s",
                c.getCaseNo(), violationLabel(c.getViolationType()),
                decisionLabel(decisionType), c.getDecisionContent());
        notificationService.notify(c.getBatchId(), c.getRegistrationId(), c.getRegNo(), c.getName(),
                DisciplineConstants.NOTIFY_DISCIPLINE_DECISION, "违纪处理决定送达通知", content,
                null, operator);
        return c;
    }

    // ==================== 后果执行（执行结果回写成绩档案） ====================

    @Transactional
    public DisciplineCase execute(Long caseId, String remark) {
        DisciplineCase c = requireCase(caseId);
        assertNotSealed(c);
        String operator = OperatorHolder.current();
        LocalDateTime now = LocalDateTime.now();
        if (!DisciplineConstants.CASE_DECIDED.equals(c.getStatus())) {
            throw new BusinessException("EXECUTE_STATUS_INVALID",
                    "案件 " + c.getCaseNo() + " 当前状态 " + c.getStatus() + "，仅已出决定（DECIDED）可执行");
        }
        applyConsequences(c, operator, now, trimToNull(remark));
        advanceToExecutedIfDone(c, operator, now);
        return c;
    }

    /**
     * 逐后果执行：成绩作废有成绩即回写、无成绩挂 PENDING_SCORE（成绩导入自动回写）；禁考决定即生效。
     */
    private void applyConsequences(DisciplineCase c, String operator, LocalDateTime now, String remark) {
        List<DisciplineConsequence> consList = consequenceMapper.selectList(
                new QueryWrapper<DisciplineConsequence>().eq("case_id", c.getId()));
        for (DisciplineConsequence cons : consList) {
            if (!DisciplineConstants.CONS_PENDING.equals(cons.getStatus())) {
                continue;
            }
            if (DisciplineConstants.CONS_BAN.equals(cons.getConsType())) {
                markBanExecuted(cons, operator, now, remark);
            } else {
                ExamScore score = findScore(c.getRegistrationId());
                if (score == null) {
                    cons.setStatus(DisciplineConstants.CONS_PENDING_SCORE);
                    cons.setExecuteRemark("成绩尚未导入，挂起待成绩落库自动回写作废");
                    consequenceMapper.updateById(cons);
                } else {
                    voidScore(cons, score, operator, now, remark);
                }
            }
        }
    }

    private void markBanExecuted(DisciplineConsequence cons, String operator, LocalDateTime now, String remark) {
        cons.setStatus(DisciplineConstants.CONS_EXECUTED);
        cons.setExecutedBy(operator);
        cons.setExecutedAt(now);
        cons.setExecuteRemark(remark != null ? remark : "禁考决定生效，区间已建档");
        consequenceMapper.updateById(cons);
    }

    private void voidScore(DisciplineConsequence cons, ExamScore score, String operator,
                           LocalDateTime now, String remark) {
        cons.setScoreId(score.getId());
        cons.setScoreBefore(score.getPaperScore());
        cons.setGradeBefore(score.getGrade());
        cons.setStatus(DisciplineConstants.CONS_EXECUTED);
        cons.setExecutedBy(operator);
        cons.setExecutedAt(now);
        cons.setExecuteRemark(remark != null ? remark : "成绩作废已回写成绩档案");
        consequenceMapper.updateById(cons);

        score.setValid(false);
        score.setDisciplineCaseNo(cons.getCaseNo());
        scoreMapper.updateById(score);
    }

    /** 无待执行后果即结案（成绩晚到的 PENDING_SCORE 不阻塞，导入时自动回写）。 */
    private void advanceToExecutedIfDone(DisciplineCase c, String operator, LocalDateTime now) {
        Long pending = consequenceMapper.selectCount(new QueryWrapper<DisciplineConsequence>()
                .eq("case_id", c.getId())
                .eq("status", DisciplineConstants.CONS_PENDING));
        if (pending != null && pending > 0) {
            return;
        }
        c.setStatus(DisciplineConstants.CASE_EXECUTED);
        c.setExecutedAt(now);
        c.setCurrentVersion(c.getCurrentVersion() + 1);
        caseMapper.updateById(c);
        appendVersion(c, DisciplineConstants.CHANGE_EXECUTE,
                "处理决定后果已执行/交付（成绩晚到项挂起自动回写）", null, operator, now);
    }

    /**
    成绩导入钩子（ScoreService 录入后调用）：把挂起/新决定的成绩作废回写到新成绩，
    并顺带把已出决定的案件推进到已执行。撤销复核期间（REVOKE_PENDING）不回写，等复核结论。
     */
    @Transactional
    public void onScoreImported(ExamScore score) {
        if (score == null || score.getId() == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        String operator = "system";
        List<DisciplineConsequence> pending = consequenceMapper.selectList(
                new QueryWrapper<DisciplineConsequence>()
                        .eq("registration_id", score.getRegistrationId())
                        .eq("cons_type", DisciplineConstants.CONS_SCORE_VOID)
                        .in("status", DisciplineConstants.CONS_PENDING,
                                DisciplineConstants.CONS_PENDING_SCORE));
        for (DisciplineConsequence cons : pending) {
            DisciplineCase c = caseMapper.selectById(cons.getCaseId());
            if (c == null) {
                continue;
            }
            // 撤销复核期间原决定暂停，不回写；已撤销案件的后果不再执行（复核驳回维持时由复核流程补回写）
            if (DisciplineConstants.CASE_REVOKE_PENDING.equals(c.getStatus())
                    || DisciplineConstants.CASE_REVOKED.equals(c.getStatus())) {
                continue;
            }
            voidScore(cons, score, operator, now, "成绩导入时自动回写作废");
            if (DisciplineConstants.CASE_DECIDED.equals(c.getStatus())) {
                advanceToExecutedIfDone(c, operator, now);
            }
        }
    }

    /**
     * 成绩录入前判定：该学员是否有已生效（决定已出、未撤销、非复核暂停）的成绩作废后果。
     * 与缺考无效裁定取并集，成绩落库即无效，两个窗口同一口径。
     */
    @Transactional(readOnly = true)
    public boolean isScoreVoided(Long registrationId) {
        if (registrationId == null) {
            return false;
        }
        List<DisciplineConsequence> list = consequenceMapper.selectList(
                new QueryWrapper<DisciplineConsequence>()
                        .eq("registration_id", registrationId)
                        .eq("cons_type", DisciplineConstants.CONS_SCORE_VOID)
                        .in("status", DisciplineConstants.CONS_PENDING,
                                DisciplineConstants.CONS_EXECUTED,
                                DisciplineConstants.CONS_PENDING_SCORE));
        for (DisciplineConsequence cons : list) {
            DisciplineCase c = caseMapper.selectById(cons.getCaseId());
            if (c == null) {
                continue;
            }
            String s = c.getStatus();
            if (DisciplineConstants.CASE_DECIDED.equals(s)
                    || DisciplineConstants.CASE_EXECUTED.equals(s)
                    || DisciplineConstants.CASE_SEALED.equals(s)) {
                return true;
            }
        }
        return false;
    }

    // ==================== 撤销立案（走双人复核） ====================

    @Transactional
    public DisciplineCase applyRevoke(Long caseId, CaseRevokeRequest request) {
        DisciplineCase c = requireCase(caseId);
        assertNotSealed(c);
        String operator = OperatorHolder.current();
        LocalDateTime now = LocalDateTime.now();
        if (DisciplineConstants.CASE_REVOKE_PENDING.equals(c.getStatus())) {
            throw new BusinessException("REVOKE_EXISTS",
                    "案件 " + c.getCaseNo() + " 已在撤销复核中，不能重复申请");
        }
        if (DisciplineConstants.CASE_REVOKED.equals(c.getStatus())) {
            throw new BusinessException("CASE_REVOKED", "案件已撤销立案：" + c.getCaseNo());
        }
        c.setPrevStatus(c.getStatus());
        c.setStatus(DisciplineConstants.CASE_REVOKE_PENDING);
        c.setRevokeApplicant(operator);
        c.setRevokeReason(request.getReason().trim());
        c.setCurrentVersion(c.getCurrentVersion() + 1);
        caseMapper.updateById(c);
        appendVersion(c, DisciplineConstants.CHANGE_REVOKE_APPLY,
                "申请撤销立案：" + request.getReason().trim(), null, operator, now);
        return c;
    }

    @Transactional
    public DisciplineCase reviewRevoke(Long caseId, CaseRevokeReviewRequest request) {
        DisciplineCase c = requireCase(caseId);
        assertNotSealed(c);
        String reviewer = OperatorHolder.current();
        LocalDateTime now = LocalDateTime.now();
        if (!DisciplineConstants.CASE_REVOKE_PENDING.equals(c.getStatus())) {
            throw new BusinessException("REVOKE_REVIEW_STATUS_INVALID",
                    "案件 " + c.getCaseNo() + " 当前状态 " + c.getStatus() + "，仅撤销复核中（REVOKE_PENDING）可复核");
        }
        if (reviewer.equals(c.getRevokeApplicant())) {
            throw new BusinessException("REVIEWER_SAME_AS_APPLICANT",
                    "撤销立案须双人复核：复核人不能是撤销申请人（" + reviewer + "）");
        }
        if (Boolean.FALSE.equals(request.getApproved())) {
            if (request.getComment() == null || request.getComment().trim().isEmpty()) {
                throw new BusinessException("REJECT_COMMENT_REQUIRED", "驳回撤销申请必须填写复核意见");
            }
            String restore = c.getPrevStatus() == null
                    ? DisciplineConstants.CASE_REPORTED : c.getPrevStatus();
            c.setStatus(restore);
            c.setPrevStatus(null);
            c.setRevokeReviewer(reviewer);
            c.setRevokeReviewComment(request.getComment().trim());
            c.setCurrentVersion(c.getCurrentVersion() + 1);
            caseMapper.updateById(c);
            appendVersion(c, DisciplineConstants.CHANGE_REVOKE_REJECT,
                    "撤销申请被驳回，维持原处理：" + request.getComment().trim(), reviewer, reviewer, now);
            // 复核期间可能有成绩导入（当时暂停未回写），维持决定即补回写
            applySuspendedScoreVoids(c, reviewer, now);
            if (DisciplineConstants.CASE_DECIDED.equals(restore)) {
                advanceToExecutedIfDone(c, reviewer, now);
            }
            return c;
        }

        // 复核通过：撤销立案，已执行后果同步取消/还原
        String comment = request.getComment() == null ? null : request.getComment().trim();
        cancelConsequencesOnRevoke(c, reviewer, now);
        c.setStatus(DisciplineConstants.CASE_REVOKED);
        c.setPrevStatus(null);
        c.setRevokeReviewer(reviewer);
        c.setRevokeReviewComment(comment);
        c.setRevokedAt(now);
        c.setCurrentVersion(c.getCurrentVersion() + 1);
        caseMapper.updateById(c);
        appendVersion(c, DisciplineConstants.CHANGE_REVOKE_APPROVE,
                "复核通过，撤销立案" + (comment == null ? "" : "：" + comment), reviewer, reviewer, now);

        String content = String.format("案件号 %s（%s）：经复核，撤销立案，原处理决定不再执行，已取消/还原相关成绩与禁考后果。",
                c.getCaseNo(), violationLabel(c.getViolationType()));
        notificationService.notify(c.getBatchId(), c.getRegistrationId(), c.getRegNo(), c.getName(),
                DisciplineConstants.NOTIFY_DISCIPLINE_REVOKE, "违纪案件撤销结论通知", content, null, reviewer);
        return c;
    }

    /** 撤销通过：成绩作废恢复成绩有效（除非另有缺考无效裁定）；禁考区间作废；挂起项取消。 */
    private void cancelConsequencesOnRevoke(DisciplineCase c, String reviewer, LocalDateTime now) {
        List<DisciplineConsequence> consList = consequenceMapper.selectList(
                new QueryWrapper<DisciplineConsequence>().eq("case_id", c.getId()));
        for (DisciplineConsequence cons : consList) {
            if (DisciplineConstants.CONS_CANCELLED.equals(cons.getStatus())) {
                continue;
            }
            if (DisciplineConstants.CONS_SCORE_VOID.equals(cons.getConsType())
                    && DisciplineConstants.CONS_EXECUTED.equals(cons.getStatus()) && cons.getScoreId() != null) {
                ExamScore score = scoreMapper.selectById(cons.getScoreId());
                if (score != null && c.getCaseNo().equals(score.getDisciplineCaseNo())) {
                    // 另有缺考/违纪无效裁定时仍保持无效，只在无其他无效依据时恢复；
                    // UpdateWrapper 显式 set null（updateById 默认忽略 null，清不掉案件号锚点）
                    scoreMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<ExamScore>()
                            .eq("id", score.getId())
                            .set("valid", !absenceMarkService.isInvalidated(score.getRegistrationId()))
                            .set("discipline_case_no", null));
                }
            }
            cons.setStatus(DisciplineConstants.CONS_CANCELLED);
            cons.setCancelledBy(reviewer);
            cons.setCancelledAt(now);
            cons.setCancelReason("撤销立案经复核通过，后果取消/还原");
            consequenceMapper.updateById(cons);
        }
    }

    /** 撤销复核驳回维持原决定时，把复核期间导入成绩对应的挂起作废补回写。 */
    private void applySuspendedScoreVoids(DisciplineCase c, String operator, LocalDateTime now) {
        List<DisciplineConsequence> consList = consequenceMapper.selectList(
                new QueryWrapper<DisciplineConsequence>()
                        .eq("case_id", c.getId())
                        .eq("cons_type", DisciplineConstants.CONS_SCORE_VOID)
                        .in("status", DisciplineConstants.CONS_PENDING,
                                DisciplineConstants.CONS_PENDING_SCORE));
        for (DisciplineConsequence cons : consList) {
            ExamScore score = findScore(c.getRegistrationId());
            if (score != null) {
                voidScore(cons, score, operator, now, "撤销复核驳回维持原决定，补回写作废");
            }
        }
    }

    // ==================== 结案封存（仅授权岗可查封存记录） ====================

    @Transactional
    public DisciplineCase seal(Long caseId, String remark) {
        DisciplineCase c = requireCase(caseId);
        String operator = OperatorHolder.current();
        LocalDateTime now = LocalDateTime.now();
        if (Boolean.TRUE.equals(c.getSealed())) {
            throw new BusinessException("CASE_ALREADY_SEALED", "案件已封存：" + c.getCaseNo());
        }
        if (!DisciplineConstants.CASE_EXECUTED.equals(c.getStatus())
                && !DisciplineConstants.CASE_REVOKED.equals(c.getStatus())) {
            throw new BusinessException("SEAL_STATUS_INVALID",
                    "仅已执行完结（EXECUTED）或已撤销（REVOKED）的案件可封存，当前状态 " + c.getStatus());
        }
        c.setSealed(true);
        c.setSealedBy(operator);
        c.setSealedAt(now);
        c.setSealRemark(trimToNull(remark));
        c.setStatus(DisciplineConstants.CASE_SEALED);
        c.setCurrentVersion(c.getCurrentVersion() + 1);
        caseMapper.updateById(c);
        appendVersion(c, DisciplineConstants.CHANGE_SEAL,
                "结案封存" + (remark == null ? "" : "：" + remark.trim()), null, operator, now);
        return c;
    }

    // ==================== 查询（管理视图） ====================

    @Transactional(readOnly = true)
    public List<DisciplineCase> list(Long batchId, String status, String violationType, Boolean sealed) {
        QueryWrapper<DisciplineCase> wrapper = new QueryWrapper<DisciplineCase>().orderByDesc("id");
        if (batchId != null) {
            wrapper.eq("batch_id", batchId);
        }
        if (status != null && !status.trim().isEmpty()) {
            wrapper.eq("status", status.trim().toUpperCase());
        }
        if (violationType != null && !violationType.trim().isEmpty()) {
            wrapper.eq("violation_type", violationType.trim().toUpperCase());
        }
        if (sealed != null) {
            wrapper.eq("sealed", sealed);
        }
        return caseMapper.selectList(wrapper);
    }

    public DisciplineCase requireCase(Long id) {
        if (id == null) {
            throw new BusinessException("CASE_ID_REQUIRED", "案件 id 不能为空");
        }
        DisciplineCase c = caseMapper.selectById(id);
        if (c == null) {
            throw new BusinessException("CASE_NOT_FOUND", "违纪案件不存在：id=" + id);
        }
        return c;
    }

    @Transactional(readOnly = true)
    public DisciplineCase getByCaseNo(String caseNo) {
        if (caseNo == null || caseNo.trim().isEmpty()) {
            throw new BusinessException("CASE_NO_REQUIRED", "案件号不能为空");
        }
        DisciplineCase c = caseMapper.selectOne(new QueryWrapper<DisciplineCase>()
                .eq("case_no", caseNo.trim()));
        if (c == null) {
            throw new BusinessException("CASE_NOT_FOUND", "违纪案件不存在：" + caseNo.trim());
        }
        return c;
    }

    @Transactional(readOnly = true)
    public List<DisciplineEvidence> evidences(Long caseId) {
        requireCase(caseId);
        return evidenceMapper.selectList(new QueryWrapper<DisciplineEvidence>()
                .eq("case_id", caseId).orderByAsc("id"));
    }

    @Transactional(readOnly = true)
    public List<DisciplineConsequence> consequences(Long caseId) {
        requireCase(caseId);
        return consequenceMapper.selectList(new QueryWrapper<DisciplineConsequence>()
                .eq("case_id", caseId).orderByAsc("id"));
    }

    @Transactional(readOnly = true)
    public List<DisciplineCaseVersion> versions(Long caseId) {
        requireCase(caseId);
        return versionMapper.selectList(new QueryWrapper<DisciplineCaseVersion>()
                .eq("case_id", caseId).orderByAsc("version_no"));
    }

    // ==================== 封存档案窗口（仅授权岗；只返已封存记录） ====================

    @Transactional(readOnly = true)
    public List<DisciplineCase> listSealed(Long batchId, String violationType) {
        QueryWrapper<DisciplineCase> wrapper = new QueryWrapper<DisciplineCase>()
                .eq("sealed", true)
                .orderByDesc("sealed_at", "id");
        if (batchId != null) {
            wrapper.eq("batch_id", batchId);
        }
        if (violationType != null && !violationType.trim().isEmpty()) {
            wrapper.eq("violation_type", violationType.trim().toUpperCase());
        }
        return caseMapper.selectList(wrapper);
    }

    /** 封存窗口取案件：必须已封存，否则按不存在处理（未封存记录不进授权窗口）。 */
    public DisciplineCase requireSealedCase(Long caseId) {
        DisciplineCase c = requireCase(caseId);
        if (!Boolean.TRUE.equals(c.getSealed())) {
            throw new BusinessException("SEALED_RECORD_NOT_FOUND",
                    "封存记录不存在或案件尚未封存：id=" + caseId);
        }
        return c;
    }

    // ==================== 学员脱敏自查 ====================

    @Transactional(readOnly = true)
    public List<StudentCaseVO> studentCases(String regNo) {
        if (regNo == null || regNo.trim().isEmpty()) {
            throw new BusinessException("REG_NO_REQUIRED", "报名号不能为空");
        }
        List<DisciplineCase> cases = caseMapper.selectList(new QueryWrapper<DisciplineCase>()
                .eq("reg_no", regNo.trim()).orderByDesc("id"));
        List<StudentCaseVO> result = new ArrayList<>();
        for (DisciplineCase c : cases) {
            result.add(toStudentVO(c));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public StudentCaseVO studentByCaseNo(String regNo, String caseNo) {
        DisciplineCase c = getByCaseNo(caseNo);
        if (regNo == null || !c.getRegNo().equals(regNo.trim())) {
            // 不暴露案件是否存在，统一按查无处理
            throw new BusinessException("CASE_NOT_FOUND", "违纪案件不存在：" + caseNo);
        }
        return toStudentVO(c);
    }

    private StudentCaseVO toStudentVO(DisciplineCase c) {
        StudentCaseVO vo = new StudentCaseVO();
        vo.setCaseNo(c.getCaseNo());
        vo.setBatchId(c.getBatchId());
        vo.setMaskedName(maskName(c.getName()));
        vo.setViolationType(c.getViolationType());
        vo.setViolationLabel(violationLabel(c.getViolationType()));
        vo.setStatus(c.getStatus());
        vo.setStatusLabel(caseStatusLabel(c.getStatus()));
        vo.setDecisionType(c.getDecisionType());
        vo.setDecisionTypeLabel(c.getDecisionType() == null ? null : decisionLabel(c.getDecisionType()));
        vo.setDecisionContent(c.getDecisionContent());
        vo.setReportedAt(c.getReportedAt());
        vo.setDecisionDeadline(c.getDecisionDeadline());
        vo.setDecidedAt(c.getDecidedAt());
        vo.setExecutedAt(c.getExecutedAt());
        vo.setRevokedAt(c.getRevokedAt());
        vo.setSealed(c.getSealed());
        vo.setSealedAt(c.getSealedAt());
        List<DisciplineCaseVersion> versions = versionMapper.selectList(new QueryWrapper<DisciplineCaseVersion>()
                .eq("case_id", c.getId()).orderByAsc("version_no"));
        for (DisciplineCaseVersion v : versions) {
            StudentCaseVO.TimelineItem item = new StudentCaseVO.TimelineItem();
            item.setChangeType(v.getChangeType());
            item.setChangeTypeLabel(changeTypeLabel(v.getChangeType()));
            item.setStatus(v.getStatus());
            item.setChangedAt(v.getChangedAt());
            vo.getTimeline().add(item);
        }
        return vo;
    }

    // ==================== 内部 ====================

    private DisciplineConsequence newConsequence(DisciplineCase c, String type, LocalDateTime now) {
        DisciplineConsequence cons = new DisciplineConsequence();
        cons.setCaseId(c.getId());
        cons.setCaseNo(c.getCaseNo());
        cons.setBatchId(c.getBatchId());
        cons.setRegistrationId(c.getRegistrationId());
        cons.setRegNo(c.getRegNo());
        cons.setName(c.getName());
        cons.setConsType(type);
        cons.setStatus(DisciplineConstants.CONS_PENDING);
        return cons;
    }

    private ExamScore findScore(Long registrationId) {
        return scoreMapper.selectOne(new QueryWrapper<ExamScore>()
                .eq("registration_id", registrationId));
    }

    /** 在办案件：REPORTED/DECIDED/EXECUTED/REVOKE_PENDING 视为占用（REVOKED/SEALED 不挡重新立案）。 */
    private DisciplineCase findOpenCase(Long registrationId) {
        List<DisciplineCase> open = caseMapper.selectList(new QueryWrapper<DisciplineCase>()
                .eq("registration_id", registrationId)
                .in("status", DisciplineConstants.CASE_REPORTED, DisciplineConstants.CASE_DECIDED,
                        DisciplineConstants.CASE_EXECUTED, DisciplineConstants.CASE_REVOKE_PENDING)
                .orderByDesc("id"));
        return open.isEmpty() ? null : open.get(0);
    }

    private Registration requireRegistration(Long batchId, String regNo) {
        if (regNo == null || regNo.trim().isEmpty()) {
            throw new BusinessException("REG_NO_REQUIRED", "报名号不能为空");
        }
        Registration reg = registrationService.findByRegNo(regNo.trim());
        if (reg == null) {
            throw new BusinessException("REG_NOT_FOUND", "名册中查无此报名号：" + regNo.trim());
        }
        if (!reg.getBatchId().equals(batchId)) {
            throw new BusinessException("REG_NOT_IN_BATCH",
                    "报名号不属于本批次（属于批次 id=" + reg.getBatchId() + "）：" + regNo.trim());
        }
        return reg;
    }

    private void assertNotSealed(DisciplineCase c) {
        if (Boolean.TRUE.equals(c.getSealed()) || DisciplineConstants.CASE_SEALED.equals(c.getStatus())) {
            throw new BusinessException("CASE_SEALED",
                    "案件已封存封存只读，不能再变更（如需改动须按工作区版本回退流程）：" + c.getCaseNo());
        }
    }

    void appendVersion(DisciplineCase c, String changeType, String reason,
                       String reviewer, String operator, LocalDateTime now) {
        DisciplineCaseVersion v = new DisciplineCaseVersion();
        v.setCaseId(c.getId());
        v.setVersionNo(c.getCurrentVersion());
        v.setStatus(c.getStatus());
        v.setChangeType(changeType);
        v.setViolationType(c.getViolationType());
        v.setDecisionType(c.getDecisionType());
        v.setChangeReason(reason);
        v.setReviewer(reviewer);
        v.setOperator(operator);
        v.setChangedAt(now);
        versionMapper.insert(v);
    }

    /** 案件号：WJ + 批次编码 + "-" + 4 位流水（唯一约束兜底）。 */
    private String nextCaseNo(ExamBatch batch) {
        String prefix = "WJ" + batch.getCode() + "-";
        int max = 0;
        List<DisciplineCase> exist = caseMapper.selectList(new QueryWrapper<DisciplineCase>()
                .likeRight("case_no", prefix));
        for (DisciplineCase c : exist) {
            String no = c.getCaseNo();
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

    private static String trimToNull(String text) {
        return text == null || text.trim().isEmpty() ? null : text.trim();
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.trim().isEmpty()) {
            return a.trim();
        }
        return b;
    }

    static String normalizeViolationType(String type) {
        if (type == null || type.trim().isEmpty()) {
            throw new BusinessException("VIOLATION_TYPE_REQUIRED", "违纪类型不能为空");
        }
        String normalized = type.trim().toUpperCase();
        if (!VIOLATION_TYPES.contains(normalized)) {
            throw new BusinessException("VIOLATION_TYPE_INVALID",
                    "违纪类型仅支持 CHEAT 作弊 / IMPERSONATE 替考 / PROHIBITED 携带违禁品 / DISORDER 扰乱秩序 / OTHER 其他：" + type);
        }
        return normalized;
    }

    static String normalizeEvidenceType(String type) {
        if (type == null || type.trim().isEmpty()) {
            throw new BusinessException("EVIDENCE_TYPE_REQUIRED", "证据类型不能为空");
        }
        String normalized = type.trim().toUpperCase();
        if (!EVIDENCE_TYPES.contains(normalized)) {
            throw new BusinessException("EVIDENCE_TYPE_INVALID",
                    "证据类型仅支持 PHOTO / VIDEO / DOC / STATEMENT / OTHER：" + type);
        }
        return normalized;
    }

    static String normalizeDecisionType(String type) {
        if (type == null || type.trim().isEmpty()) {
            throw new BusinessException("DECISION_TYPE_REQUIRED", "处理决定类型不能为空");
        }
        String normalized = type.trim().toUpperCase();
        if (!DECISION_TYPES.contains(normalized)) {
            throw new BusinessException("DECISION_TYPE_INVALID",
                    "处理决定仅支持 WARNING 警告 / SCORE_VOID 成绩作废 / BAN 禁考 / SCORE_VOID_AND_BAN 成绩作废并禁考：" + type);
        }
        return normalized;
    }

    static boolean isScoreVoidDecision(String decisionType) {
        return DisciplineConstants.DECISION_SCORE_VOID.equals(decisionType)
                || DisciplineConstants.DECISION_SCORE_VOID_AND_BAN.equals(decisionType);
    }

    static boolean isBanDecision(String decisionType) {
        return DisciplineConstants.DECISION_BAN.equals(decisionType)
                || DisciplineConstants.DECISION_SCORE_VOID_AND_BAN.equals(decisionType);
    }

    static String violationLabel(String type) {
        if (DisciplineConstants.VIOLATION_CHEAT.equals(type)) {
            return "作弊";
        }
        if (DisciplineConstants.VIOLATION_IMPERSONATE.equals(type)) {
            return "替考";
        }
        if (DisciplineConstants.VIOLATION_PROHIBITED.equals(type)) {
            return "携带违禁品";
        }
        if (DisciplineConstants.VIOLATION_DISORDER.equals(type)) {
            return "扰乱秩序";
        }
        return "其他违纪";
    }

    static String evidenceLabel(String type) {
        if (DisciplineConstants.EVIDENCE_PHOTO.equals(type)) {
            return "照片";
        }
        if (DisciplineConstants.EVIDENCE_VIDEO.equals(type)) {
            return "视频";
        }
        if (DisciplineConstants.EVIDENCE_DOC.equals(type)) {
            return "书证";
        }
        if (DisciplineConstants.EVIDENCE_STATEMENT.equals(type)) {
            return "情况说明";
        }
        return "其他证据";
    }

    static String decisionLabel(String type) {
        if (DisciplineConstants.DECISION_WARNING.equals(type)) {
            return "警告";
        }
        if (DisciplineConstants.DECISION_SCORE_VOID.equals(type)) {
            return "成绩作废";
        }
        if (DisciplineConstants.DECISION_BAN.equals(type)) {
            return "禁考";
        }
        if (DisciplineConstants.DECISION_SCORE_VOID_AND_BAN.equals(type)) {
            return "成绩作废并禁考";
        }
        return type;
    }

    static String caseStatusLabel(String status) {
        if (DisciplineConstants.CASE_REPORTED.equals(status)) {
            return "已立案待决定";
        }
        if (DisciplineConstants.CASE_DECIDED.equals(status)) {
            return "已出处理决定";
        }
        if (DisciplineConstants.CASE_EXECUTED.equals(status)) {
            return "后果已执行";
        }
        if (DisciplineConstants.CASE_REVOKE_PENDING.equals(status)) {
            return "撤销复核中";
        }
        if (DisciplineConstants.CASE_REVOKED.equals(status)) {
            return "已撤销立案";
        }
        if (DisciplineConstants.CASE_SEALED.equals(status)) {
            return "已封存";
        }
        return status;
    }

    static String changeTypeLabel(String type) {
        if (DisciplineConstants.CHANGE_CREATE.equals(type)) {
            return "上报立案";
        }
        if (DisciplineConstants.CHANGE_DECIDE.equals(type)) {
            return "处理决定";
        }
        if (DisciplineConstants.CHANGE_EXECUTE.equals(type)) {
            return "后果执行";
        }
        if (DisciplineConstants.CHANGE_REVOKE_APPLY.equals(type)) {
            return "申请撤销";
        }
        if (DisciplineConstants.CHANGE_REVOKE_APPROVE.equals(type)) {
            return "撤销通过";
        }
        if (DisciplineConstants.CHANGE_REVOKE_REJECT.equals(type)) {
            return "撤销驳回";
        }
        if (DisciplineConstants.CHANGE_SEAL.equals(type)) {
            return "结案封存";
        }
        return type;
    }

    static String maskName(String name) {
        if (name == null || name.isEmpty()) {
            return "*";
        }
        StringBuilder sb = new StringBuilder(name.substring(0, 1));
        for (int i = 1; i < name.length(); i++) {
            sb.append('*');
        }
        return sb.toString();
    }

    private static final Set<String> VIOLATION_TYPES = new HashSet<>(Arrays.asList(
            DisciplineConstants.VIOLATION_CHEAT, DisciplineConstants.VIOLATION_IMPERSONATE,
            DisciplineConstants.VIOLATION_PROHIBITED, DisciplineConstants.VIOLATION_DISORDER,
            DisciplineConstants.VIOLATION_OTHER));
    private static final Set<String> EVIDENCE_TYPES = new HashSet<>(Arrays.asList(
            DisciplineConstants.EVIDENCE_PHOTO, DisciplineConstants.EVIDENCE_VIDEO,
            DisciplineConstants.EVIDENCE_DOC, DisciplineConstants.EVIDENCE_STATEMENT,
            DisciplineConstants.EVIDENCE_OTHER));
    private static final Set<String> DECISION_TYPES = new HashSet<>(Arrays.asList(
            DisciplineConstants.DECISION_WARNING, DisciplineConstants.DECISION_SCORE_VOID,
            DisciplineConstants.DECISION_BAN, DisciplineConstants.DECISION_SCORE_VOID_AND_BAN));
}
