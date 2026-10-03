package com.evops.handbook.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.handbook.entity.HandbookVersion;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface HandbookVersionMapper extends BaseMapper<HandbookVersion> {
}
