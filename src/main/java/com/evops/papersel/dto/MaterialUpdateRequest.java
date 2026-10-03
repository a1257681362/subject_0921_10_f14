package com.evops.papersel.dto;

import lombok.Data;

/**
 * 素材修改：任一字段为空表示不改该字段；只允许改内容/归类/备注，编号与项目不可变。
 */
@Data
public class MaterialUpdateRequest {
    private String knowledgePoint;
    private String difficulty;
    private String content;
    private String referenceAnswer;
    private String remark;
    /** 修改原因（台账留痕必填）。 */
    private String reason;
}
