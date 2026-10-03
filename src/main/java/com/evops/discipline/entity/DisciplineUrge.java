package com.evops.discipline.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 催办记录：超过处理决定期限自动催办（每分钟扫描，服务器钟点），按配置间隔升级，只追加。
 * 本版先站内落库（复用通知查询），短信/邮件外发下版再接。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_discipline_urge")
public class DisciplineUrge extends BaseEntity {
    private Long caseId;
    private String caseNo;
    private Integer urgeRound;
    private String recipient;
    private String title;
    private String content;
    private LocalDateTime deadline;
    /** IN_SITE 站内（本版唯一渠道）。 */
    private String channel;
    /** NEW 未读 / READ 已读。 */
    private String status;
    private String urgedBy;
    private LocalDateTime urgedAt;
    private LocalDateTime readAt;
}
