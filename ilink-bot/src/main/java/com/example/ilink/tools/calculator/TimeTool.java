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

/** 将时间长度按预置的单位倍率进行精确换算。 */
public final class TimeTool implements Tool {

    public static final String NAME = "time_convert";

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
        UNITS.put("yr", new UnitInfo("年", "31536000"));
        UNITS.put("wk", new UnitInfo("周", "604800"));
        UNITS.put("d", new UnitInfo("天", "86400"));
        UNITS.put("hr", new UnitInfo("时", "3600"));
        UNITS.put("min", new UnitInfo("分", "60"));
        UNITS.put("s", new UnitInfo("秒", "1"));
        UNITS.put("ms", new UnitInfo("毫秒", "0.001"));
        UNITS.put("us", new UnitInfo("微秒", "0.000001"));
        UNITS.put("ps", new UnitInfo("皮秒", "1e-12"));
        for (Map.Entry<String, UnitInfo> e : new LinkedHashMap<>(UNITS).entrySet()) {
            UNITS.put(e.getValue().name, new UnitInfo(e.getValue().name, e.getValue().factorStr));
        }
        UNITS.put("year", new UnitInfo("年", "31536000"));
        UNITS.put("years", new UnitInfo("年", "31536000"));
        UNITS.put("week", new UnitInfo("周", "604800"));
        UNITS.put("weeks", new UnitInfo("周", "604800"));
        UNITS.put("day", new UnitInfo("天", "86400"));
        UNITS.put("days", new UnitInfo("天", "86400"));
        UNITS.put("hour", new UnitInfo("时", "3600"));
        UNITS.put("hours", new UnitInfo("时", "3600"));
        UNITS.put("minute", new UnitInfo("分", "60"));
        UNITS.put("minutes", new UnitInfo("分", "60"));
        UNITS.put("second", new UnitInfo("秒", "1"));
        UNITS.put("seconds", new UnitInfo("秒", "1"));
    }

    private final ToolDefinition definition;

    /** 创建时间换算工具并声明 Function Calling 参数。 */
    public TimeTool() {
        JsonObject properties = new JsonObject();
        properties.add("value", ToolDefinition.stringProperty("要转换的数值，如 1"));
        properties.add("from", ToolDefinition.stringProperty("源时间单位，如 d/day/天、hr/hour/时、min/minute/分、s/second/秒、wk/week/周、yr/year/年"));
        properties.add("to", ToolDefinition.stringProperty("目标时间单位，如 d/day/天、hr/hour/时、min/minute/分、s/second/秒、wk/week/周、yr/year/年"));
        this.definition = new ToolDefinition(
                NAME, "时间换算",
                "时间单位换算，支持年、周、天、时、分、秒、毫秒、微秒、皮秒等",
                ToolDefinition.objectParameters(properties, "value", "from", "to"), true);
    }

    @Override
    /** 返回时间换算工具的标准定义。 */
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
            return ToolResult.failure("不支持的时间单位");
        }

        BigDecimal value = DecimalUtils.of(valueStr);
        BigDecimal base = DecimalUtils.mul(value, fromUnit.factor);
        BigDecimal result = DecimalUtils.div(base, toUnit.factor);

        return ToolResult.success("━━━━ 时间换算 ━━━━\n"
                + DecimalUtils.fmt(value) + " " + fromUnit.name + " = " + DecimalUtils.fmt(result) + " " + toUnit.name
                + "\n━━━━━━━━━━━━━━");
    }

    /** 从英文缩写、中文名称或多个候选别名中查找单位。 */
    private UnitInfo findUnit(String key) {
        return UnitAliasResolver.find(UNITS, key);
    }
}
