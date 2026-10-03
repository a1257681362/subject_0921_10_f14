package com.evops.papersel.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class MaterialRequest {
    /** 素材编号；不传由系统生成 SC + yyMMddHHmmss + 4 位序列。 */
    private String materialNo;
    private String projectCode;
    @NotBlank(message = "知识点不能为空")
    private String knowledgePoint;
    @NotBlank(message = "难度档不能为空")
    private String difficulty;
    @NotBlank(message = "题干内容不能为空")
    private String content;
    private String referenceAnswer;
    private String remark;
}
