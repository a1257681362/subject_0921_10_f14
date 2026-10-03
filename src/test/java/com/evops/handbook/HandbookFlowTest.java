package com.evops.handbook;

import com.evops.common.BusinessException;
import com.evops.exam.entity.ExamBatch;
import com.evops.exam.entity.ExamSite;
import com.evops.exam.mapper.ExamBatchMapper;
import com.evops.exam.mapper.ExamSiteMapper;
import com.evops.exam.service.WorkspaceVersionService;
import com.evops.handbook.common.HandbookConstants;
import com.evops.handbook.dto.HandbookAdjustRequest;
import com.evops.handbook.dto.HandbookCreateRequest;
import com.evops.handbook.dto.HandbookReissueRequest;
import com.evops.handbook.dto.HandbookReleaseRequest;
import com.evops.handbook.entity.HandbookNotice;
import com.evops.handbook.entity.HandbookRelease;
import com.evops.handbook.entity.HandbookVersion;
import com.evops.handbook.entity.InvigilationHandbook;
import com.evops.handbook.service.HandbookService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 监考手册端到端：
 * 写入待确认 → 负责人放行生成打印版（通知考点）→ 调整另建版本再放行（旧版让位）
 * → 补发沿用发布批次/生效时刻/经办人 → 考点范围下载隔离 → 过期自动归档 → 快照回退。
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class HandbookFlowTest {

    @Autowired
    private HandbookService handbookService;
    @Autowired
    private WorkspaceVersionService workspaceVersionService;
    @Autowired
    private ExamSiteMapper siteMapper;
    @Autowired
    private ExamBatchMapper batchMapper;

    private ExamBatch createBatch(String code, String siteCode) {
        ExamSite site = new ExamSite();
        site.setCode(siteCode);
        site.setName("考点-" + siteCode);
        site.setStatus("ENABLED");
        siteMapper.insert(site);

        ExamBatch batch = new ExamBatch();
        batch.setCode(code);
        batch.setName("批次-" + code);
        batch.setSiteId(site.getId());
        batch.setProjectCode("DEFAULT");
        batch.setStatus("CREATED");
        batchMapper.insert(batch);
        return batch;
    }

    private HandbookCreateRequest buildCreate(ExamBatch batch) {
        HandbookCreateRequest req = new HandbookCreateRequest();
        req.setBatchId(batch.getId());
        req.setPostDescription("1. 考前 30 分钟到岗\n2. 核对证件\n3. 宣读纪律");
        req.setSignInLocation("1 号楼 2 层考务办公室");
        req.setEmergencyContact("王主任");
        req.setEmergencyPhone("13800000000");
        return req;
    }

    @Test
    void fullFlow_pendingReleaseAdjustReissueScopeArchiveRestore() {
        ExamBatch batchA = createBatch("HBA", "SITE-A");
        ExamBatch batchB = createBatch("HBB", "SITE-B");

        // 1. 写入即待确认
        InvigilationHandbook handbook = handbookService.create(buildCreate(batchA));
        final Long handbookId = handbook.getId();
        assertEquals(HandbookConstants.HANDBOOK_PENDING, handbook.getStatus());
        assertEquals(1, handbook.getCurrentVersionNo());

        // 同批次重复建档拒收
        assertThrows(BusinessException.class, () -> handbookService.create(buildCreate(batchA)));

        // 待确认时不能下载打印版
        assertThrows(BusinessException.class,
                () -> handbookService.viewPrintHtml(handbookId, null, null));

        // 2. 负责人放行 → 生成打印版
        HandbookReleaseRequest releaseReq = new HandbookReleaseRequest();
        LocalDateTime effective = LocalDateTime.now().minusHours(2);
        releaseReq.setEffectiveAt(effective);
        HandbookRelease r1 = handbookService.release(handbook.getId(), releaseReq);
        assertEquals("R1", r1.getReleaseNo());
        assertEquals(HandbookConstants.RELEASE_INITIAL, r1.getKind());
        assertEquals("system", r1.getOperator());
        assertNull(r1.getReissuedBy());

        handbook = handbookService.get(handbook.getId(), null);
        assertEquals(HandbookConstants.HANDBOOK_RELEASED, handbook.getStatus());
        String printHtml = handbookService.viewPrintHtml(handbook.getId(), null, null);
        assertTrue(printHtml.contains("监 考 手 册"));
        assertTrue(printHtml.contains("王主任"));
        assertTrue(printHtml.contains("R1"));

        // 放行通知考点
        List<HandbookNotice> notices = handbookService.listNotices(handbook.getId(), null);
        assertEquals(1, notices.size());
        assertEquals(HandbookConstants.NOTICE_RELEASE, notices.get(0).getNoticeType());
        assertEquals("SITE-A", notices.get(0).getSiteCode());

        // 无待确认版本时重复放行拒收
        assertThrows(BusinessException.class,
                () -> handbookService.release(handbookId, null));

        // 3. 调整另建版本：只改应急联系人，其余沿用上一版
        HandbookAdjustRequest adjustReq = new HandbookAdjustRequest();
        adjustReq.setEmergencyContact("李主任");
        adjustReq.setEmergencyPhone("13900000000");
        adjustReq.setReason("应急联系人更换");
        HandbookVersion v2 = handbookService.adjust(handbook.getId(), adjustReq);
        assertEquals(2, v2.getVersionNo());
        assertEquals(HandbookConstants.CHANGE_ADJUST, v2.getChangeType());
        assertEquals(HandbookConstants.VERSION_PENDING, v2.getStatus());
        // 岗位说明沿用
        assertTrue(v2.getPostDescription().contains("考前 30 分钟"));
        assertEquals("李主任", v2.getEmergencyContact());

        // 手册保持 RELEASED：调整稿放行前，旧打印版仍可下载（内容仍为旧联系人）
        assertEquals(HandbookConstants.HANDBOOK_RELEASED,
                handbookService.get(handbook.getId(), null).getStatus());
        String printDuringAdjust = handbookService.viewPrintHtml(handbook.getId(), null, null);
        assertTrue(printDuringAdjust.contains("王主任"));

        HandbookRelease r2 = handbookService.release(handbook.getId(), null);
        assertEquals("R2", r2.getReleaseNo());
        assertEquals(HandbookConstants.RELEASE_ADJUST, r2.getKind());
        // 旧版让位 SUPERSEDED
        List<HandbookVersion> versions = handbookService.listVersions(handbook.getId(), null);
        assertEquals(HandbookConstants.VERSION_SUPERSEDED, versions.get(0).getStatus());
        assertEquals(HandbookConstants.VERSION_RELEASED, versions.get(1).getStatus());
        // 调整放行通知
        List<HandbookNotice> afterAdjust = handbookService.listNotices(handbook.getId(), null);
        assertEquals(HandbookConstants.NOTICE_ADJUST, afterAdjust.get(0).getNoticeType());

        // 当前打印版含新联系人
        String print2 = handbookService.viewPrintHtml(handbook.getId(), null, null);
        assertTrue(print2.contains("李主任"));

        // 4. 补发：沿用发布批次号、生效时刻、经办人
        HandbookReissueRequest reissueReq = new HandbookReissueRequest();
        reissueReq.setReason("考点打印遗失补发");
        HandbookRelease reissue = handbookService.reissue(handbook.getId(), reissueReq);
        assertEquals(HandbookConstants.RELEASE_REISSUE, reissue.getKind());
        assertEquals("R2", reissue.getReleaseNo());
        assertEquals(r2.getEffectiveAt(), reissue.getEffectiveAt());
        assertEquals(r2.getOperator(), reissue.getOperator());
        assertEquals("system", reissue.getReissuedBy());
        assertEquals(r2.getId(), reissue.getOriginReleaseId());

        // 补发放行批次页面带补发抬头
        String reprint = handbookService.viewPrintHtml(handbook.getId(), reissue.getId(), null);
        assertTrue(reprint.contains("补发"));

        // 5. 下载权限跟随考点范围
        // 另一考点看不到
        assertTrue(handbookService.list("SITE-B").isEmpty());
        assertEquals(1, handbookService.list("SITE-A").size());
        // 越权访问他考点手册：先建 B 考点手册并放行
        InvigilationHandbook handbookB = handbookService.create(buildCreate(batchB));
        final Long handbookBId = handbookB.getId();
        // 用 SITE-A 范围访问 B 手册被拒
        assertThrows(BusinessException.class,
                () -> handbookService.get(handbookBId, "SITE-A"));
        assertThrows(BusinessException.class,
                () -> handbookService.viewPrintHtml(handbookBId, null, "SITE-A"));
        // 本考点可访问
        assertNotNull(handbookService.get(handbookB.getId(), "SITE-B"));

        // 6. 过期自动归档
        InvigilationHandbook expired = handbookService.create(
                expireBatchHandbook(createBatch("HBC", "SITE-C"), LocalDateTime.now().minusMinutes(1)));
        assertEquals(1, handbookService.sweepExpired());
        InvigilationHandbook archived = handbookService.get(expired.getId(), null);
        assertEquals(HandbookConstants.HANDBOOK_ARCHIVED, archived.getStatus());
        // 归档后不可下载
        assertThrows(BusinessException.class,
                () -> handbookService.viewPrintHtml(expired.getId(), null, null));
        // 归档通知
        List<HandbookNotice> archiveNotices = handbookService.listNotices(expired.getId(), null);
        assertEquals(HandbookConstants.NOTICE_ARCHIVE, archiveNotices.get(0).getNoticeType());
        // 归档后不能再调整
        assertThrows(BusinessException.class,
                () -> handbookService.adjust(expired.getId(), adjustReq));
        // 再扫一次幂等
        assertEquals(0, handbookService.sweepExpired());

        // 7. 快照回退：手册表纳入工作区版本
        int beforeRestore = handbookService.list(null).size();
        com.evops.exam.entity.WorkspaceVersion snap = workspaceVersionService.createSnapshot("手册快照", null);
        // 回退后再建一本，回退应恢复到快照时刻数量
        ExamBatch batchD = createBatch("HBD", "SITE-D");
        handbookService.create(buildCreate(batchD));
        assertEquals(beforeRestore + 1, handbookService.list(null).size());
        workspaceVersionService.rollback(snap.getVersionNo(), "回退手册快照");
        assertEquals(beforeRestore, handbookService.list(null).size());
    }

    private HandbookCreateRequest expireBatchHandbook(ExamBatch batch, LocalDateTime expireAt) {
        HandbookCreateRequest req = buildCreate(batch);
        req.setExpireAt(expireAt);
        return req;
    }
}
