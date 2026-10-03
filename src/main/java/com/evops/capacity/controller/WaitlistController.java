package com.evops.capacity.controller;

import com.evops.capacity.dto.WaitlistActionRequest;
import com.evops.capacity.dto.WaitlistJoinRequest;
import com.evops.capacity.entity.CapacityNotification;
import com.evops.capacity.entity.CapacityWaitlist;
import com.evops.capacity.entity.CapacityWaitlistEvent;
import com.evops.capacity.service.CapacityNotificationService;
import com.evops.capacity.service.WaitlistService;
import com.evops.capacity.vo.WaitlistPositionView;
import com.evops.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * 候补队列与递补通知：
 * 满员入队（重复请求回原位置不插队）→ 退位按序递补 → 通知带确认时限（服务器钟点判定）
 * → 时限内确认锁座，超时顺给下一位；队列位置随时可查，透明不猜。
 */
@RestController
@RequestMapping("/api/capacity")
public class WaitlistController {

    private final WaitlistService waitlistService;
    private final CapacityNotificationService notificationService;

    public WaitlistController(WaitlistService waitlistService,
                              CapacityNotificationService notificationService) {
        this.waitlistService = waitlistService;
        this.notificationService = notificationService;
    }

    /** 入队：满员才进候补；同一规则同一报名号重复请求只回原位置，不新建不插队。 */
    @PostMapping("/waitlist/join")
    public ApiResponse<WaitlistPositionView> join(@Valid @RequestBody WaitlistJoinRequest request) {
        return ApiResponse.ok(waitlistService.join(request));
    }

    /** 确认递补：须在通知截止钟点前；成功回写报名名册并锁座，超时名额已顺延。 */
    @PostMapping("/waitlist/confirm")
    public ApiResponse<CapacityWaitlist> confirm(@Valid @RequestBody WaitlistActionRequest request) {
        return ApiResponse.ok(waitlistService.confirm(request.getWaitlistId(), request.getRuleId(),
                request.getBatchId(), request.getRegNo()));
    }

    /** 退出候补（待确认中退出会释放名额并立即顺延）。 */
    @PostMapping("/waitlist/cancel")
    public ApiResponse<CapacityWaitlist> cancel(@Valid @RequestBody WaitlistActionRequest request) {
        return ApiResponse.ok(waitlistService.cancel(request.getWaitlistId(), request.getRuleId(),
                request.getBatchId(), request.getRegNo(), request.getReason()));
    }

    /** 退位：已锁座/在册人员释放名额，系统立即按序递补候补首位。 */
    @PostMapping("/waitlist/resign")
    public ApiResponse<Map<String, Object>> resign(@Valid @RequestBody WaitlistActionRequest request) {
        return ApiResponse.ok(waitlistService.resign(request.getRuleId(), request.getBatchId(),
                request.getRegNo(), request.getReason()));
    }

    /** 手动执行超时顺延（定时任务每分钟自动跑同一逻辑，服务器钟点判定）。 */
    @PostMapping("/waitlist/sweep-expired")
    public ApiResponse<Map<String, Object>> sweepExpired() {
        return ApiResponse.ok(waitlistService.sweepExpired());
    }

    /** 查我的队列位置：真实顺位、前面人数、递补轮次与确认截止钟点。 */
    @GetMapping("/waitlist/position")
    public ApiResponse<WaitlistPositionView> position(@RequestParam(required = false) Long ruleId,
                                                      @RequestParam(required = false) Long batchId,
                                                      @RequestParam String regNo) {
        return ApiResponse.ok(waitlistService.position(ruleId, batchId, regNo));
    }

    /** 队列清单（管理视图，默认按入队序号）。 */
    @GetMapping("/waitlist")
    public ApiResponse<List<CapacityWaitlist>> list(@RequestParam(required = false) Long ruleId,
                                                    @RequestParam(required = false) Long batchId,
                                                    @RequestParam(required = false) String status) {
        return ApiResponse.ok(waitlistService.list(ruleId, batchId, status));
    }

    /** 单条候补的流转事件链（入队/递补/确认/超时/退出/拒收）。 */
    @GetMapping("/waitlist/{waitlistId}/events")
    public ApiResponse<List<CapacityWaitlistEvent>> events(@PathVariable Long waitlistId) {
        return ApiResponse.ok(waitlistService.events(waitlistId));
    }

    /** 候补通知（可按报名号/候补单过滤，可只看未读）。递补通知含确认截止钟点。 */
    @GetMapping("/notifications")
    public ApiResponse<List<CapacityNotification>> notifications(@RequestParam(required = false) String regNo,
                                                                 @RequestParam(required = false) Long waitlistId,
                                                                 @RequestParam(required = false, defaultValue = "false")
                                                                 boolean unreadOnly) {
        return ApiResponse.ok(waitlistService.notifications(regNo, waitlistId, unreadOnly));
    }

    @PostMapping("/notifications/{id}/read")
    public ApiResponse<CapacityNotification> markRead(@PathVariable Long id) {
        return ApiResponse.ok(notificationService.markRead(id));
    }
}
