package com.example.canteen.entity;

/**
 * 订单状态枚举。
 *
 * 用于消除散落在 Service/Controller/Report 中的 status=1/2/3 魔法数字。
 * 数值与数据库 schema 保持一致,不修改字段类型,仅用于代码可读性。
 */
public enum OrderStatus {
    /** 待完成(已下单,等待取餐) */
    PENDING(1),
    /** 已完成(已取餐) */
    COMPLETED(2),
    /** 已取消(用户取消或异常) */
    CANCELED(3),
    /** 未就餐(超过就餐时段未核销,由定时任务自动标记) */
    MISSED(4);

    private final int code;

    OrderStatus(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    /** 从数据库 status 值转枚举,未知值返回 null */
    public static OrderStatus fromCode(Integer code) {
        if (code == null) return null;
        for (OrderStatus s : values()) {
            if (s.code == code) return s;
        }
        return null;
    }
}
