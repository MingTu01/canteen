package com.example.canteen.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.canteen.entity.DiningTimeSlot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DiningTimeSlotMapper extends BaseMapper<DiningTimeSlot> {
    List<DiningTimeSlot> selectByStoreId(@Param("storeId") Long storeId);
}
