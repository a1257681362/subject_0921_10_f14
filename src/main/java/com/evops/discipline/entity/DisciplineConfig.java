package com.evops.discipline.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 违纪处理配置：办理期限 / 催办间隔 / 默认承办人，键值对可调。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_discipline_config")
public class DisciplineConfig extends BaseEntity {
    private String configKey;
    private String configValue;
    private String remark;
    private String operator;
}
