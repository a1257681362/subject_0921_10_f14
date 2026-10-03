package com.evops.papersel.dto;

import lombok.Data;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * 知识点 + 难度档配比项：该知识点该难度档抽 count 道。
 */
@Data
public class QuotaItem {
    @NotBlank(message = "知识点不能为空")
    private String knowledgePoint;

    @NotBlank(message = "难度档不能为空")
    private String difficulty;

    @NotNull(message = "配比数量不能为空")
    @Min(value = 1, message = "每个配比项数量至少为 1")
    private Integer count;
}
