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

    /**
     * 卡号全局存在性检查。
     * 不过滤 is_deleted,以便精确对齐数据库 employee.card_no 全局唯一索引(含已逻辑删除记录)。
     * excludeId 非空时排除自身(用于编辑场景)。
     */
    int countByCardNoExcludeId(@Param("cardNo") String cardNo, @Param("excludeId") Long excludeId);

    /** 手机号 + 门店查询(H5/小程序登录用) */
    Employee selectByPhoneAndStore(@Param("phone") String phone, @Param("storeId") Long storeId);

    /** 姓名 + 门店查询(批量头像上传按文件名匹配姓名用) */
    Employee selectByNameAndStore(@Param("name") String name, @Param("storeId") Long storeId);

    /** 手机号全局唯一查询(phone 已建全局唯一索引,登录不传 storeId 时用) */
    Employee selectByPhone(@Param("phone") String phone);

    /** 微信 openid 查询(微信登录用,openid 全局唯一) */
    Employee selectByWxOpenid(@Param("wxOpenid") String wxOpenid);

    /** B1 原子扣减余额:balance>=amount 才更新,返回受影响行数 */
    int deductBalance(@Param("id") Long id, @Param("amount") BigDecimal amount);

    /** 原子增加余额 */
    int addBalance(@Param("id") Long id, @Param("amount") BigDecimal amount);
}
