package com.evops.grade.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 复核材料阅读记录：材料阅读独立于申请流转，只追加，不影响复核单状态与窗口。
 * readerKind=SELF 为学员本人（X-Self-Code），STAFF 为审核岗（X-Operator）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_review_material_read")
public class ReviewMaterialRead extends BaseEntity {
    private Long materialId;
    private Integer versionNo;
    private Long reviewId;
    private String regNo;
    private String reader;
    /** STAFF 审核岗 / SELF 学员本人。 */
    private String readerKind;
    private LocalDateTime readAt;
}
