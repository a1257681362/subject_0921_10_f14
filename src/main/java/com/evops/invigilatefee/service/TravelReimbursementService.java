package com.evops.invigilatefee.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.appoint.entity.Invigilator;
import com.evops.appoint.mapper.InvigilatorMapper;
import com.evops.common.BusinessException;
import com.evops.common.OperatorHolder;
import com.evops.exam.entity.ExamBatch;
import com.evops.exam.entity.ExamSite;
import com.evops.exam.mapper.ExamSiteMapper;
import com.evops.exam.service.ExamBatchService;
import com.evops.invigilatefee.common.FeeSelfHolder;
import com.evops.invigilatefee.common.InvigilateFeeConstants;
import com.evops.invigilatefee.dto.ModeTariff;
import com.evops.invigilatefee.dto.ReimbursementApplyRequest;
import com.evops.invigilatefee.dto.RerouteRequest;
import com.evops.invigilatefee.entity.SiteDistance;
import com.evops.invigilatefee.entity.TravelAllowanceRuleVersion;
import com.evops.invigilatefee.entity.TravelReimbursement;
import com.evops.invigilatefee.entity.TravelReimbVersion;
import com.evops.invigilatefee.entity.TravelReceipt;
import com.evops.invigilatefee.entity.TravelRouteChange;
import com.evops.invigilatefee.mapper.TravelReimbursementMapper;
import com.evops.invigilatefee.mapper.TravelReimbVersionMapper;
import com.evops.invigilatefee.mapper.TravelReceiptMapper;
import com.evops.invigilatefee.mapper.TravelRouteChangeMapper;
import com.evops.invigilatefee.vo.EstimatePreview;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 跨区监考交通补贴报销（模块 + 版本 + 状态）：
 * - 申请即按 出行日期命中规则版本、按考点里程 + 交通方式试算，并把版本 id/no、口径、里程冻结进 CREATE 版本；
 * - 改派：原申请路线/金额作为历史版本只读保留，按新路线重新选版本、重新估算，写改派记录；
 * - 教师（X-Self-Code）只能为本人申请 / 查询；不带为财务全量视图；
 * - 提交票据、异常转人工、确认锁定、调整单由票据 / 财务服务衔接。
 */
@Service
public class TravelReimbursementService {

    private final TravelReimbursementMapper reimbursementMapper;
    private final TravelReimbVersionMapper versionMapper;
    private final TravelRouteChangeMapper routeChangeMapper;
    private final TravelReceiptMapper receiptMapper;
    private final TravelRuleService ruleService;
    private final SiteDistanceService distanceService;
    private final InvigilatorMapper invigilatorMapper;
    private final ExamBatchService batchService;
    private final ExamSiteMapper siteMapper;

    public TravelReimbursementService(TravelReimbursementMapper reimbursementMapper,
                                      TravelReimbVersionMapper versionMapper,
                                      TravelRouteChangeMapper routeChangeMapper,
                                      TravelReceiptMapper receiptMapper,
                                      TravelRuleService ruleService,
                                      SiteDistanceService distanceService,
                                      InvigilatorMapper invigilatorMapper,
                                      ExamBatchService batchService,
                                      ExamSiteMapper siteMapper) {
        this.reimbursementMapper = reimbursementMapper;
        this.versionMapper = versionMapper;
        this.routeChangeMapper = routeChangeMapper;
        this.receiptMapper = receiptMapper;
        this.ruleService = ruleService;
        this.distanceService = distanceService;
        this.invigilatorMapper = invigilatorMapper;
        this.batchService = batchService;
        this.siteMapper = siteMapper;
    }

    // ==================== 试算（不落库） ====================

    @Transactional(readOnly = true)
    public EstimatePreview preview(ReimbursementApplyRequest request) {
        EstimateContext ctx = resolveContext(request.getBatchId(), request.getOriginCode(),
                request.getTransportMode(), request.getTravelDate());
        return ctx.preview;
    }

    // ==================== 申请 ====================

