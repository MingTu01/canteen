package com.example.canteen.entity;

/**
 * 餐次类型枚举(早餐/午餐/晚餐)。
 *
 * 用于消除散落在代码中的 mealType=1/2/3 魔法数字。
 * 数值与数据库 schema 保持一致。
 */
public enum MealType {
    BREAKFAST(1, "早餐", "breakfast"),
    LUNCH(2, "午餐", "lunch"),
    DINNER(3, "晚餐", "dinner");

    private final int code;
    private final String chineseName;
    private final String englishKey;

    MealType(int code, String chineseName, String englishKey) {
        this.code = code;
        this.chineseName = chineseName;
        this.englishKey = englishKey;
    }

    public int getCode() {
        return code;
    }

    public String getChineseName() {
        return chineseName;
    }

    public String getEnglishKey() {
        return englishKey;
    }

    /** 从数据库 meal_type 值转枚举,未知值返回 null */
    public static MealType fromCode(Integer code) {
        if (code == null) return null;
        for (MealType m : values()) {
            if (m.code == code) return m;
        }
        return null;
    }
}
