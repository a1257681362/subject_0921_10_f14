package com.evops.handbook.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.common.BusinessException;
import com.evops.common.OperatorHolder;
import com.evops.exam.entity.ExamBatch;
import com.evops.exam.entity.ExamSite;
import com.evops.exam.mapper.ExamBatchMapper;
import com.evops.exam.mapper.ExamSiteMapper;
import com.evops.handbook.common.HandbookConstants;
import com.evops.handbook.dto.HandbookAdjustRequest;
import com.evops.handbook.dto.HandbookCreateRequest;
import com.evops.handbook.dto.HandbookReissueRequest;
import com.evops.handbook.dto.HandbookReleaseRequest;
import com.evops.handbook.entity.HandbookNotice;
import com.evops.handbook.entity.HandbookRelease;
import com.evops.handbook.entity.HandbookVersion;
import com.evops.handbook.entity.InvigilationHandbook;
import com.evops.handbook.mapper.HandbookNoticeMapper;
import com.evops.handbook.mapper.HandbookReleaseMapper;
import com.evops.handbook.mapper.HandbookVersionMapper;
import com.evops.handbook.mapper.InvigilationHandbookMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 监考手册：
 * - 模块（手册主表，一个批次一本）/ 版本（只追加）/ 状态（当前版本）三段；
 * - 内容写入即 PENDING_CONFIRM，负责人放行才冻结内容并生成打印版（RELEASED）；
 * - 后续调整另建新版本，放行后旧版 SUPERSEDED，全程通知考点；
 * - 下载权限跟随考点范围（X-Site-Code）；过期手册自动归档，归档后不可下载；
 * - 补发沿用原发布批次号、生效时刻与经办人，另记补发经办人与钟点。
 */
@Service
public class HandbookService {

    private final InvigilationHandbookMapper handbookMapper;
    private final HandbookVersionMapper versionMapper;
    private final HandbookReleaseMapper releaseMapper;
    private final HandbookNoticeMapper noticeMapper;
    private final ExamBatchMapper batchMapper;
    private final ExamSiteMapper siteMapper;
    private final HandbookPrintService printService;

    public HandbookService(InvigilationHandbookMapper handbookMapper,
                           HandbookVersionMapper versionMapper,
                           HandbookReleaseMapper releaseMapper,
                           HandbookNoticeMapper noticeMapper,
                           ExamBatchMapper batchMapper,
                           ExamSiteMapper siteMapper,
                           HandbookPrintService printService) {
        this.handbookMapper = handbookMapper;
        this.versionMapper = versionMapper;
        this.releaseMapper = releaseMapper;
        this.noticeMapper = noticeMapper;
        this.batchMapper = batchMapper;
        this.siteMapper = siteMapper;
        this.printService = printService;
    }

    // ---------- 写入（待确认） ----------

    @Transactional
    public InvigilationHandbook create(HandbookCreateRequest request) {
        ExamBatch batch = requireBatch(request.getBatchId());
        ExamSite site = requireSite(batch.getSiteId());

        Long existing = handbookMapper.selectCount(new QueryWrapper<InvigilationHandbook>()
                .eq("batch_id", batch.getId()));
        if (existing != null && existing > 0) {
            throw new BusinessException("HANDBOOK_ALREADY_EXISTS",
                    "该批次监考手册已存在，调整请走另建版本：batchId=" + batch.getId());
        }

        String operator = OperatorHolder.current();
        LocalDateTime now = LocalDateTime.now();

        InvigilationHandbook handbook = new InvigilationHandbook();
        handbook.setHandbookNo("HB" + batch.getCode());
        handbook.setBatchId(batch.getId());
        handbook.setBatchCode(batch.getCode());
        handbook.setBatchName(batch.getName());
        handbook.setSiteId(site.getId());
        handbook.setSiteCode(site.getCode());
        handbook.setSiteName(site.getName());
        handbook.setProjectCode(batch.getProjectCode());
        handbook.setStatus(HandbookConstants.HANDBOOK_PENDING);
        handbook.setExpireAt(request.getExpireAt());
        handbook.setOperator(operator);
        handbook.setRemark(request.getRemark());
        handbookMapper.insert(handbook);

        HandbookVersion version = new HandbookVersion();
        version.setHandbookId(handbook.getId());
        version.setVersionNo(1);
        version.setChangeType(HandbookConstants.CHANGE_CREATE);
        version.setStatus(HandbookConstants.VERSION_PENDING);
        version.setPostDescription(request.getPostDescription());
        version.setSignInLocation(request.getSignInLocation());
        version.setEmergencyContact(request.getEmergencyContact());
        version.setEmergencyPhone(request.getEmergencyPhone());
        version.setSubmittedBy(operator);
        version.setSubmittedAt(now);
        versionMapper.insert(version);

        handbook.setCurrentVersionId(version.getId());
        handbook.setCurrentVersionNo(version.getVersionNo());
        handbookMapper.updateById(handbook);
        return handbook;
    }

