package com.example.ilink.application.tooling;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 金额的小数位规则。
 *
 * <p>需要以“元”为单位展示或结算的功能统一使用两位小数和四舍五入规则，
 * 避免基础计算、AA 分摊等模块出现不同金额结果。</p>
 */
public final class MoneyUtils {

    /** 人民币最小单位：一分钱。 */
    public static final BigDecimal CENT = new BigDecimal("0.01");

    private MoneyUtils() {
    }

    /** 将数值按金额规则保留两位小数。 */
    public static BigDecimal round(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    /** 将数值格式化为固定两位小数的金额文本。 */
    public static String format(BigDecimal value) {
        return round(value).toPlainString();
    }
}