    @Transactional
    public TravelReimbursement apply(ReimbursementApplyRequest request) {
        EstimateContext ctx = resolveContext(request.getBatchId(), request.getOriginCode(),
                request.getTransportMode(), request.getTravelDate());
        Invigilator inv = requireApplicant(request.getInvigilatorCode());
        String operator = OperatorHolder.current();
        LocalDateTime now = LocalDateTime.now();

        TravelReimbursement reimb = new TravelReimbursement();
        reimb.setReimbursementNo(nextReimbursementNo(ctx.batch));
        reimb.setBatchId(ctx.batch.getId());
        reimb.setSiteId(ctx.site.getId());
        reimb.setSiteCode(ctx.site.getCode());
        reimb.setSiteName(ctx.site.getName());
        reimb.setProjectCode(ctx.project);
        reimb.setInvigilatorId(inv.getId());
        reimb.setInvigilatorCode(inv.getCode());
        reimb.setInvigilatorName(inv.getName());
        reimb.setOriginCode(ctx.distance.getOriginCode());
        reimb.setOriginName(ctx.distance.getOriginName());
        reimb.setTransportMode(ctx.preview.getTransportMode());
        reimb.setTravelDate(ctx.travelDate);
        reimb.setDistanceKm(ctx.distance.getDistanceKm());
        reimb.setRuleVersionId(ctx.version.getId());
        reimb.setRuleVersionNo(ctx.version.getVersionNo());
        reimb.setCalcMode(ctx.calcResult.getTariff().getCalcMode());
        reimb.setEstimatedAmount(ctx.calcResult.getAmount());
        reimb.setCurrentVersion(1);
        reimb.setStatus(InvigilateFeeConstants.REIMB_DRAFT);
        reimb.setAdjustTotal(BigDecimal.ZERO);
        reimb.setAdjustCount(0);
        reimb.setRemark(request.getRemark());
        reimb.setOperator(operator);
        reimbursementMapper.insert(reimb);

        appendVersion(reimb, InvigilateFeeConstants.REIMB_DRAFT,
                InvigilateFeeConstants.CHANGE_CREATE, "新建交通补贴报销申请，按出行日期冻结规则版本并试算",
                null, operator, now);
        return reimb;
    }

    // ==================== 改派：新路线重新估算，原申请只读 ====================

    @Transactional
    public TravelReimbursement reroute(Long reimbursementId, RerouteRequest request) {
        TravelReimbursement reimb = requireReimbursement(reimbursementId);
        assertSelf(reimb);
        if (InvigilateFeeConstants.REIMB_CONFIRMED.equals(reimb.getStatus())) {
            throw new BusinessException("REIMBURSEMENT_LOCKED",
                    "报销 " + reimb.getReimbursementNo() + " 已确认锁定，不能改派；如需修订请走调整单");
        }
        if (InvigilateFeeConstants.REIMB_REJECTED.equals(reimb.getStatus())) {
            throw new BusinessException("REIMBURSEMENT_REJECTED",
                    "报销 " + reimb.getReimbursementNo() + " 已驳回，不能改派");
        }

        // 新路线上下文（新批次 → 新考点 / 项目，新出发地 / 交通方式 / 出行日期 → 重新选版本、重新估算）
        EstimateContext ctx = resolveContext(request.getBatchId(), request.getOriginCode(),
                request.getTransportMode(), request.getTravelDate());
        String operator = OperatorHolder.current();
        LocalDateTime now = LocalDateTime.now();

        TravelRouteChange change = new TravelRouteChange();
        change.setReimbursementId(reimb.getId());
        change.setBatchId(reimb.getBatchId());
        change.setFromSiteId(reimb.getSiteId());
        change.setFromSiteCode(reimb.getSiteCode());
        change.setFromOriginCode(reimb.getOriginCode());
        change.setFromTransportMode(reimb.getTransportMode());
        change.setFromTravelDate(reimb.getTravelDate());
        change.setFromDistanceKm(reimb.getDistanceKm());
        change.setFromEstimatedAmount(reimb.getEstimatedAmount());
        change.setToSiteId(ctx.site.getId());
        change.setToSiteCode(ctx.site.getCode());
        change.setToOriginCode(ctx.distance.getOriginCode());
        change.setToTransportMode(ctx.preview.getTransportMode());
        change.setToTravelDate(ctx.travelDate);
        change.setToDistanceKm(ctx.distance.getDistanceKm());
        change.setToRuleVersionNo(ctx.version.getVersionNo());
        change.setToEstimatedAmount(ctx.calcResult.getAmount());
        change.setReason(request.getReason());
        change.setOperator(operator);
        change.setChangedAt(now);
        routeChangeMapper.insert(change);

        // 主表切到新路线并重新冻结口径；原路线/原金额已在历史版本与改派记录中只读保留
        int nextNo = reimb.getCurrentVersion() + 1;
        reimb.setBatchId(ctx.batch.getId());
        reimb.setSiteId(ctx.site.getId());
        reimb.setSiteCode(ctx.site.getCode());
        reimb.setSiteName(ctx.site.getName());
        reimb.setProjectCode(ctx.project);
        reimb.setOriginCode(ctx.distance.getOriginCode());
        reimb.setOriginName(ctx.distance.getOriginName());
        reimb.setTransportMode(ctx.preview.getTransportMode());
        reimb.setTravelDate(ctx.travelDate);
        reimb.setDistanceKm(ctx.distance.getDistanceKm());
        reimb.setRuleVersionId(ctx.version.getId());
        reimb.setRuleVersionNo(ctx.version.getVersionNo());
        reimb.setCalcMode(ctx.calcResult.getTariff().getCalcMode());
        reimb.setEstimatedAmount(ctx.calcResult.getAmount());
        // 新路线待重新提交票据与审批：清空旧路线票据口径，回到草稿
        reimb.setReceiptAmount(null);
        reimb.setApprovedAmount(null);
        reimb.setAbnormalReason(null);
        reimb.setReviewer(null);
        reimb.setReviewedAt(null);
        reimb.setReviewComment(null);
        reimb.setConfirmedBy(null);
        reimb.setConfirmedAt(null);
        reimb.setStatus(InvigilateFeeConstants.REIMB_DRAFT);
        reimb.setCurrentVersion(nextNo);
        reimb.setOperator(operator);
        reimbursementMapper.updateById(reimb);

        appendVersion(reimb, InvigilateFeeConstants.REIMB_DRAFT,
                InvigilateFeeConstants.CHANGE_REROUTE,
                request.getReason() == null ? "改派，按新路线重新估算（原路线只读保留）" : request.getReason(),
                change.getId(), operator, now);

        // 旧路线已提交票据不再核入新路线（票据本身保留，标记不予核入留痕）
        List<TravelReceipt> old = receiptMapper.selectList(new QueryWrapper<TravelReceipt>()
                .eq("reimbursement_id", reimb.getId())
                .eq("status", InvigilateFeeConstants.RECEIPT_SUBMITTED));
        for (TravelReceipt r : old) {
            r.setStatus(InvigilateFeeConstants.RECEIPT_REJECTED);
            r.setSubmitRemark(appendNote(r.getSubmitRemark(), "改派换路线，原票据不予核入新路线"));
            receiptMapper.updateById(r);
        }
        return reimb;
    }

