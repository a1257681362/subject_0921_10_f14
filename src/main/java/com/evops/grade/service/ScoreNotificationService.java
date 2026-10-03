package com.evops.grade.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.common.BusinessException;
import com.evops.grade.common.GradeConstants;
import com.evops.grade.entity.ScoreNotification;
import com.evops.grade.mapper.ScoreNotificationMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 站内通知：本版先落库（复核结论、超窗关闭），短信等外发渠道下版再接。
 */
@Service
public class ScoreNotificationService {

    private final ScoreNotificationMapper notificationMapper;

    public ScoreNotificationService(ScoreNotificationMapper notificationMapper) {
        this.notificationMapper = notificationMapper;
    }

    @Transactional
    public ScoreNotification notify(Long batchId, Long registrationId, String regNo, String name,
                                    String notifyType, String title, String content,
                                    Long reviewId, String operator) {
        ScoreNotification n = new ScoreNotification();
        n.setBatchId(batchId);
        n.setRegistrationId(registrationId);
        n.setRegNo(regNo);
        n.setName(name);
        n.setNotifyType(notifyType);
        n.setTitle(title);
        n.setContent(content);
        n.setStatus(GradeConstants.NOTIFY_NEW);
        n.setReviewId(reviewId);
        n.setOperator(operator);
        n.setNotifiedAt(LocalDateTime.now());
        notificationMapper.insert(n);
        return n;
    }

    public List<ScoreNotification> list(String regNo, boolean unreadOnly) {
        QueryWrapper<ScoreNotification> wrapper = new QueryWrapper<ScoreNotification>()
                .orderByDesc("notified_at", "id");
        if (regNo != null && !regNo.trim().isEmpty()) {
            wrapper.eq("reg_no", regNo.trim());
        }
        if (unreadOnly) {
            wrapper.eq("status", GradeConstants.NOTIFY_NEW);
        }
        return notificationMapper.selectList(wrapper);
    }

    /**
     * 通知回执按原申请挂回：取一张复核申请下的复核类通知（结论 / 超窗关闭）。
     * 必须同时限定通知类型：review_id 是多态列，报名条件申诉通知也复用本表写 appealId，
     * 只按 id 反查会把 id 碰巧相同的申诉通知误挂到复核单上。
     */
    public List<ScoreNotification> listByReview(Long reviewId) {
        return notificationMapper.selectList(new QueryWrapper<ScoreNotification>()
                .eq("review_id", reviewId)
                .in("notify_type", java.util.Arrays.asList(
                        GradeConstants.NOTIFY_REVIEW_RESULT, GradeConstants.NOTIFY_REVIEW_CLOSED))
                .orderByDesc("notified_at", "id"));
    }

    /**
     * 学员本人标记已读：只能读本人的通知回执，非本人统一按不存在处理，不暴露存在性。
     */
    @Transactional
    public ScoreNotification markReadForSelf(Long id, String regNo) {
        ScoreNotification n = notificationMapper.selectById(id);
        if (n == null || regNo == null || !regNo.trim().equals(n.getRegNo())) {
            throw new BusinessException("NOTIFICATION_NOT_FOUND", "通知不存在：id=" + id);
        }
        return markRead(id);
    }

    @Transactional
    public ScoreNotification markRead(Long id) {
        ScoreNotification n = notificationMapper.selectById(id);
        if (n == null) {
            throw new BusinessException("NOTIFICATION_NOT_FOUND", "通知不存在：id=" + id);
        }
        if (GradeConstants.NOTIFY_NEW.equals(n.getStatus())) {
            n.setStatus(GradeConstants.NOTIFY_READ);
            n.setReadAt(LocalDateTime.now());
            notificationMapper.updateById(n);
        }
        return n;
    }
}
