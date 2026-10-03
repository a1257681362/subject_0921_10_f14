package com.evops.discipline.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.common.BusinessException;
import com.evops.discipline.common.DisciplineConstants;
import com.evops.discipline.entity.DisciplineConfig;
import com.evops.discipline.mapper.DisciplineConfigMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 违纪处理配置：决定期限（小时）、催办间隔（小时）、默认承办人；键值对可调。
 */
@Service
public class DisciplineConfigService {

    private final DisciplineConfigMapper configMapper;

    public DisciplineConfigService(DisciplineConfigMapper configMapper) {
        this.configMapper = configMapper;
    }

    public List<DisciplineConfig> list() {
        return configMapper.selectList(new QueryWrapper<DisciplineConfig>().orderByAsc("config_key"));
    }

    public String get(String key) {
        DisciplineConfig config = configMapper.selectOne(
                new QueryWrapper<DisciplineConfig>().eq("config_key", key));
        return config == null ? null : config.getConfigValue();
    }

    public int getDecisionDeadlineHours() {
        return getPositiveInt(DisciplineConstants.CFG_DEADLINE_HOURS,
                DisciplineConstants.DEFAULT_DEADLINE_HOURS);
    }

    public int getUrgeIntervalHours() {
        return getPositiveInt(DisciplineConstants.CFG_URGE_INTERVAL_HOURS,
                DisciplineConstants.DEFAULT_URGE_INTERVAL_HOURS);
    }

    public String getDefaultAssignee() {
        String value = get(DisciplineConstants.CFG_DEFAULT_ASSIGNEE);
        return value == null || value.trim().isEmpty()
                ? DisciplineConstants.DEFAULT_ASSIGNEE_NAME : value.trim();
    }

    private int getPositiveInt(String key, int defaultValue) {
        String value = get(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed <= 0) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new BusinessException("DISCIPLINE_CONFIG_INVALID",
                    "违纪配置 " + key + " 必须为正整数（小时），当前值：" + value);
        }
    }

    @Transactional
    public DisciplineConfig update(String key, String value, String remark) {
        if (key == null || key.trim().isEmpty()) {
            throw new BusinessException("CONFIG_KEY_REQUIRED", "配置键不能为空");
        }
        if (value == null || value.trim().isEmpty()) {
            throw new BusinessException("CONFIG_VALUE_REQUIRED", "配置值不能为空：" + key);
        }
        String normalizedKey = key.trim();
        String normalizedValue = value.trim();
        validateValue(normalizedKey, normalizedValue);
        DisciplineConfig config = configMapper.selectOne(
                new QueryWrapper<DisciplineConfig>().eq("config_key", normalizedKey));
        if (config == null) {
            config = new DisciplineConfig();
            config.setConfigKey(normalizedKey);
            config.setConfigValue(normalizedValue);
            config.setRemark(remark);
            config.setOperator(com.evops.common.OperatorHolder.current());
            configMapper.insert(config);
        } else {
            config.setConfigValue(normalizedValue);
            if (remark != null) {
                config.setRemark(remark);
            }
            config.setOperator(com.evops.common.OperatorHolder.current());
            configMapper.updateById(config);
        }
        return config;
    }

    private void validateValue(String key, String value) {
        if (DisciplineConstants.CFG_DEADLINE_HOURS.equals(key)
                || DisciplineConstants.CFG_URGE_INTERVAL_HOURS.equals(key)) {
            try {
                int parsed = Integer.parseInt(value);
                if (parsed <= 0) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException e) {
                throw new BusinessException("DISCIPLINE_CONFIG_INVALID",
                        key + " 必须为正整数（小时），收到：" + value);
            }
        }
    }

    /** 供调度器取当前钟点（便于测试替换）。 */
    protected LocalDateTime now() {
        return LocalDateTime.now();
    }
}
