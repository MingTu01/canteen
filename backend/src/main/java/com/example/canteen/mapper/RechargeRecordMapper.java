package com.example.canteen.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.canteen.entity.RechargeRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface RechargeRecordMapper extends BaseMapper<RechargeRecord> {
    List<RechargeRecord> selectByStoreId(@Param("storeId") Long storeId);
    List<RechargeRecord> selectByEmployeeId(@Param("employeeId") Long employeeId);
}
