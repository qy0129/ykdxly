package com.example.ilink.tools.calculator;

import com.example.ilink.tools.core.Tool;
import com.example.ilink.tools.core.ToolArguments;
import com.example.ilink.tools.core.ToolContext;
import com.example.ilink.tools.core.ToolDefinition;
import com.example.ilink.tools.core.ToolResult;
import com.google.gson.JsonObject;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/** 将面积值按预置的单位倍率进行精确换算。 */
public final class AreaTool implements Tool {

    public static final String NAME = "area_convert";

    private final ToolDefinition definition;

    /** 创建面积换算工具并声明 Function Calling 参数。 */
    public AreaTool() {
        JsonObject properties = new JsonObject();
        properties.add("from", ToolDefinition.stringProperty("源面积单位，如 sqm/squaremeter/平方米、sqkm/squarekilometer/平方千米、ha/hectare/公顷、mu/亩、ac/acre/英亩、sqft/平方英尺"));
        properties.add("to", ToolDefinition.stringProperty("目标面积单位，格式同 from"));
        properties.add("value", ToolDefinition.stringProperty("待转换的数值"));
        this.definition = new ToolDefinition(
                NAME,
                "面积转换",
                "面积单位转换，支持平方米、平方千米、公顷、英亩、平方英尺等多种面积单位",
                ToolDefinition.objectParameters(properties, "from", "to", "value"),
                true);
    }

    @Override
    /** 返回面积换算工具的标准定义。 */
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
            return ToolResult.failure("不支持的面积单位: " + from);
        }
        if (toUnit == null) {
            return ToolResult.failure("不支持的面积单位: " + to);
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
        UNITS.put("sqm", new UnitInfo("平方米", "1", DecimalUtils.of("1")));
        UNITS.put("sqkm", new UnitInfo("平方千米", "1000000", DecimalUtils.of("1000000")));
        UNITS.put("ha", new UnitInfo("公顷", "10000", DecimalUtils.of("10000")));
        UNITS.put("are", new UnitInfo("公亩", "100", DecimalUtils.of("100")));
        UNITS.put("sqdm", new UnitInfo("平方分米", "0.01", DecimalUtils.of("0.01")));
        UNITS.put("sqcm", new UnitInfo("平方厘米", "0.0001", DecimalUtils.of("0.0001")));
        UNITS.put("sqmm", new UnitInfo("平方毫米", "0.000001", DecimalUtils.of("0.000001")));
        UNITS.put("squm", new UnitInfo("平方微米", "1e-12", DecimalUtils.of("1e-12")));
        UNITS.put("ac", new UnitInfo("英亩", "4046.8564224", DecimalUtils.of("4046.8564224")));
        UNITS.put("sqmi", new UnitInfo("平方英里", "2589988.110336", DecimalUtils.of("2589988.110336")));
        UNITS.put("sqyd", new UnitInfo("平方码", "0.83612736", DecimalUtils.of("0.83612736")));
        UNITS.put("sqft", new UnitInfo("平方英尺", "0.09290304", DecimalUtils.of("0.09290304")));
        UNITS.put("sqin", new UnitInfo("平方英寸", "0.00064516", DecimalUtils.of("0.00064516")));
        UNITS.put("qing", new UnitInfo("顷", "66666.6667", DecimalUtils.of("66666.6667")));
        UNITS.put("mu", new UnitInfo("亩", "666.666667", DecimalUtils.of("666.666667")));
        UNITS.put("sqchi", new UnitInfo("平方尺", "0.111111", DecimalUtils.of("0.111111")));
        UNITS.put("sqcun", new UnitInfo("平方寸", "0.00111111", DecimalUtils.of("0.00111111")));
        UNITS.put("sqkm_ch", new UnitInfo("平方公里", "1000000", DecimalUtils.of("1000000")));

        for (Map.Entry<String, UnitInfo> entry : new LinkedHashMap<>(UNITS).entrySet()) {
            String cn = entry.getValue().name;
            if (!UNITS.containsKey(cn)) {
                UNITS.put(cn, entry.getValue());
            }
        }

        UNITS.put("squaremeter", UNITS.get("平方米"));
        UNITS.put("squaremeters", UNITS.get("平方米"));
        UNITS.put("squarekilometer", UNITS.get("平方千米"));
        UNITS.put("hectare", UNITS.get("公顷"));
        UNITS.put("hectares", UNITS.get("公顷"));
        UNITS.put("acre", UNITS.get("英亩"));
        UNITS.put("acres", UNITS.get("英亩"));
    }

    /** 从英文缩写、中文名称或多个候选别名中查找面积单位。 */
    public static UnitInfo findUnit(String key) {
        return UnitAliasResolver.find(UNITS, key);
    }
}
