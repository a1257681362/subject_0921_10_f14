package com.evops.grade.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * 复核材料进入 evops-base 留档：挂在复核申请下，同一申请标题唯一。
 * 附件类给 fileRef（建议同时给 contentHash/fileSize/originalFilename）；文本类给 content。
 */
@Data
public class MaterialRegisterRequest {
    @NotNull(message = "复核申请 id 不能为空")
    private Long reviewId;
    @NotBlank(message = "材料标题不能为空")
    private String title;
    /** DOC 书证 / PHOTO 照片 / FORM 申请表 / OTHER 其他，缺省 DOC。 */
    private String materialType;
    private String fileRef;
    private String contentHash;
    private String content;
    private Long fileSize;
    private String originalFilename;
    /** 摘要说明（可空，缺省按 文件名/大小/哈希 生成）。 */
    private String digestSummary;
    /** 内部批注：仅内部视图可见，永不下发学员、永不进导出。 */
    private String internalAnnotation;
}
