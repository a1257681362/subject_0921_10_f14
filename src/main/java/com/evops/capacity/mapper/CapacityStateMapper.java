package com.evops.capacity.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.capacity.entity.CapacityState;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CapacityStateMapper extends BaseMapper<CapacityState> {

    /**
     * 取规则状态行并上写锁：入队/递补/确认/超时全部经此串行化，
     * 杜绝并发超卖与同一名额重复递补。须在事务内调用。
     */
    @Select("SELECT * FROM t_capacity_state WHERE rule_id = #{ruleId} FOR UPDATE")
    CapacityState selectForUpdate(@Param("ruleId") Long ruleId);
}
