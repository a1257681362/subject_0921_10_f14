package com.evops.grade.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 复核材料版本（只追加）：进入留档 REGISTER、附件替换 REPLACE 各追加一版。
 * 旧版不删，文件引用/哈希/大小/文件名/摘要都保留，状态置 SUPERSEDED。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_review_material_version")
public class ReviewMaterialVersion extends BaseEntity {
    private Long materialId;
    private Long reviewId;
    private Integer versionNo;
    /** ACTIVE 当前版 / SUPERSEDED 旧版（仅留摘要）。 */
    private String status;
    /** 文件引用（路径/对象存储键）。 */
    private String fileRef;
    /** 内容摘要指纹 SHA-256。 */
    private String contentHash;
    /** 文本材料正文（附件类可空）。 */
    private String content;
    /** 文件大小（字节）。 */
    private Long fileSize;
    private String originalFilename;
    /** 旧版留摘要：文件名/大小/哈希/替换原因/钟点的文字概括。 */
    private String digestSummary;
    /** REGISTER 进入留档 / REPLACE 版本替换。 */
    private String changeType;
    private String changeReason;
    private String operator;
    private LocalDateTime enteredAt;
}
