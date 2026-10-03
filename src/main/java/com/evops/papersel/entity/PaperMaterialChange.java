package com.evops.papersel.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 素材池增删改台账：只追加，前后快照都留，永不修改。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_paper_material_change")
public class PaperMaterialChange extends BaseEntity {
    private Long materialId;
    private String materialNo;
    /** CREATE 建档 / UPDATE 修改 / DISABLE 下架 / ENABLE 恢复 */
    private String changeType;
    /** 变更前/后素材快照 JSON */
    private String beforeSnapshot;
    private String afterSnapshot;
    private String reason;
    private String operator;
    private LocalDateTime changedAt;
}
