package com.evops.capacity;

import com.evops.capacity.common.CapacityConstants;
import com.evops.capacity.dto.CapacityParams;
import com.evops.capacity.dto.CapacityRuleRequest;
import com.evops.capacity.dto.CapacityRuleVersionRequest;
import com.evops.capacity.dto.WaitlistActionRequest;
import com.evops.capacity.dto.WaitlistJoinRequest;
import com.evops.capacity.entity.CapacityNotification;
import com.evops.capacity.entity.CapacityRule;
import com.evops.capacity.entity.CapacityState;
import com.evops.capacity.entity.CapacityWaitlist;
import com.evops.capacity.mapper.CapacityStateMapper;
import com.evops.capacity.mapper.CapacityWaitlistMapper;
import com.evops.capacity.service.CapacityRuleService;
import com.evops.capacity.service.WaitlistService;
import com.evops.capacity.vo.CapacityStateView;
import com.evops.capacity.vo.WaitlistPositionView;
import com.evops.common.BusinessException;
import com.evops.exam.common.ExamConstants;
import com.evops.exam.entity.ExamBatch;
import com.evops.exam.entity.ExamSite;
import com.evops.exam.entity.Registration;
import com.evops.exam.mapper.ExamBatchMapper;
import com.evops.exam.mapper.ExamSiteMapper;
import com.evops.exam.mapper.RegistrationMapper;
import com.evops.exam.service.WorkspaceVersionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 热门考点容量控制与候补递补端到端：
 * 三段式配置 → 满员入队（重复请求不插队、位置透明）→ 退位按序递补（通知带时限）
 * → 服务器钟点判超时顺延（双方通知不漏）→ 时限内确认锁座 → 窗口关闭拒收 → 快照回退。
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CapacityFlowTest {

    private static final LocalDateTime NOW = LocalDateTime.now();

    @Autowired
    private CapacityRuleService ruleService;
    @Autowired
    private WaitlistService waitlistService;
    @Autowired
    private WorkspaceVersionService workspaceVersionService;
    @Autowired
    private ExamSiteMapper siteMapper;
    @Autowired
    private ExamBatchMapper batchMapper;
    @Autowired
    private RegistrationMapper registrationMapper;
    @Autowired
    private CapacityStateMapper stateMapper;
    @Autowired
    private CapacityWaitlistMapper waitlistMapper;

    // ---------- 辅助 ----------

    private ExamBatch createBatch(String code) {
        ExamSite site = new ExamSite();
        site.setCode("S" + code);
        site.setName("热门考点-" + code);
        site.setStatus("ENABLED");
        siteMapper.insert(site);
        ExamBatch batch = new ExamBatch();
        batch.setCode(code);
        batch.setName("批次-" + code);
        batch.setSiteId(site.getId());
        batch.setProjectCode("ZHUKAO");
        batch.setStatus(ExamConstants.BATCH_CREATED);
        batchMapper.insert(batch);
        return batch;
    }

    private CapacityRule publishRule(ExamBatch batch, String ruleCode, int capacity,
                                     LocalDateTime windowStart, LocalDateTime windowEnd,
                                     int timeoutMinutes) {
        CapacityRuleRequest rr = new CapacityRuleRequest();
        rr.setCode(ruleCode);
        rr.setName("容量规则-" + ruleCode);
        rr.setBatchId(batch.getId());
        CapacityRule rule = ruleService.createRule(rr);
        publishVersion(rule.getId(), capacity, windowStart, windowEnd, timeoutMinutes);
        return ruleService.getRule(rule.getId());
    }

    /** 同一条规则下发新版本（旧 ACTIVE 自动停用）：用于模拟窗口改期/容量调整。 */
    private void publishVersion(Long ruleId, int capacity,
                                LocalDateTime windowStart, LocalDateTime windowEnd, int timeoutMinutes) {
        CapacityParams params = new CapacityParams();
        params.setWindowStart(windowStart);
        params.setWindowEnd(windowEnd);
        params.setOfferTimeoutMinutes(timeoutMinutes);
        params.setCapacity(capacity);
        CapacityRuleVersionRequest vr = new CapacityRuleVersionRequest();
        vr.setParams(params);
        ruleService.publishVersion(ruleService.createDraftVersion(ruleId, vr).getId());
    }

    private void enroll(ExamBatch batch, String regNo, String name) {
        Registration reg = new Registration();
        reg.setRegNo(regNo);
        reg.setName(name);
        reg.setIdType("ID_CARD");
        reg.setBatchId(batch.getId());
        reg.setSubject(batch.getSubject());
        reg.setStatus(ExamConstants.REG_REGISTERED);
        reg.setCorrectionCount(0);
        registrationMapper.insert(reg);
    }

    private WaitlistJoinRequest joinReq(Long ruleId, String regNo, String name) {
        WaitlistJoinRequest req = new WaitlistJoinRequest();
        req.setRuleId(ruleId);
        req.setRegNo(regNo);
        req.setName(name);
        return req;
    }

    private WaitlistActionRequest action(Long ruleId, String regNo) {
        WaitlistActionRequest req = new WaitlistActionRequest();
        req.setRuleId(ruleId);
        req.setRegNo(regNo);
        return req;
    }

    private CapacityWaitlist row(Long ruleId, String regNo) {
        return waitlistMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<CapacityWaitlist>()
                .eq("rule_id", ruleId).eq("reg_no", regNo));
    }

    private List<CapacityNotification> notifications(String waitlistRegNo, String type) {
        return waitlistService.notifications(waitlistRegNo, null, false).stream()
                .filter(n -> type == null || type.equals(n.getNotifyType()))
                .collect(java.util.stream.Collectors.toList());
    }

    // ---------- 用例 ----------

    @Test
    void fullFlow_join_offer_expireNext_confirmSeat_windowCloseReject() {
        ExamBatch batch = createBatch("CAP01");
        // 容量 2，窗口此刻开放，递补确认时限 60 分钟
        CapacityRule rule = publishRule(batch, "CAPR01", 2,
                NOW.minusHours(1), NOW.plusDays(1), 60);

        // 未满员不候补
        enroll(batch, "R001", "在册甲");
        assertThrows(BusinessException.class, () -> waitlistService.join(joinReq(rule.getId(), "W001", "候补一")));
        enroll(batch, "R002", "在册乙");

        // 满员入队，位置透明：第 1、2 位
        WaitlistPositionView p1 = waitlistService.join(joinReq(rule.getId(), "W001", "候补一"));
        assertEquals(CapacityConstants.WAIT_WAITING, p1.getStatus());
        assertEquals(1, p1.getPosition());
        assertEquals(0, p1.getAheadCount());
        WaitlistPositionView p2 = waitlistService.join(joinReq(rule.getId(), "W002", "候补二"));
        assertEquals(2, p2.getPosition());
        assertEquals(1, p2.getAheadCount());

        // 重复请求不许插队：返回原序号原位置，不新建
        WaitlistPositionView p1Again = waitlistService.join(joinReq(rule.getId(), "W001", "候补一"));
        assertEquals(p1.getSeq(), p1Again.getSeq());
        assertEquals(1, p1Again.getPosition());
        assertEquals(2, waitlistService.list(rule.getId(), null, CapacityConstants.WAIT_WAITING).size());

        // 状态总览：容量 2、占 2、候补 2、余量 0
        CapacityStateView state = waitlistService.stateView(rule.getId());
        assertTrue(state.isActive());
        assertEquals(CapacityConstants.WINDOW_OPEN, state.getWindowStatus());
        assertEquals(2, state.getCapacity());
        assertEquals(2, state.getOccupiedCount());
        assertEquals(2, state.getWaitingCount());
        assertEquals(0, state.getAvailableCount());

        // 一人退位 → 首位递补，通知带确认截止钟点
        waitlistService.resign(rule.getId(), null, "R001", "个人原因");
        CapacityWaitlist w1 = row(rule.getId(), "W001");
        assertEquals(CapacityConstants.WAIT_OFFERED, w1.getStatus());
        assertEquals(1, w1.getOfferRound());
        assertNotNull(w1.getOfferDeadline());
        assertTrue(w1.getOfferDeadline().isAfter(NOW));
        List<CapacityNotification> offers = notifications("W001", CapacityConstants.NOTIFY_OFFER);
        assertEquals(1, offers.size());
        assertEquals(w1.getOfferDeadline(), offers.get(0).getOfferDeadline());

        // 模拟时间流逝：把本轮截止点改到过去（超时判定只认服务器钟点）。
        // 由超时候补人自己点“确认”触发顺延——验证顺延在独立事务落库，不随确认异常回滚
        w1.setOfferDeadline(NOW.minusMinutes(1));
        waitlistMapper.updateById(w1);
        assertThrows(BusinessException.class,
                () -> waitlistService.confirm(null, rule.getId(), null, "W001"));

        // 超时者 EXPIRED 且收到顺延通知（已随确认请求落库）；下一位 W002 被递补且收到通知（顺延不漏发）
        assertEquals(CapacityConstants.WAIT_EXPIRED, row(rule.getId(), "W001").getStatus());
        assertEquals(1, notifications("W001", CapacityConstants.NOTIFY_EXPIRED).size());
        CapacityWaitlist w2 = row(rule.getId(), "W002");
        assertEquals(CapacityConstants.WAIT_OFFERED, w2.getStatus());
        assertEquals(1, notifications("W002", CapacityConstants.NOTIFY_OFFER).size());

        // W001 超时后再排只能到队尾（新序号），不许插回原位
        WaitlistPositionView p1Tail = waitlistService.join(joinReq(rule.getId(), "W001", "候补一"));
        assertEquals(CapacityConstants.WAIT_WAITING, p1Tail.getStatus());
        assertTrue(p1Tail.getSeq() > w2.getSeq());
        assertEquals(1, p1Tail.getPosition());

        // 超时候补人再确认：拒收（名额已顺延）
        assertThrows(BusinessException.class,
                () -> waitlistService.confirm(null, rule.getId(), null, "W001"));

        // W002 时限内确认 → 锁座、回写报名名册
        CapacityWaitlist confirmed = waitlistService.confirm(null, rule.getId(), null, "W002");
        assertEquals(CapacityConstants.WAIT_SEATED, confirmed.getStatus());
        assertNotNull(confirmed.getRegistrationId());
        assertNotNull(confirmed.getConfirmedAt());
        Registration seated = registrationMapper.selectById(confirmed.getRegistrationId());
        assertEquals("W002", seated.getRegNo());
        assertEquals(ExamConstants.REG_REGISTERED, seated.getStatus());
        assertEquals(1, notifications("W002", CapacityConstants.NOTIFY_SEATED).size());
        // 重复确认
        assertThrows(BusinessException.class,
                () -> waitlistService.confirm(null, rule.getId(), null, "W002"));

        // 状态：容量 2 占 2（在册 R002/W002），锁座 1，余量 0
        CapacityStateView after = waitlistService.stateView(rule.getId());
        assertEquals(2, after.getOccupiedCount());
        assertEquals(1, after.getSeatedCount());
        assertEquals(0, after.getAvailableCount());
        // 退位者名册保留但不计占座
        assertEquals(WaitlistService.REG_WITHDRAWN,
                registrationMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Registration>()
                        .eq("batch_id", batch.getId()).eq("reg_no", "R001")).getStatus());

        // 窗口关闭（同规则发一版窗口已结束的新版本）：仍在候补者被拒收并通知，新入队拒绝。
        // 定时兜底扫描可能抢先处理，故循环跑到终态再断言，不依赖单次返回计数。
        publishVersion(rule.getId(), 2, NOW.minusDays(2), NOW.minusMinutes(1), 60);
        for (int i = 0; i < 3; i++) {
            waitlistService.processExpiry(rule.getId());
        }
        assertEquals(CapacityConstants.WAIT_REJECTED, row(rule.getId(), "W001").getStatus());
        assertEquals(1, notifications("W001", CapacityConstants.NOTIFY_REJECTED).size());
        assertThrows(BusinessException.class,
                () -> waitlistService.join(joinReq(rule.getId(), "W009", "候补九")));
    }

    @Test
    void waitlistWindowGuards_andExitBeforeOfferReleasesNothing() {
        ExamBatch batch = createBatch("CAP02");
        // 窗口尚未开始
        CapacityRule rule = publishRule(batch, "CAPR02", 1,
                NOW.plusHours(1), NOW.plusDays(1), 30);
        assertThrows(BusinessException.class,
                () -> waitlistService.join(joinReq(rule.getId(), "X1", "窗口未开")));

        // 同规则重发一版立刻开放
        publishVersion(rule.getId(), 1, NOW.minusMinutes(5), NOW.plusDays(1), 30);
        enroll(batch, "X-R1", "占座人");
        WaitlistPositionView x = waitlistService.join(joinReq(rule.getId(), "X1", "候补"));
        assertEquals(1, x.getPosition());

        // 候补中（未拿到递补）退出：不释放座位、不顺延
        waitlistService.cancel(null, rule.getId(), null, "X1", "不等了");
        assertEquals(CapacityConstants.WAIT_CANCELLED, row(rule.getId(), "X1").getStatus());
        CapacityState state = stateMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<CapacityState>()
                        .eq("rule_id", rule.getId()));
        assertEquals(1, state.getOccupiedCount());

        // 未配置/未发布：另建规则不发布，入队拒绝
        ExamBatch batch2 = createBatch("CAP03");
        CapacityRuleRequest rr = new CapacityRuleRequest();
        rr.setCode("CAPR03");
        rr.setName("未发布规则");
        rr.setBatchId(batch2.getId());
        CapacityRule unPublished = ruleService.createRule(rr);
        assertThrows(BusinessException.class,
                () -> waitlistService.join(joinReq(unPublished.getId(), "Y1", "无版本")));
    }

    @Test
    void offerCancelReleasesAndPromotesNext() {
        ExamBatch batch = createBatch("CAP04");
        CapacityRule rule = publishRule(batch, "CAPR04", 1,
                NOW.minusHours(1), NOW.plusDays(1), 90);
        enroll(batch, "P1", "甲");
        waitlistService.join(joinReq(rule.getId(), "Q1", "乙"));
        waitlistService.join(joinReq(rule.getId(), "Q2", "丙"));

        // 甲退位 → Q1 待确认
        waitlistService.resign(rule.getId(), null, "P1", null);
        assertEquals(CapacityConstants.WAIT_OFFERED, row(rule.getId(), "Q1").getStatus());

        // Q1 在待确认中退出 → 名额立即顺延给 Q2（同事务，不漏通知）
        waitlistService.cancel(null, rule.getId(), null, "Q1", "放弃递补");
        assertEquals(CapacityConstants.WAIT_CANCELLED, row(rule.getId(), "Q1").getStatus());
        assertEquals(CapacityConstants.WAIT_OFFERED, row(rule.getId(), "Q2").getStatus());
        assertEquals(1, notifications("Q2", CapacityConstants.NOTIFY_OFFER).size());
    }

    @Test
    void waitlistIncludedInSnapshotRollback() {
        ExamBatch batch = createBatch("CAP05");
        CapacityRule rule = publishRule(batch, "CAPR05", 1,
                NOW.minusHours(1), NOW.plusDays(1), 30);
        enroll(batch, "S1", "占座");
        waitlistService.join(joinReq(rule.getId(), "S-W1", "快照候补"));
        workspaceVersionService.createSnapshot("容量候补快照", null);

        // 快照后再入队一人、并发生递补
        waitlistService.join(joinReq(rule.getId(), "S-W2", "回退后消失"));
        waitlistService.resign(rule.getId(), null, "S1", null);
        assertEquals(CapacityConstants.WAIT_OFFERED, row(rule.getId(), "S-W1").getStatus());

        // 回退：快照后的 S-W2 消失，S-W1 回到快照时的 WAITING，R1 恢复在册占座
        workspaceVersionService.rollback(
                workspaceVersionService.listVersions(false).stream()
                        .filter(v -> "容量候补快照".equals(v.getLabel()))
                        .findFirst().orElseThrow(IllegalStateException::new).getVersionNo(),
                "回退验证");
        assertNotNull(row(rule.getId(), "S-W1"));
        assertEquals(CapacityConstants.WAIT_WAITING, row(rule.getId(), "S-W1").getStatus());
        assertNull(row(rule.getId(), "S-W2"));
        assertEquals(ExamConstants.REG_REGISTERED,
                registrationMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Registration>()
                        .eq("batch_id", batch.getId()).eq("reg_no", "S1")).getStatus());
    }
}
