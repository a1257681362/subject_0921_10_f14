package com.evops.papersel.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.util.List;

/**
 * 一次多场并行组卷请求。
 */
@Data
public class ComposeRequest {
    private String projectCode;
    /** 并行套数（≥1）。 */
    @NotNull(message = "组卷套数不能为空")
    @Min(value = 1, message = "组卷套数至少为 1")
    private Integer paperCount;
    /** 多场并行组号（教研会场次编码等）；不传取运行单号。 */
    private String groupCode;
    /** 根种子：不传由系统按项目/套数/配比/池子指纹/历史次数取确定性种子；传了可复现。 */
    private Long rootSeed;

    @Valid
    @NotEmpty(message = "知识点+难度档配比不能为空")
    private List<QuotaItem> quotas;
}
