package com.evops.papersel.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 抽选算法模块：一个项目可建一套（或多套），算法下挂多个版本。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_paper_sel_algorithm")
public class PaperSelAlgorithm extends BaseEntity {
    private String code;
    private String name;
    /** 与素材/批次 project_code 对齐 */
    private String projectCode;
    private String remark;
}
