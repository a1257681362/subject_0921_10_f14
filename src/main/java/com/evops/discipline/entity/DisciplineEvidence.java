package com.evops.discipline.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 案件证据：监考上报随附，只追加、不修改不删除；封存后仅授权岗可查。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_discipline_evidence")
public class DisciplineEvidence extends BaseEntity {
    private Long caseId;
    private String caseNo;
    /** 证据编号 WJ-ZJ + 案件流水 + 2 位序号。 */
    private String evidenceNo;
    /** PHOTO 照片 / VIDEO 视频 / DOC 书证 / STATEMENT 情况说明 / OTHER 其他。 */
    private String evidenceType;
    private String title;
    /** 附件存放引用（路径/对象存储键）。 */
    private String fileRef;
    /** 附件内容摘要哈希（防篡改留档）。 */
    private String contentHash;
    /** 文字情况说明正文（附件类可空）。 */
    private String content;
    private String reporter;
    private LocalDateTime collectedAt;
}