    // ---------- 调整（另建版本，先待确认） ----------

    @Transactional
    public HandbookVersion adjust(Long handbookId, HandbookAdjustRequest request) {
        InvigilationHandbook handbook = requireHandbook(handbookId);
        if (HandbookConstants.HANDBOOK_ARCHIVED.equals(handbook.getStatus())) {
            throw new BusinessException("HANDBOOK_ARCHIVED", "手册已归档，不能再调整：" + handbook.getHandbookNo());
        }
        HandbookVersion latest = latestVersion(handbookId);

        // 已有待确认版本未放行：由本次调整顶替，同一时刻只保留一份待确认稿。
        if (HandbookConstants.VERSION_PENDING.equals(latest.getStatus())) {
            latest.setStatus(HandbookConstants.VERSION_SUPERSEDED);
            versionMapper.updateById(latest);
        }

        HandbookVersion version = new HandbookVersion();
        version.setHandbookId(handbookId);
        version.setVersionNo(latest.getVersionNo() + 1);
        version.setChangeType(HandbookConstants.CHANGE_ADJUST);
        version.setStatus(HandbookConstants.VERSION_PENDING);
        // 留空字段沿用上一版
        version.setPostDescription(
                request.getPostDescription() == null || request.getPostDescription().trim().isEmpty()
                        ? latest.getPostDescription() : request.getPostDescription());
        version.setSignInLocation(
                request.getSignInLocation() == null || request.getSignInLocation().trim().isEmpty()
                        ? latest.getSignInLocation() : request.getSignInLocation());
        version.setEmergencyContact(
                request.getEmergencyContact() == null || request.getEmergencyContact().trim().isEmpty()
                        ? latest.getEmergencyContact() : request.getEmergencyContact());
        version.setEmergencyPhone(
                request.getEmergencyPhone() == null || request.getEmergencyPhone().trim().isEmpty()
                        ? latest.getEmergencyPhone() : request.getEmergencyPhone());
        version.setAdjustReason(request.getReason());
        version.setSubmittedBy(OperatorHolder.current());
        version.setSubmittedAt(LocalDateTime.now());
        versionMapper.insert(version);

        handbook.setCurrentVersionId(version.getId());
        handbook.setCurrentVersionNo(version.getVersionNo());
        // 已有生效版时手册保持 RELEASED：新版放行前，旧打印版仍可下载；首次写入的手册才停留在待确认。
        if (!HandbookConstants.HANDBOOK_RELEASED.equals(handbook.getStatus())) {
            handbook.setStatus(HandbookConstants.HANDBOOK_PENDING);
        }
        if (request.getExpireAt() != null) {
            handbook.setExpireAt(request.getExpireAt());
        }
        handbookMapper.updateById(handbook);
        return version;
    }

    // ---------- 负责人放行（生成打印版） ----------

