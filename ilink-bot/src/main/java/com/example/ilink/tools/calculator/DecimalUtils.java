package com.example.ilink.tools.calculator;

import java.math.BigDecimal;
import java.math.MathContext;

/**
 * 计算工具共用的高精度小数运算方法。
 *
 * <p>所有需要小数计算的工具均应通过本类创建和运算 {@link BigDecimal}，
 * 从而避免 {@code double} 的二进制精度误差。</p>
 */
public final class DecimalUtils {

    private static final MathContext MC = MathContext.DECIMAL128;
    private DecimalUtils() {
    }

    /** 从文本创建高精度小数，适合处理模型返回的数字参数。 */
    public static BigDecimal of(String value) {
        return new BigDecimal(value, MC);
    }

    /** 返回两个小数的和。 */
    public static BigDecimal add(BigDecimal a, BigDecimal b) {
        return a.add(b, MC);
    }

    /** 返回第一个小数减去第二个小数的差。 */
    public static BigDecimal sub(BigDecimal a, BigDecimal b) {
        return a.subtract(b, MC);
    }

    /** 返回两个小数的积。 */
    public static BigDecimal mul(BigDecimal a, BigDecimal b) {
        return a.multiply(b, MC);
    }

    /** 返回两个小数的商，使用 DECIMAL128 精度避免无限小数报错。 */
    public static BigDecimal div(BigDecimal a, BigDecimal b) {
        return a.divide(b, MC);
    }

    /** 返回两个小数中较小的值。 */
    public static BigDecimal min(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) < 0 ? a : b;
    }

    /** 返回两个小数中较大的值。 */
    public static BigDecimal max(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) > 0 ? a : b;
    }

    /** 去除无意义的末尾零，生成适合展示的普通数字文本。 */
    public static String fmt(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
