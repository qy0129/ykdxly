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

/** 将重量值按预置的单位倍率进行精确换算。 */
public final class WeightTool implements Tool {

    public static final String NAME = "weight_convert";

    private static final class UnitInfo {
        final String name;
        final String factorStr;
        final BigDecimal factor;

        UnitInfo(String name, String factorStr) {
            this.name = name;
            this.factorStr = factorStr;
            if (factorStr.contains("/")) {
                String[] parts = factorStr.split("/");
                this.factor = DecimalUtils.div(DecimalUtils.of(parts[0]), DecimalUtils.of(parts[1]));
            } else {
                this.factor = DecimalUtils.of(factorStr);
            }
        }
    }

    private static final LinkedHashMap<String, UnitInfo> UNITS = new LinkedHashMap<>();

    static {
        UNITS.put("g", new UnitInfo("克", "1"));
        UNITS.put("mg", new UnitInfo("毫克", "0.001"));
        UNITS.put("ug", new UnitInfo("微克", "0.000001"));
        UNITS.put("lb", new UnitInfo("磅", "453.59237"));
        UNITS.put("t", new UnitInfo("吨", "1000000"));
        UNITS.put("oz", new UnitInfo("盎司", "28.349523125"));
        UNITS.put("ct", new UnitInfo("克拉", "0.2"));
        UNITS.put("st", new UnitInfo("英石", "6350.29318"));
        UNITS.put("dan", new UnitInfo("担", "50000"));
        UNITS.put("jin", new UnitInfo("斤", "500"));
        UNITS.put("qian", new UnitInfo("钱", "5"));
        UNITS.put("liang", new UnitInfo("两", "50"));
        UNITS.put("jin_tw", new UnitInfo("台斤", "600"));
        for (Map.Entry<String, UnitInfo> e : new LinkedHashMap<>(UNITS).entrySet()) {
            UNITS.put(e.getValue().name, new UnitInfo(e.getValue().name, e.getValue().factorStr));
        }
        UNITS.put("ton", new UnitInfo("吨", "1000000"));
        UNITS.put("tons", new UnitInfo("吨", "1000000"));
        UNITS.put("tonne", new UnitInfo("吨", "1000000"));
        UNITS.put("tonnes", new UnitInfo("吨", "1000000"));
        UNITS.put("kilogram", new UnitInfo("千克", "1000"));
        UNITS.put("kilograms", new UnitInfo("千克", "1000"));
        UNITS.put("kg", new UnitInfo("千克", "1000"));
        UNITS.put("gram", new UnitInfo("克", "1"));
        UNITS.put("grams", new UnitInfo("克", "1"));
        UNITS.put("pound", new UnitInfo("磅", "453.59237"));
        UNITS.put("pounds", new UnitInfo("磅", "453.59237"));
        UNITS.put("ounce", new UnitInfo("盎司", "28.349523125"));
        UNITS.put("ounces", new UnitInfo("盎司", "28.349523125"));
    }

    private final ToolDefinition definition;

    /** 创建重量换算工具并声明 Function Calling 参数。 */
    public WeightTool() {
        JsonObject properties = new JsonObject();
        properties.add("value", ToolDefinition.stringProperty("要转换的数值，如 1"));
        properties.add("from", ToolDefinition.stringProperty("源单位，支持 kg/千克、g/克、t/ton/吨、mg/毫克、lb/磅、oz/盎司、jin/斤、liang/两 等"));
        properties.add("to", ToolDefinition.stringProperty("目标单位，支持 kg/千克、g/克、t/ton/吨、mg/毫克、lb/磅、oz/盎司、jin/斤、liang/两 等"));
        this.definition = new ToolDefinition(
                NAME, "重量换算",
                "重量单位换算，支持千克、克、毫克、微克、吨、磅、盎司、克拉、英石、担、斤、钱、两、台斤等",
                ToolDefinition.objectParameters(properties, "value", "from", "to"), true);
    }

    @Override
    /** 返回重量换算工具的标准定义。 */
    public ToolDefinition definition() { return definition; }

    @Override
    /** 读取输入数值和两个单位，计算并返回换算结果。 */
    public ToolResult execute(ToolContext context, JsonObject arguments) {
        String valueStr = ToolArguments.requireString(arguments, "value");
        String fromKey = ToolArguments.requireString(arguments, "from").toLowerCase();
        String toKey = ToolArguments.requireString(arguments, "to").toLowerCase();

        UnitInfo fromUnit = findUnit(fromKey);
        UnitInfo toUnit = findUnit(toKey);
        if (fromUnit == null || toUnit == null) {
            return ToolResult.failure("不支持的单位");
        }

        BigDecimal value = DecimalUtils.of(valueStr);
        BigDecimal base = DecimalUtils.mul(value, fromUnit.factor);
        BigDecimal result = DecimalUtils.div(base, toUnit.factor);

        return ToolResult.success("━━━━ 重量换算 ━━━━\n"
                + DecimalUtils.fmt(value) + " " + fromUnit.name + " = " + DecimalUtils.fmt(result) + " " + toUnit.name
                + "\n━━━━━━━━━━━━━━");
    }

    /** 从英文缩写、中文名称或多个候选别名中查找单位。 */
    private UnitInfo findUnit(String key) {
        return UnitAliasResolver.find(UNITS, key);
    }
}
