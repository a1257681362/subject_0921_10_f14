package com.evops.discipline.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 监考上报附证据立案：上报即立案（REPORTED），证据至少一条，立案即起算处理决定期限。
 */
@Data
public class CaseReportRequest {

    @NotNull(message = "批次不能为空")
    private Long batchId;

    @NotBlank(message = "报名号不能为空")
    private String regNo;

    @NotBlank(message = "违纪类型不能为空（CHEAT 作弊 / IMPERSONATE 替考 / PROHIBITED 携带违禁品 / DISORDER 扰乱秩序 / OTHER 其他）")
    private String violationType;

    /** 违纪情形描述。 */
    private String description;

    /** 违纪发生时间（可空，缺省上报当前时间）。 */
    private LocalDateTime occurredAt;

    /** 证据清单：上报必须附证据，至少一条。 */
    @NotEmpty(message = "上报立案必须附证据（至少一条）")
    @Valid
    private List<EvidenceItem> evidences;

    /** 上报监考（可空，缺省取 X-Operator）。 */
    private String reporter;

    /** 承办人（可空，缺省取默认承办人配置；催办发给承办人）。 */
    private String assignee;

    /** LIVE 现场上报 / PAPER 纸面补报（可空，缺省 LIVE）。 */
    private String source;

    private String remark;

    /**
     * 证据条目：附件类给 fileRef（建议同时给 contentHash 摘要留档）；情况说明给 content 正文。
     */
    @Data
    public static class EvidenceItem {
        @NotBlank(message = "证据类型不能为空（PHOTO 照片 / VIDEO 视频 / DOC 书证 / STATEMENT 情况说明 / OTHER 其他）")
        private String evidenceType;

        @NotBlank(message = "证据标题不能为空")
        private String title;

        /** 附件存放引用（路径/对象存储键）。 */
        private String fileRef;

        /** 附件内容摘要哈希（SHA-256，建议提供以防篡改）。 */
        private String contentHash;

        /** 文字情况说明正文。 */
        private String content;

        /** 取证时间（可空，缺省上报当前时间）。 */
        private LocalDateTime collectedAt;
    }
}