    @Transactional
    public HandbookRelease release(Long handbookId, HandbookReleaseRequest request) {
        InvigilationHandbook handbook = requireHandbook(handbookId);
        if (HandbookConstants.HANDBOOK_ARCHIVED.equals(handbook.getStatus())) {
            throw new BusinessException("HANDBOOK_ARCHIVED", "手册已归档，不能放行：" + handbook.getHandbookNo());
        }
        HandbookVersion pending = latestVersion(handbookId);
        if (!HandbookConstants.VERSION_PENDING.equals(pending.getStatus())) {
            throw new BusinessException("HANDBOOK_NO_PENDING_VERSION",
                    "当前没有待确认版本可放行（已放行的调整须先另建版本）：" + handbook.getHandbookNo());
        }

        String operator = OperatorHolder.current();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime effectiveAt = request != null && request.getEffectiveAt() != null
                ? request.getEffectiveAt() : now;

        // 旧生效版本让位
        List<HandbookVersion> released = versionMapper.selectList(new QueryWrapper<HandbookVersion>()
                .eq("handbook_id", handbookId)
                .eq("status", HandbookConstants.VERSION_RELEASED));
        for (HandbookVersion old : released) {
            old.setStatus(HandbookConstants.VERSION_SUPERSEDED);
            versionMapper.updateById(old);
        }

        String kind = HandbookConstants.CHANGE_ADJUST.equals(pending.getChangeType())
                ? HandbookConstants.RELEASE_ADJUST : HandbookConstants.RELEASE_INITIAL;
        String releaseNo = nextReleaseNo(handbookId);

        HandbookRelease record = new HandbookRelease();
        record.setHandbookId(handbookId);
        record.setReleaseNo(releaseNo);
        record.setVersionId(pending.getId());
        record.setVersionNo(pending.getVersionNo());
        record.setKind(kind);
        record.setEffectiveAt(effectiveAt);
        record.setOperator(operator);
        record.setReleasedAt(now);
        releaseMapper.insert(record);

        // 放行时生成并冻结打印版（打印 HTML 只此一份，调整不改历史打印版）
        String printHtml = printService.renderPrintHtml(handbook, pending, record);
        pending.setStatus(HandbookConstants.VERSION_RELEASED);
        pending.setReleasedBy(operator);
        pending.setReleasedAt(now);
        pending.setEffectiveAt(effectiveAt);
        pending.setPrintHtml(printHtml);
        pending.setPrintGeneratedAt(now);
        versionMapper.updateById(pending);

        handbook.setStatus(HandbookConstants.HANDBOOK_RELEASED);
        handbook.setCurrentVersionId(pending.getId());
        handbook.setCurrentVersionNo(pending.getVersionNo());
        handbookMapper.updateById(handbook);

        String noticeType = HandbookConstants.RELEASE_ADJUST.equals(kind)
                ? HandbookConstants.NOTICE_ADJUST : HandbookConstants.NOTICE_RELEASE;
        String title = HandbookConstants.RELEASE_ADJUST.equals(kind)
                ? "监考手册调整版已放行（V" + pending.getVersionNo() + "）"
                : "监考手册已放行发布";
        String content = "批次 " + handbook.getBatchCode() + " 监考手册 V" + pending.getVersionNo()
                + " 已由 " + operator + " 放行，发布批次 " + releaseNo
                + "，生效时刻 " + effectiveAt + "，请按考点范围下载打印版并组织监考。";
        insertNotice(handbook, record.getId(), pending.getId(), noticeType, title, content, operator, now);
        return record;
    }

    // ---------- 补发（保留经办人、生效时刻、发布批次） ----------

