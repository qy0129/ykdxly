package com.example.ilink.tools.math;

import com.example.ilink.tools.core.Tool;
import com.example.ilink.tools.core.ToolArguments;
import com.example.ilink.tools.core.ToolContext;
import com.example.ilink.tools.core.ToolDefinition;
import com.example.ilink.tools.core.ToolResult;
import com.example.ilink.tools.core.MoneyUtils;
import com.google.gson.JsonObject;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 基础计算工具。
 *
 * <p>支持四则运算、百分比计算和总价计算。所有金额结果统一保留两位小数，
 * 不执行用户提交的 JavaScript 或 Java 代码。</p>
 */
public final class CalculatorTool implements Tool {

    public static final String NAME = "calculate";
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    private final ToolDefinition definition;

    /** 创建基础计算工具。 */
    public CalculatorTool() {
        JsonObject properties = new JsonObject();
        properties.add("operation", ToolDefinition.enumStringProperty(
                "计算类型",
                "add", "subtract", "multiply", "divide", "percentage", "total_price"));
        properties.add("left", ToolDefinition.numberProperty(
                "四则运算的第一个数，或百分比计算的基数；其他类型填0", -1_000_000_000, 1_000_000_000));
        properties.add("right", ToolDefinition.numberProperty(
                "四则运算的第二个数，或 percentage 要计算的百分数；其他类型填0", -1_000_000_000, 1_000_000_000));
        properties.add("quantity", ToolDefinition.integerProperty(
                "总价计算的购买数量，其他类型填1", 0, 1_000_000));
        properties.add("unit_price", ToolDefinition.numberProperty(
                "总价计算的单价，其他类型填0", 0, 1_000_000_000));
        properties.add("discount_percent", ToolDefinition.numberProperty(
                "总价计算的折扣百分比，例如八五折填写15，其他类型填0", 0, 100));
        this.definition = new ToolDefinition(
                NAME,
                "基础计算",
                "执行加减乘除、百分比或商品总价计算。只处理结构化数字，不执行任意代码。",
                ToolDefinition.objectParameters(properties,
                        "operation", "left", "right", "quantity", "unit_price", "discount_percent"),
                true);
    }

    /** 返回计算工具定义。 */
    @Override
    public ToolDefinition definition() {
        return definition;
    }

    /** 根据指定操作计算结果并返回计算公式。 */
    @Override
    public ToolResult execute(ToolContext context, JsonObject arguments) {
        String operation = ToolArguments.requireString(arguments, "operation");
        BigDecimal left = ToolArguments.decimal(arguments, "left", BigDecimal.ZERO);
        BigDecimal right = ToolArguments.decimal(arguments, "right", BigDecimal.ZERO);
        int quantity = ToolArguments.integer(arguments, "quantity", 1);
        BigDecimal unitPrice = ToolArguments.decimal(arguments, "unit_price", BigDecimal.ZERO);
        BigDecimal discountPercent = ToolArguments.decimal(
                arguments, "discount_percent", BigDecimal.ZERO);

        CalculationOutput output;
        try {
            output = switch (operation) {
                case "add" -> result("加法", left.add(right), left + " + " + right);
                case "subtract" -> result("减法", left.subtract(right), left + " - " + right);
                case "multiply" -> result("乘法", left.multiply(right), left + " × " + right);
                case "divide" -> divide(left, right);
                case "percentage" -> result("百分比", left.multiply(right).divide(ONE_HUNDRED),
                        left + " × " + right + "%");
                case "total_price" -> totalPrice(unitPrice, quantity, discountPercent);
                default -> throw new IllegalArgumentException("不支持的计算类型: " + operation);
            };
        } catch (ArithmeticException | IllegalArgumentException e) {
            return ToolResult.failure("计算失败：" + e.getMessage());
        }

        return ToolResult.success(output.formula() + " = " + output.result(), output);
    }

    /** 执行除法并阻止除数为零。 */
    private CalculationOutput divide(BigDecimal left, BigDecimal right) {
        if (right.compareTo(BigDecimal.ZERO) == 0) {
            throw new IllegalArgumentException("除数不能为0");
        }
        return result("除法", left.divide(right, 10, RoundingMode.HALF_UP),
                left + " ÷ " + right);
    }

    /** 计算单价乘数量并应用折扣。 */
    private CalculationOutput totalPrice(BigDecimal unitPrice, int quantity,
                                         BigDecimal discountPercent) {
        if (quantity < 0) {
            throw new IllegalArgumentException("数量不能为负数");
        }
        if (discountPercent.compareTo(BigDecimal.ZERO) < 0
                || discountPercent.compareTo(ONE_HUNDRED) > 0) {
            throw new IllegalArgumentException("折扣百分比必须在0到100之间");
        }
        BigDecimal original = unitPrice.multiply(BigDecimal.valueOf(quantity));
        BigDecimal discount = original.multiply(discountPercent).divide(ONE_HUNDRED);
        BigDecimal total = original.subtract(discount);
        String formula = unitPrice + " × " + quantity + " × (1 - "
                + discountPercent + "%)";
        return result("总价", total, formula);
    }

    /** 创建统一计算结果并按金额规则保留小数。 */
    private CalculationOutput result(String type, BigDecimal value, String formula) {
        return new CalculationOutput(type, formula, MoneyUtils.round(value));
    }

    /** 计算工具返回的结构化结果。 */
    public record CalculationOutput(String type, String formula, BigDecimal result) {
    }
}
