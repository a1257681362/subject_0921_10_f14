package com.evops.papersel.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 题库素材：只下架（DISABLED）不物理删除；增删改全部留 t_paper_material_change 台账。
 * 历史组卷卷面保存的是组卷时点的素材快照与内容哈希，池子事后变动不影响历史。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_paper_material")
public class PaperMaterial extends BaseEntity {
    private String materialNo;
    private String projectCode;
    private String knowledgePoint;
    private String difficulty;
    private String content;
    private String referenceAnswer;
    /** 内容指纹 SHA-256，内容改动即重算 */
    private String contentHash;
    /** ENABLED 在池可抽 / DISABLED 已下架 */
    private String status;
    private String remark;
    private String disabledReason;
    private LocalDateTime disabledAt;
}