    @Transactional
    public HandbookRelease reissue(Long handbookId, HandbookReissueRequest request) {
        InvigilationHandbook handbook = requireHandbook(handbookId);
        if (!HandbookConstants.HANDBOOK_RELEASED.equals(handbook.getStatus())) {
            throw new BusinessException("HANDBOOK_NOT_RELEASED",
                    "仅已放行手册可补发：" + handbook.getHandbookNo());
        }

        HandbookRelease origin;
        if (request != null && request.getOriginReleaseId() != null) {
            origin = releaseMapper.selectById(request.getOriginReleaseId());
            if (origin == null || !origin.getHandbookId().equals(handbookId)) {
                throw new BusinessException("RELEASE_NOT_FOUND",
                        "原发布批次不存在或不属于本手册：id=" + request.getOriginReleaseId());
            }
        } else {
            // 默认补发最近一个正式发布批次（INITIAL/ADJUST），保证始终挂在同一原批次上
            List<HandbookRelease> formal = releaseMapper.selectList(new QueryWrapper<HandbookRelease>()
                    .eq("handbook_id", handbookId)
                    .in("kind", HandbookConstants.RELEASE_INITIAL, HandbookConstants.RELEASE_ADJUST)
                    .orderByDesc("id"));
            if (formal.isEmpty()) {
                throw new BusinessException("RELEASE_NOT_FOUND", "手册尚无正式发布批次，无法补发");
            }
            origin = formal.get(0);
        }

        String reissuedBy = OperatorHolder.current();
        LocalDateTime now = LocalDateTime.now();

        HandbookRelease record = new HandbookRelease();
        record.setHandbookId(handbookId);
        // 发布批次沿用原批次号
        record.setReleaseNo(origin.getReleaseNo());
        record.setVersionId(origin.getVersionId());
        record.setVersionNo(origin.getVersionNo());
        record.setKind(HandbookConstants.RELEASE_REISSUE);
        record.setOriginReleaseId(origin.getId());
        // 生效时刻沿用原发布批次
        record.setEffectiveAt(origin.getEffectiveAt());
        // 经办人保留原经办人，补发经办人另记
        record.setOperator(origin.getOperator());
        record.setReissuedBy(reissuedBy);
        record.setReleasedAt(now);
        record.setReason(request == null ? null : request.getReason());
        releaseMapper.insert(record);

        String title = "监考手册补发（发布批次 " + origin.getReleaseNo() + "）";
        String content = "批次 " + handbook.getBatchCode() + " 监考手册 V" + origin.getVersionNo()
                + " 补发，沿用发布批次 " + origin.getReleaseNo() + "、生效时刻 " + origin.getEffectiveAt()
                + " 与原经办人 " + origin.getOperator() + "；补发经办 " + reissuedBy + "。";
        insertNotice(handbook, record.getId(), origin.getVersionId(),
                HandbookConstants.NOTICE_REISSUE, title, content, reissuedBy, now);
        return record;
    }

    // ---------- 归档（人工 / 定时自动） ----------

    @Transactional
    public int archive(Long handbookId, String reason) {
        InvigilationHandbook handbook = requireHandbook(handbookId);
        return doArchive(handbook, OperatorHolder.current(), reason);
    }

    /** 定时扫描：过期（expire_at <= now）且未归档的手册自动归档。返回归档数量。 */
    @Transactional
    public int sweepExpired() {
        List<InvigilationHandbook> due = handbookMapper.selectList(new QueryWrapper<InvigilationHandbook>()
                .ne("status", HandbookConstants.HANDBOOK_ARCHIVED)
                .isNotNull("expire_at")
                .le("expire_at", LocalDateTime.now())
                .orderByAsc("id"));
        int count = 0;
        for (InvigilationHandbook handbook : due) {
            doArchive(handbook, HandbookConstants.SYSTEM_OPERATOR, "超过手册有效期，系统自动归档");
            count++;
        }
        return count;
    }

    private int doArchive(InvigilationHandbook handbook, String operator, String reason) {
        if (HandbookConstants.HANDBOOK_ARCHIVED.equals(handbook.getStatus())) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now();
        List<HandbookVersion> versions = versionMapper.selectList(new QueryWrapper<HandbookVersion>()
                .eq("handbook_id", handbook.getId()));
        for (HandbookVersion v : versions) {
            if (!HandbookConstants.VERSION_ARCHIVED.equals(v.getStatus())) {
                v.setStatus(HandbookConstants.VERSION_ARCHIVED);
                v.setArchivedAt(now);
                versionMapper.updateById(v);
            }
        }
        handbook.setStatus(HandbookConstants.HANDBOOK_ARCHIVED);
        handbook.setArchivedBy(operator);
        handbook.setArchivedAt(now);
        handbook.setArchiveReason(reason);
        handbookMapper.updateById(handbook);

        String title = "监考手册已归档";
        String content = "批次 " + handbook.getBatchCode() + " 监考手册已归档（" + reason
                + "），归档后不再提供下载。";
        insertNotice(handbook, null, handbook.getCurrentVersionId(),
                HandbookConstants.NOTICE_ARCHIVE, title, content, operator, now);
        return 1;
    }

    // ---------- 查询（下载权限跟随考点范围） ----------

    @Transactional(readOnly = true)
    public List<InvigilationHandbook> list(String siteCode) {
        QueryWrapper<InvigilationHandbook> wrapper = new QueryWrapper<InvigilationHandbook>()
                .orderByDesc("id");
        if (siteCode != null && !siteCode.trim().isEmpty()) {
            wrapper.eq("site_code", siteCode.trim());
        }
        return handbookMapper.selectList(wrapper);
    }