    // ==================== 查询 ====================

    @Transactional(readOnly = true)
    public List<TravelReimbursement> list(Long batchId, String status, String invigilatorCode) {
        String selfCode = FeeSelfHolder.currentSelfCode();
        QueryWrapper<TravelReimbursement> wrapper = new QueryWrapper<TravelReimbursement>()
                .orderByDesc("batch_id", "id");
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
        return reimbursementMapper.selectList(wrapper);
    }

    @Transactional(readOnly = true)
    public TravelReimbursement get(Long id) {
        TravelReimbursement reimb = requireReimbursement(id);
        assertSelf(reimb);
        return reimb;
    }

    /** 最终应付 = 核定金额 + 调整单累计（确认后才有意义）。 */
    public BigDecimal payable(TravelReimbursement reimb) {
        BigDecimal base = reimb.getApprovedAmount() == null ? BigDecimal.ZERO : reimb.getApprovedAmount();
        BigDecimal adj = reimb.getAdjustTotal() == null ? BigDecimal.ZERO : reimb.getAdjustTotal();
        return base.add(adj).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    @Transactional(readOnly = true)
    public List<TravelReimbVersion> versions(Long id) {
        TravelReimbursement reimb = requireReimbursement(id);
        assertSelf(reimb);
        return versionMapper.selectList(new QueryWrapper<TravelReimbVersion>()
                .eq("reimbursement_id", reimb.getId())
                .orderByAsc("version_no"));
    }

    @Transactional(readOnly = true)
    public List<TravelRouteChange> routeChanges(Long batchId, Long reimbursementId) {
        String selfCode = FeeSelfHolder.currentSelfCode();
        QueryWrapper<TravelRouteChange> wrapper = new QueryWrapper<TravelRouteChange>()
                .orderByDesc("id");
        if (batchId != null) {
            wrapper.eq("batch_id", batchId);
        }
        if (reimbursementId != null) {
            wrapper.eq("reimbursement_id", reimbursementId);
        }
        List<TravelRouteChange> changes = routeChangeMapper.selectList(wrapper);
        if (selfCode != null) {
            // 本人视图：只能看本人报销的改派记录，需经主表归属过滤
            List<TravelRouteChange> mine = new ArrayList<>();
            for (TravelRouteChange c : changes) {
                TravelReimbursement owner = reimbursementMapper.selectById(c.getReimbursementId());
                if (owner != null && selfCode.trim().equals(owner.getInvigilatorCode())) {
                    mine.add(c);
                }
            }
            return mine;
        }
        return changes;
    }

    // ==================== 内部 / 供协作服务 ====================

    public TravelReimbursement requireReimbursement(Long id) {
        if (id == null) {
            throw new BusinessException("REIMBURSEMENT_ID_REQUIRED", "报销单 id 不能为空");
        }
        TravelReimbursement reimb = reimbursementMapper.selectById(id);
        if (reimb == null) {
            throw new BusinessException("REIMBURSEMENT_NOT_FOUND", "交通补贴报销单不存在：id=" + id);
        }
        return reimb;
    }

    /** 取报销单并校验本人归属（教师视图只能操作本人单）。 */
    public TravelReimbursement requireOwnReimbursement(Long id) {
        TravelReimbursement reimb = requireReimbursement(id);
        assertSelf(reimb);
        return reimb;
    }

    /** 持久化报销单（供票据 / 财务协作服务在同一事务内更新）。 */
    public void update(TravelReimbursement reimb) {
        reimbursementMapper.updateById(reimb);
    }

    public void assertSelf(TravelReimbursement reimb) {
        String selfCode = FeeSelfHolder.currentSelfCode();
        if (selfCode != null && !selfCode.trim().equals(reimb.getInvigilatorCode())) {
            throw new BusinessException("FORBIDDEN_NOT_OWN", "本人视图只能操作本人的交通补贴报销单");
        }
    }

    /** 财务动作仅允许全量视图（不带 X-Self-Code）。 */
    public void assertFinance() {
        if (FeeSelfHolder.currentSelfCode() != null) {
            throw new BusinessException("FINANCE_ONLY", "该操作仅财务全量视图可执行，本人视图无权操作");
        }
    }

    public void appendVersion(TravelReimbursement reimb, String status, String changeType,
                              String reason, Long routeChangeId, String operator, LocalDateTime now) {
        TravelReimbVersion v = new TravelReimbVersion();
        v.setReimbursementId(reimb.getId());
        v.setVersionNo(reimb.getCurrentVersion());
        v.setStatus(status);
        v.setOriginCode(reimb.getOriginCode());
        v.setOriginName(reimb.getOriginName());
        v.setTransportMode(reimb.getTransportMode());
        v.setTravelDate(reimb.getTravelDate());
        v.setDistanceKm(reimb.getDistanceKm());
        v.setRuleVersionId(reimb.getRuleVersionId());
        v.setRuleVersionNo(reimb.getRuleVersionNo());
        v.setCalcMode(reimb.getCalcMode());
        v.setEstimatedAmount(reimb.getEstimatedAmount());
        v.setReceiptAmount(reimb.getReceiptAmount());
        v.setApprovedAmount(reimb.getApprovedAmount());
        v.setChangeType(changeType);
        v.setChangeReason(reason);
        v.setRouteChangeId(routeChangeId);
        v.setOperator(operator);
        v.setChangedAt(now);
        versionMapper.insert(v);
    }

    /** 报销单号：TB + 批次编码 + 4 位流水。 */
    private String nextReimbursementNo(ExamBatch batch) {
        String prefix = "TB" + batch.getCode() + "-";
        int max = 0;
        List<TravelReimbursement> exist = reimbursementMapper.selectList(
                new QueryWrapper<TravelReimbursement>().eq("batch_id", batch.getId()));
        for (TravelReimbursement r : exist) {
            String no = r.getReimbursementNo();
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

    /** 教师为本人申请：本人视图下编码必须与 X-Self-Code 一致；监考人员须启用。 */
    private Invigilator requireApplicant(String invigilatorCode) {
        if (invigilatorCode == null || invigilatorCode.trim().isEmpty()) {
            throw new BusinessException("INVIGILATOR_CODE_REQUIRED", "报销监考人员编码不能为空");
        }
        String code = invigilatorCode.trim();
        String selfCode = FeeSelfHolder.currentSelfCode();
        if (selfCode != null && !selfCode.trim().equals(code)) {
            throw new BusinessException("FORBIDDEN_NOT_OWN", "本人视图只能为本人提交交通补贴报销申请");
        }
        Invigilator inv = invigilatorMapper.selectOne(new QueryWrapper<Invigilator>().eq("code", code));
        if (inv == null) {
            throw new BusinessException("INVIGILATOR_NOT_FOUND", "监考人员不存在：" + code);
        }
        if (!InvigilateFeeConstants.ENABLED.equals(inv.getStatus())) {
            throw new BusinessException("INVIGILATOR_DISABLED", "监考人员已停用，不能申请交通补贴：" + code);
        }
        return inv;
    }

    /** 解析一次出行的试算上下文：批次/考点/项目 + 里程 + 按出行日期命中的规则版本 + 金额。 */
    private EstimateContext resolveContext(Long batchId, String originCode, String transportMode,
                                           LocalDate travelDate) {
        if (travelDate == null) {
            throw new BusinessException("TRAVEL_DATE_REQUIRED", "出行日期不能为空");
        }
        ExamBatch batch = batchService.getBatch(batchId);
        ExamSite site = siteMapper.selectById(batch.getSiteId());
        if (site == null) {
            throw new BusinessException("SITE_NOT_FOUND", "批次所属考点不存在：siteId=" + batch.getSiteId());
        }
        String project = ruleService.normalizeProject(batch.getProjectCode());
        SiteDistance distance = distanceService.requireEnabled(originCode, site.getId());
        String mode = transportMode == null ? null : transportMode.trim().toUpperCase();
        TravelAllowanceRuleVersion version = ruleService.resolveVersion(project, travelDate);
        TravelRuleService.CalcResult calc = ruleService.calculateDetail(version, mode, distance.getDistanceKm());

        EstimateContext ctx = new EstimateContext();
        ctx.batch = batch;
        ctx.site = site;
        ctx.project = project;
        ctx.distance = distance;
        ctx.travelDate = travelDate;
        ctx.version = version;
        ctx.calcResult = calc;
        ctx.preview = toPreview(batch, site, project, distance, travelDate, version, calc);
        return ctx;
    }

    private EstimatePreview toPreview(ExamBatch batch, ExamSite site, String project, SiteDistance distance,
                                      LocalDate travelDate, TravelAllowanceRuleVersion version,
                                      TravelRuleService.CalcResult calc) {
        ModeTariff tariff = calc.getTariff();
        EstimatePreview p = new EstimatePreview();
        p.setBatchId(batch.getId());
        p.setBatchCode(batch.getCode());
        p.setSiteId(site.getId());
        p.setSiteCode(site.getCode());
        p.setSiteName(site.getName());
        p.setProjectCode(project);
        p.setOriginCode(distance.getOriginCode());
        p.setOriginName(distance.getOriginName());
        p.setTransportMode(tariff.getTransportMode());
        p.setTravelDate(travelDate);
        p.setDistanceKm(distance.getDistanceKm());
        p.setRuleVersionId(version.getId());
        p.setRuleVersionNo(version.getVersionNo());
        p.setCalcMode(tariff.getCalcMode());
        p.setPerKmRate(tariff.getPerKmRate());
        p.setCapAmount(tariff.getCapAmount());
        p.setFixedAmount(tariff.getFixedAmount());
        p.setReceiptRequired(tariff.getReceiptRequired());
        p.setEstimatedAmount(calc.getAmount());
        p.setNote(InvigilateFeeConstants.CALC_FIXED.equals(tariff.getCalcMode())
                ? "按规则版本 " + version.getVersionNo() + " 定额 " + calc.getAmount().toPlainString() + " 元"
                : "按规则版本 " + version.getVersionNo() + " 单价 "
                        + (tariff.getPerKmRate() == null ? "0" : tariff.getPerKmRate().toPlainString())
                        + " 元/公里 × 里程 " + distance.getDistanceKm().toPlainString()
                        + (tariff.getCapAmount() != null ? "，封顶 " + tariff.getCapAmount().toPlainString() : "")
                        + " = " + calc.getAmount().toPlainString() + " 元");
        return p;
    }

    private String appendNote(String origin, String note) {
        if (origin == null || origin.trim().isEmpty()) {
            return note;
        }
        return origin + "；" + note;
    }

    /** 一次出行试算所需的解析中间结构。 */
    static class EstimateContext {
        private ExamBatch batch;
        private ExamSite site;
        private String project;
        private SiteDistance distance;
        private LocalDate travelDate;
        private TravelAllowanceRuleVersion version;
        private TravelRuleService.CalcResult calcResult;
        private EstimatePreview preview;
    }
}
