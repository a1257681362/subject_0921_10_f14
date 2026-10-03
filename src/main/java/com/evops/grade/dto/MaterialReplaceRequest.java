package com.evops.grade.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 附件版本替换：新版本进入留档为当前版，旧版不删、保留摘要置 SUPERSEDED。
 * 替换原因必填（为什么换版要留痕）。
 */
@Data
public class MaterialReplaceRequest {
    private String fileRef;
    private String contentHash;
    private String content;
    private Long fileSize;
    private String originalFilename;
    /** 新版摘要说明（可空，缺省按 文件名/大小/哈希 生成）。 */
    private String digestSummary;
    @NotBlank(message = "附件版本替换必须写明替换原因")
    private String reason;
}