    @Transactional(readOnly = true)
    public InvigilationHandbook get(Long handbookId, String siteCode) {
        InvigilationHandbook handbook = requireHandbook(handbookId);
        checkSiteScope(handbook, siteCode);
        return handbook;
    }

    @Transactional(readOnly = true)
    public List<HandbookVersion> listVersions(Long handbookId, String siteCode) {
        InvigilationHandbook handbook = requireHandbook(handbookId);
        checkSiteScope(handbook, siteCode);
        List<HandbookVersion> versions = versionMapper.selectList(new QueryWrapper<HandbookVersion>()
                .eq("handbook_id", handbookId)
                .orderByAsc("version_no"));
        // 列表不带打印大字段
        for (HandbookVersion v : versions) {
            v.setPrintHtml(null);
        }
        return versions;
    }

    @Transactional(readOnly = true)
    public List<HandbookRelease> listReleases(Long handbookId, String siteCode) {
        InvigilationHandbook handbook = requireHandbook(handbookId);
        checkSiteScope(handbook, siteCode);
        return releaseMapper.selectList(new QueryWrapper<HandbookRelease>()
                .eq("handbook_id", handbookId)
                .orderByAsc("id"));
    }

    @Transactional(readOnly = true)
    public List<HandbookNotice> listNotices(Long handbookId, String siteCode) {
        InvigilationHandbook handbook = requireHandbook(handbookId);
        checkSiteScope(handbook, siteCode);
        return noticeMapper.selectList(new QueryWrapper<HandbookNotice>()
                .eq("handbook_id", handbookId)
                .orderByDesc("id"));
    }

    @Transactional
    public HandbookNotice markNoticeRead(Long noticeId, String siteCode) {
        HandbookNotice notice = noticeMapper.selectById(noticeId);
        if (notice == null) {
            throw new BusinessException("HANDBOOK_NOTICE_NOT_FOUND", "手册通知不存在：id=" + noticeId);
        }
        InvigilationHandbook handbook = requireHandbook(notice.getHandbookId());
        checkSiteScope(handbook, siteCode);
        if (HandbookConstants.NOTICE_NEW.equals(notice.getStatus())) {
            notice.setStatus(HandbookConstants.NOTICE_READ);
            notice.setReadAt(LocalDateTime.now());
            noticeMapper.updateById(notice);
        }
        return notice;
    }

    /**
     * 下载打印版：仅 RELEASED 手册可下载；考点范围不匹配拒绝；归档后拒绝。
     * releaseId 缺省取当前生效版本放行时冻结的打印版；补发批次现场渲染带补发抬头的同版内容。
     */
    @Transactional(readOnly = true)
    public String viewPrintHtml(Long handbookId, Long releaseId, String siteCode) {
        InvigilationHandbook handbook = requireHandbook(handbookId);
        checkSiteScope(handbook, siteCode);
        if (HandbookConstants.HANDBOOK_PENDING.equals(handbook.getStatus())) {
            throw new BusinessException("HANDBOOK_NOT_RELEASED", "手册尚待负责人放行，未生成打印版本");
        }
        if (HandbookConstants.HANDBOOK_ARCHIVED.equals(handbook.getStatus())) {
            throw new BusinessException("HANDBOOK_ARCHIVED", "手册已归档，打印版本不再提供下载");
        }

        HandbookRelease release;
        HandbookVersion version;
        if (releaseId != null) {
            release = releaseMapper.selectById(releaseId);
            if (release == null || !release.getHandbookId().equals(handbookId)) {
                throw new BusinessException("RELEASE_NOT_FOUND", "发布批次不存在或不属于本手册：id=" + releaseId);
            }
            version = versionMapper.selectById(release.getVersionId());
        } else {
            // 缺省取最近已放行版本（可能存在更新的待确认调整稿，其打印版尚未生成，不提供）
            List<HandbookVersion> releasedVersions = versionMapper.selectList(new QueryWrapper<HandbookVersion>()
                    .eq("handbook_id", handbookId)
                    .eq("status", HandbookConstants.VERSION_RELEASED)
                    .orderByDesc("version_no"));
            if (releasedVersions.isEmpty()) {
                throw new BusinessException("HANDBOOK_NOT_RELEASED", "手册尚待负责人放行，未生成打印版本");
            }
            version = releasedVersions.get(0);
            List<HandbookRelease> formal = releaseMapper.selectList(new QueryWrapper<HandbookRelease>()
                    .eq("handbook_id", handbookId)
                    .eq("version_id", version.getId())
                    .in("kind", HandbookConstants.RELEASE_INITIAL, HandbookConstants.RELEASE_ADJUST)
                    .orderByDesc("id"));
            release = formal.get(0);
        }

        if (HandbookConstants.RELEASE_REISSUE.equals(release.getKind())) {
            // 补发：版本内容与冻结版一致，抬头补注补发经办与钟点
            return printService.renderPrintHtml(handbook, version, release);
        }
        String frozen = version.getPrintHtml();
        if (frozen == null || frozen.trim().isEmpty()) {
            // 兜底：历史数据缺打印版时现场补渲染
            return printService.renderPrintHtml(handbook, version, release);
        }
        return frozen;
    }

