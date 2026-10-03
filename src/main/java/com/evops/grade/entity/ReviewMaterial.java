package com.evops.grade.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 复核材料主表（模块）：申请流转与材料阅读分开处理，材料随复核单单独建档。
 * 状态取当前版本：ACTIVE 在档 / SUPERSEDED 已被替换（旧版仅留摘要）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_review_material")
public class ReviewMaterial extends BaseEntity {
    private Long reviewId;
    private Long batchId;
    private Long registrationId;
    private String regNo;
    private String name;
    private String title;
    /** DOC 书证 / PHOTO 照片 / FORM 申请表 / OTHER 其他。 */
    private String materialType;
    /** ACTIVE 在档可阅读 / SUPERSEDED 已替换（留摘要）。 */
    private String status;
    private Integer currentVersion;
    /** 内部批注：只进内部管理视图，学员视图与导出一律不带出。 */
    private String internalAnnotation;
}
