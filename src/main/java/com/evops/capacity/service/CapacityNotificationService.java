package com.evops.capacity.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.capacity.common.CapacityConstants;
import com.evops.capacity.entity.CapacityNotification;
import com.evops.capacity.mapper.CapacityNotificationMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 候补通知：入队/递补/锁座/超时顺延/退出/拒收都落库通知。
 * 递补通知必带确认截止钟点（服务器时间）。短信等外发渠道下版再接。
 */
@Service
public class CapacityNotificationService {

    private final CapacityNotificationMapper notificationMapper;

    public CapacityNotificationService(CapacityNotificationMapper notificationMapper) {
        this.notificationMapper = notificationMapper;
    }

    @Transactional
    public CapacityNotification notify(Long ruleId, Long waitlistId, Long batchId, String regNo, String name,
                                       String notifyType, String title, String content,
                                       LocalDateTime offerDeadline, String operator) {
        CapacityNotification n = new CapacityNotification();
        n.setRuleId(ruleId);
        n.setWaitlistId(waitlistId);
        n.setBatchId(batchId);
        n.setRegNo(regNo);
        n.setName(name);
        n.setNotifyType(notifyType);
        n.setTitle(title);
        n.setContent(content);
        n.setOfferDeadline(offerDeadline);
        n.setStatus(CapacityConstants.NOTIFY_NEW);
        n.setOperator(operator);
        n.setNotifiedAt(LocalDateTime.now());
        notificationMapper.insert(n);
        return n;
    }

    public List<CapacityNotification> list(String regNo, Long waitlistId, boolean unreadOnly) {
        QueryWrapper<CapacityNotification> wrapper = new QueryWrapper<CapacityNotification>()
                .orderByDesc("notified_at", "id");
        if (regNo != null && !regNo.trim().isEmpty()) {
            wrapper.eq("reg_no", regNo.trim());
        }
        if (waitlistId != null) {
            wrapper.eq("waitlist_id", waitlistId);
        }
        if (unreadOnly) {
            wrapper.eq("status", CapacityConstants.NOTIFY_NEW);
        }
        return notificationMapper.selectList(wrapper);
    }

    @Transactional
    public CapacityNotification markRead(Long id) {
        CapacityNotification n = notificationMapper.selectById(id);
        if (n == null) {
            throw new com.evops.common.BusinessException("CAP_NOTIFICATION_NOT_FOUND",
                    "候补通知不存在：id=" + id);
        }
        if (CapacityConstants.NOTIFY_NEW.equals(n.getStatus())) {
            n.setStatus(CapacityConstants.NOTIFY_READ);
            n.setReadAt(LocalDateTime.now());
            notificationMapper.updateById(n);
        }
        return n;
    }
}