    // ---------- 内部 ----------

    private void insertNotice(InvigilationHandbook handbook, Long releaseId, Long versionId,
                              String type, String title, String content,
                              String operator, LocalDateTime now) {
        HandbookNotice notice = new HandbookNotice();
        notice.setHandbookId(handbook.getId());
        notice.setReleaseId(releaseId);
        notice.setVersionId(versionId);
        notice.setSiteId(handbook.getSiteId());
        notice.setSiteCode(handbook.getSiteCode());
        notice.setSiteName(handbook.getSiteName());
        notice.setNoticeType(type);
        notice.setTitle(title);
        notice.setContent(content);
        notice.setStatus(HandbookConstants.NOTICE_NEW);
        notice.setOperator(operator);
        notice.setNoticedAt(now);
        noticeMapper.insert(notice);
    }

    /** 发布批次号：R + 手册内正式发布次数递增（补发不递增，沿用原号）。 */
    private String nextReleaseNo(Long handbookId) {
        List<HandbookRelease> formal = releaseMapper.selectList(new QueryWrapper<HandbookRelease>()
                .eq("handbook_id", handbookId)
                .in("kind", HandbookConstants.RELEASE_INITIAL, HandbookConstants.RELEASE_ADJUST));
        return "R" + (formal.size() + 1);
    }

    private HandbookVersion latestVersion(Long handbookId) {
        List<HandbookVersion> versions = versionMapper.selectList(new QueryWrapper<HandbookVersion>()
                .eq("handbook_id", handbookId)
                .orderByDesc("version_no"));
        if (versions.isEmpty()) {
            throw new BusinessException("HANDBOOK_VERSION_NOT_FOUND", "手册不存在任何版本：id=" + handbookId);
        }
        return versions.get(0);
    }

    private InvigilationHandbook requireHandbook(Long handbookId) {
        if (handbookId == null) {
            throw new BusinessException("HANDBOOK_ID_REQUIRED", "手册 id 不能为空");
        }
        InvigilationHandbook handbook = handbookMapper.selectById(handbookId);
        if (handbook == null) {
            throw new BusinessException("HANDBOOK_NOT_FOUND", "监考手册不存在：id=" + handbookId);
        }
        return handbook;
    }

    private ExamBatch requireBatch(Long batchId) {
        if (batchId == null) {
            throw new BusinessException("BATCH_ID_REQUIRED", "考试批次 id 不能为空");
        }
        ExamBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new BusinessException("BATCH_NOT_FOUND", "考试批次不存在：id=" + batchId);
        }
        return batch;
    }

    private ExamSite requireSite(Long siteId) {
        ExamSite site = siteMapper.selectById(siteId);
        if (site == null) {
            throw new BusinessException("SITE_NOT_FOUND", "考点不存在：id=" + siteId);
        }
        return site;
    }

    /** 考点范围：传了 X-Site-Code 就只能访问本考点手册。 */
    private void checkSiteScope(InvigilationHandbook handbook, String siteCode) {
        if (siteCode != null && !siteCode.trim().isEmpty()
                && !siteCode.trim().equals(handbook.getSiteCode())) {
            throw new BusinessException("HANDBOOK_SITE_FORBIDDEN",
                    "下载权限跟随考点范围，该手册不属于考点：" + siteCode.trim());
        }
    }
}
