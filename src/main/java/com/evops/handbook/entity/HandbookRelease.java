package com.evops.handbook.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 手册发布批次：每次放行开新批次（INITIAL/ADJUST）；
 * 补发（REISSUE）沿用原发布批次号与生效时刻，保留经办人。只追加。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_handbook_release")
public class HandbookRelease extends BaseEntity {
    private Long handbookId;
    /** 发布批次号：手册内递增 R1、R2……（补发沿用原批次号）。 */
    private String releaseNo;
    private Long versionId;
    private Integer versionNo;
    /** INITIAL / ADJUST / REISSUE */
    private String kind;
    /** 补发指向原发布批次；首发/调整为空。 */
    private Long originReleaseId;
    /** 生效时刻：放行时冻结，补发沿用原发布批次。 */
    private LocalDateTime effectiveAt;
    /** 经办人：补发沿用原发布批次经办人。 */
    private String operator;
    /** 补发经办人（仅 REISSUE）。 */
    private String reissuedBy;
    /** 放行 / 补发钟点。 */
    private LocalDateTime releasedAt;
    private String reason;
}
