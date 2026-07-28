package com.example.ilink.capabilities.calculator;

import com.example.ilink.application.tooling.Tool;
import com.example.ilink.application.tooling.ToolArguments;
import com.example.ilink.application.tooling.ToolContext;
import com.example.ilink.application.tooling.ToolDefinition;
import com.example.ilink.application.tooling.ToolResult;
import com.google.gson.JsonObject;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/** 将体积值按预置的单位倍率进行精确换算。 */
public final class VolumeTool implements Tool {

    public static final String NAME = "volume_convert";

    private final ToolDefinition definition;

    /** 创建体积换算工具并声明 Function Calling 参数。 */
    public VolumeTool() {
        JsonObject properties = new JsonObject();
        properties.add("from", ToolDefinition.stringProperty("源体积单位，如 cum/cubicmeter/立方米、lit/liter/升、ml/milliliter/毫升、gal/gallon/加仑、bbl/桶、cuft/立方英尺"));
        properties.add("to", ToolDefinition.stringProperty("目标体积单位，格式同 from"));
        properties.add("value", ToolDefinition.stringProperty("待转换的数值"));
        this.definition = new ToolDefinition(
                NAME,
                "体积转换",
                "体积单位转换，支持立方米、升、毫升、美加仑、英加仑、桶、立方英尺、立方英寸、立方码等多种体积单位",
                ToolDefinition.objectParameters(properties, "from", "to", "value"),
                true);
    }

    @Override
    /** 返回体积换算工具的标准定义。 */
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    /** 读取输入数值和两个单位，计算并返回换算结果。 */
    public ToolResult execute(ToolContext context, JsonObject arguments) {
        String from = ToolArguments.requireString(arguments, "from");
        String to = ToolArguments.requireString(arguments, "to");
        String value = ToolArguments.requireString(arguments, "value");

        UnitInfo fromUnit = findUnit(from);
        UnitInfo toUnit = findUnit(to);
        if (fromUnit == null) {
            return ToolResult.failure("不支持的体积单位: " + from);
        }
        if (toUnit == null) {
            return ToolResult.failure("不支持的体积单位: " + to);
        }

        BigDecimal input = DecimalUtils.of(value);
        BigDecimal result = DecimalUtils.div(
                DecimalUtils.mul(input, fromUnit.factor),
                toUnit.factor);
        return ToolResult.success(DecimalUtils.fmt(result));
    }

    public static final class UnitInfo {
        public final String name;
        public final String factorStr;
        public final BigDecimal factor;

        public UnitInfo(String name, String factorStr, BigDecimal factor) {
            this.name = name;
            this.factorStr = factorStr;
            this.factor = factor;
        }
    }

    public static final LinkedHashMap<String, UnitInfo> UNITS = new LinkedHashMap<>();

    static {
        UNITS.put("cum", new UnitInfo("立方米", "1", DecimalUtils.of("1")));
        UNITS.put("lit", new UnitInfo("升", "0.001", DecimalUtils.of("0.001")));
        UNITS.put("ml", new UnitInfo("毫升", "0.000001", DecimalUtils.of("0.000001")));
        UNITS.put("gal_us", new UnitInfo("美加仑", "0.00378541", DecimalUtils.of("0.00378541")));
        UNITS.put("gal_uk", new UnitInfo("英加仑", "0.00454609", DecimalUtils.of("0.00454609")));
        UNITS.put("bbl", new UnitInfo("桶", "0.158987", DecimalUtils.of("0.158987")));
        UNITS.put("cuft", new UnitInfo("立方英尺", "0.0283168", DecimalUtils.of("0.0283168")));
        UNITS.put("cuin", new UnitInfo("立方英寸", "0.0000163871", DecimalUtils.of("0.0000163871")));
        UNITS.put("cuyd", new UnitInfo("立方码", "0.764555", DecimalUtils.of("0.764555")));

        for (Map.Entry<String, UnitInfo> entry : new LinkedHashMap<>(UNITS).entrySet()) {
            String cn = entry.getValue().name;
            if (!UNITS.containsKey(cn)) {
                UNITS.put(cn, entry.getValue());
            }
        }

        UNITS.put("liter", UNITS.get("升"));
        UNITS.put("liters", UNITS.get("升"));
        UNITS.put("litre", UNITS.get("升"));
        UNITS.put("litres", UNITS.get("升"));
        UNITS.put("milliliter", UNITS.get("毫升"));
        UNITS.put("milliliters", UNITS.get("毫升"));
        UNITS.put("millilitre", UNITS.get("毫升"));
        UNITS.put("millilitres", UNITS.get("毫升"));
        UNITS.put("gallon", UNITS.get("美加仑"));
        UNITS.put("gallons", UNITS.get("美加仑"));
    }

    /** 从英文缩写、中文名称或多个候选别名中查找体积单位。 */
    public static UnitInfo findUnit(String key) {
        return UnitAliasResolver.find(UNITS, key);
    }
}
