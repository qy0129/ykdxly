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

/** 将速度值按预置的单位倍率进行精确换算。 */
public final class SpeedTool implements Tool {

    public static final String NAME = "speed_convert";

    private final ToolDefinition definition;

    /** 创建速度换算工具并声明 Function Calling 参数。 */
    public SpeedTool() {
        JsonObject properties = new JsonObject();
        properties.add("from", ToolDefinition.stringProperty("源速度单位，如 mps/m/s(米/秒)、kmh/km/h(千米/时)、mph(英里/时)、kn/knot(节)、mach(马赫)"));
        properties.add("to", ToolDefinition.stringProperty("目标速度单位，格式同 from"));
        properties.add("value", ToolDefinition.stringProperty("待转换的数值"));
        this.definition = new ToolDefinition(
                NAME,
                "速度转换",
                "速度单位转换，支持米/秒、千米/时、英里/时、节、马赫、光速等多种速度单位",
                ToolDefinition.objectParameters(properties, "from", "to", "value"),
                true);
    }

    @Override
    /** 返回速度换算工具的标准定义。 */
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
            return ToolResult.failure("不支持的速度单位: " + from);
        }
        if (toUnit == null) {
            return ToolResult.failure("不支持的速度单位: " + to);
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
        UNITS.put("mps", new UnitInfo("米/秒", "1", DecimalUtils.of("1")));
        UNITS.put("kmh", new UnitInfo("千米/时", "0.277778", DecimalUtils.of("0.277778")));
        UNITS.put("mph", new UnitInfo("英里/时", "0.44704", DecimalUtils.of("0.44704")));
        UNITS.put("kn", new UnitInfo("节", "0.514444", DecimalUtils.of("0.514444")));
        UNITS.put("mach", new UnitInfo("马赫", "340.3", DecimalUtils.of("340.3")));
        UNITS.put("c", new UnitInfo("光速", "299792458", DecimalUtils.of("299792458")));

        for (Map.Entry<String, UnitInfo> entry : new LinkedHashMap<>(UNITS).entrySet()) {
            String cn = entry.getValue().name;
            if (!UNITS.containsKey(cn)) {
                UNITS.put(cn, entry.getValue());
            }
        }

        UNITS.put("kmperhour", UNITS.get("千米/时"));
        UNITS.put("kph", UNITS.get("千米/时"));
        UNITS.put("m/s", UNITS.get("mps"));
        UNITS.put("km/h", UNITS.get("kmh"));
        UNITS.put("mi/h", UNITS.get("mph"));
        UNITS.put("knot", UNITS.get("节"));
        UNITS.put("knots", UNITS.get("节"));
        UNITS.put("lightspeed", UNITS.get("光速"));
    }

    /** 从英文缩写、中文名称或多个候选别名中查找速度单位。 */
    public static UnitInfo findUnit(String key) {
        return UnitAliasResolver.find(UNITS, key);
    }
}
