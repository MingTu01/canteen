package com.example.canteen.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.canteen.entity.Employee;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface EmployeeMapper extends BaseMapper<Employee> {
    List<Employee> selectByStoreId(@Param("storeId") Long storeId);

    /** S7 卡号 + 门店查询 */
    Employee selectByCardNoAndStore(@Param("cardNo") String cardNo, @Param("storeId") Long storeId);

    /** 手机号 + 门店查询(H5/小程序登录用) */
    Employee selectByPhoneAndStore(@Param("phone") String phone, @Param("storeId") Long storeId);

    /** 手机号全局唯一查询(phone 已建全局唯一索引,登录不传 storeId 时用) */
    Employee selectByPhone(@Param("phone") String phone);

    /** B1 原子扣减余额:balance>=amount 才更新,返回受影响行数 */
    int deductBalance(@Param("id") Long id, @Param("amount") BigDecimal amount);

    /** 原子增加余额 */
    int addBalance(@Param("id") Long id, @Param("amount") BigDecimal amount);
}
