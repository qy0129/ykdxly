package com.example.ilink.tools.calculator;

import com.example.ilink.tools.core.Tool;
import com.example.ilink.tools.core.ToolArguments;
import com.example.ilink.tools.core.ToolContext;
import com.example.ilink.tools.core.ToolDefinition;
import com.example.ilink.tools.core.ToolResult;
import com.google.gson.JsonObject;

import java.math.BigDecimal;

/** 根据身高和体重计算 BMI，并给出体重区间参考。 */
public final class BMITool implements Tool {

    public static final String NAME = "bmi_calc";

    private static final BigDecimal LOW = DecimalUtils.of("18.5");
    private static final BigDecimal NORMAL = DecimalUtils.of("24");
    private static final BigDecimal OVERWEIGHT = DecimalUtils.of("28");
    private static final BigDecimal OBESE = DecimalUtils.of("32");

    private final ToolDefinition definition;

    /** 创建 BMI 计算工具并声明 Function Calling 参数。 */
    public BMITool() {
        JsonObject properties = new JsonObject();
        properties.add("height_cm", ToolDefinition.stringProperty("身高，单位厘米，如 170"));
        properties.add("weight_kg", ToolDefinition.stringProperty("体重，单位千克，如 65"));
        this.definition = new ToolDefinition(
                NAME, "BMI 计算",
                "计算身体质量指数(BMI)，评估体重状况并给出健康体重范围",
                ToolDefinition.objectParameters(properties, "height_cm", "weight_kg"), true);
    }

    @Override
    /** 返回 BMI 计算工具的标准定义。 */
    public ToolDefinition definition() { return definition; }

    @Override
    /** 将厘米转换为米后计算 BMI，并根据阈值给出分类。 */
    public ToolResult execute(ToolContext context, JsonObject arguments) {
        String heightStr = ToolArguments.requireString(arguments, "height_cm");
        String weightStr = ToolArguments.requireString(arguments, "weight_kg");

        BigDecimal heightCm = DecimalUtils.of(heightStr);
        BigDecimal weightKg = DecimalUtils.of(weightStr);

        if (heightCm.compareTo(BigDecimal.ZERO) <= 0 || weightKg.compareTo(BigDecimal.ZERO) <= 0) {
            return ToolResult.failure("身高和体重必须大于 0");
        }

        BigDecimal heightM = DecimalUtils.div(heightCm, DecimalUtils.of("100"));
        BigDecimal bmi = DecimalUtils.div(weightKg, DecimalUtils.mul(heightM, heightM));

        String category;
        if (bmi.compareTo(LOW) < 0) {
            category = "偏瘦";
        } else if (bmi.compareTo(NORMAL) < 0) {
            category = "正常";
        } else if (bmi.compareTo(OVERWEIGHT) < 0) {
            category = "偏胖";
        } else if (bmi.compareTo(OBESE) < 0) {
            category = "肥胖";
        } else {
            category = "严重肥胖";
        }

        BigDecimal healthyMin = DecimalUtils.mul(LOW, DecimalUtils.mul(heightM, heightM));
        BigDecimal healthyMax = DecimalUtils.mul(NORMAL, DecimalUtils.mul(heightM, heightM));

        return ToolResult.success("━━━━ BMI 计算 ━━━━\n"
                + "身高: " + DecimalUtils.fmt(heightCm) + " cm\n"
                + "体重: " + DecimalUtils.fmt(weightKg) + " kg\n"
                + "BMI: " + DecimalUtils.fmt(bmi) + "\n"
                + "评估: " + category + "\n"
                + "健康体重范围: " + DecimalUtils.fmt(healthyMin) + " ~ " + DecimalUtils.fmt(healthyMax) + " kg"
                + "\n━━━━━━━━━━━━━━");
    }
}
