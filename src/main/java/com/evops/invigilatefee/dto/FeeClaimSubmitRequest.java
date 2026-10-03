package com.evops.invigilatefee.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;

/**
 * 教师提交本人跨区交通补贴报销：关联批次（带出考点 / 项目 / 出行日期口径）+ 出发区域 + 交通方式 + 票据。
 * 只能提交本人：教师视图（X-Self-Code）下 invigilatorCode 由请求头强制决定，忽略报文冒填。
 * 两步：先 preview 估算不落库，确认后再 submit。
 */
@Data
public class FeeClaimSubmitRequest {

    @NotNull(message = "批次 id 不能为空")
    private Long batchId;

    @NotBlank(message = "出发区域编码不能为空")
    private String regionCode;

    /** 出行日期（规则版本按此日期选择）；为空时取批次考试日期。 */
    private LocalDate travelDate;

    /** 本程主要交通方式 BUS / TRAIN / TAXI / CAR / OTHER。 */
    @NotBlank(message = "交通方式不能为空")
    private String transportMode;

    /**
     * 财务全量视图代教师提交时用；教师本人视图（带 X-Self-Code）此值被忽略，强制取请求头。
     */
    private String invigilatorCode;

    @NotEmpty(message = "报销至少须附一张审批票据")
    @Valid
    private List<FeeReceiptItem> receipts;

    private String remark;

    /** 票据凭证：一条申请多张，只追加不可改。 */
    @Data
    public static class FeeReceiptItem {
        /** 该票交通方式（可与主方式不同，用于分段）。缺省取申请主方式。 */
        private String transportMode;
        @NotNull(message = "票据金额不能为空")
        @javax.validation.constraints.DecimalMin(value = "0", message = "票据金额不能为负")
        private java.math.BigDecimal amount;
        /** 票据发生时间（可空）。 */
        private java.time.LocalDateTime occurredAt;
        private String fileRef;
        private String contentHash;
        private String originalFilename;
        /** 票据正文 / OCR 文本（与附件至少能佐证即可，均可空）。 */
        private String content;
        private String remark;
    }
}
