package com.example.canteen.entity;

/**
 * 订单来源枚举
 * - NORMAL(0): 正常订餐
 * - UNSOLICITED(1): 未订餐用餐(现场加餐,绕过截止时间和防重复校验)
 */
public enum OrderSource {
    NORMAL(0, "正常订餐"),
    UNSOLICITED(1, "未订餐用餐");

    private final int code;
    private final String label;

    OrderSource(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int getCode() { return code; }
    public String getLabel() { return label; }

    public static OrderSource fromCode(Integer code) {
        if (code == null) return NORMAL;
        for (OrderSource s : values()) {
            if (s.code == code) return s;
        }
        return NORMAL;
    }
}
