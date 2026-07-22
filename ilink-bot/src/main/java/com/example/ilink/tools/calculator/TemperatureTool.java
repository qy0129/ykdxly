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

/** 以摄氏度为中间单位完成不同温标之间的换算。 */
public final class TemperatureTool implements Tool {

    public static final String NAME = "temperature_convert";

    private interface TempConv {
        BigDecimal toC(BigDecimal value);
        BigDecimal fromC(BigDecimal celsius);
    }

    private static final class TempUnit {
        final String name;
        final String displayName;
        final TempConv conv;

        TempUnit(String name, String displayName, TempConv conv) {
            this.name = name;
            this.displayName = displayName;
            this.conv = conv;
        }
    }

    private static final LinkedHashMap<String, TempUnit> UNITS = new LinkedHashMap<>();

    static {
        UNITS.put("c", new TempUnit("摄氏度", "°C", new TempConv() {
            @Override
            public BigDecimal toC(BigDecimal value) { return value; }
            @Override
            public BigDecimal fromC(BigDecimal celsius) { return celsius; }
        }));
        UNITS.put("f", new TempUnit("华氏度", "°F", new TempConv() {
            @Override
            public BigDecimal toC(BigDecimal value) {
                return DecimalUtils.div(DecimalUtils.sub(value, DecimalUtils.of("32")), DecimalUtils.of("1.8"));
            }
            @Override
            public BigDecimal fromC(BigDecimal celsius) {
                return DecimalUtils.add(DecimalUtils.mul(celsius, DecimalUtils.of("1.8")), DecimalUtils.of("32"));
            }
        }));
        UNITS.put("k", new TempUnit("开尔文", "K", new TempConv() {
            @Override
            public BigDecimal toC(BigDecimal value) {
                return DecimalUtils.sub(value, DecimalUtils.of("273.15"));
            }
            @Override
            public BigDecimal fromC(BigDecimal celsius) {
                return DecimalUtils.add(celsius, DecimalUtils.of("273.15"));
            }
        }));
        UNITS.put("r", new TempUnit("兰氏度", "°R", new TempConv() {
            @Override
            public BigDecimal toC(BigDecimal value) {
                return DecimalUtils.div(DecimalUtils.sub(value, DecimalUtils.of("491.67")), DecimalUtils.of("1.8"));
            }
            @Override
            public BigDecimal fromC(BigDecimal celsius) {
                return DecimalUtils.add(DecimalUtils.mul(celsius, DecimalUtils.of("1.8")), DecimalUtils.of("491.67"));
            }
        }));
        UNITS.put("re", new TempUnit("列氏度", "°Re", new TempConv() {
            @Override
            public BigDecimal toC(BigDecimal value) {
                return DecimalUtils.div(DecimalUtils.mul(value, DecimalUtils.of("5")), DecimalUtils.of("4"));
            }
            @Override
            public BigDecimal fromC(BigDecimal celsius) {
                return DecimalUtils.div(DecimalUtils.mul(celsius, DecimalUtils.of("4")), DecimalUtils.of("5"));
            }
        }));
        for (Map.Entry<String, TempUnit> e : new LinkedHashMap<>(UNITS).entrySet()) {
            String cnName = e.getValue().name;
            if (!UNITS.containsKey(cnName)) {
                TempUnit existing = UNITS.get(e.getKey());
                UNITS.put(cnName, new TempUnit(existing.name, existing.displayName, existing.conv));
            }
        }
        UNITS.put("celsius", new TempUnit("摄氏度", "°C", UNITS.get("c").conv));
        UNITS.put("fahrenheit", new TempUnit("华氏度", "°F", UNITS.get("f").conv));
        UNITS.put("kelvin", new TempUnit("开尔文", "K", UNITS.get("k").conv));
    }

    private final ToolDefinition definition;

    /** 创建温度换算工具并声明 Function Calling 参数。 */
    public TemperatureTool() {
        JsonObject properties = new JsonObject();
        properties.add("value", ToolDefinition.stringProperty("要转换的数值，如 100"));
        properties.add("from", ToolDefinition.stringProperty("源温度单位，支持 c/C(摄氏度/celsius)、f/F(华氏度/fahrenheit)、k/K(开尔文/kelvin)、r/R(兰氏度)、re/Re(列氏度)"));
        properties.add("to", ToolDefinition.stringProperty("目标温度单位，支持 c/C(摄氏度/celsius)、f/F(华氏度/fahrenheit)、k/K(开尔文/kelvin)、r/R(兰氏度)、re/Re(列氏度)"));
        this.definition = new ToolDefinition(
                NAME, "温度换算",
                "温度单位换算，支持摄氏度、华氏度、开尔文、兰氏度、列氏度等",
                ToolDefinition.objectParameters(properties, "value", "from", "to"), true);
    }

    @Override
    /** 返回温度换算工具的标准定义。 */
    public ToolDefinition definition() { return definition; }

    @Override
    /** 先将输入温度转换为摄氏度，再转换为目标温标。 */
    public ToolResult execute(ToolContext context, JsonObject arguments) {
        String valueStr = ToolArguments.requireString(arguments, "value");
        String fromKey = ToolArguments.requireString(arguments, "from").toLowerCase();
        String toKey = ToolArguments.requireString(arguments, "to").toLowerCase();

        TempUnit fromUnit = findUnit(fromKey);
        TempUnit toUnit = findUnit(toKey);
        if (fromUnit == null || toUnit == null) {
            return ToolResult.failure("不支持的温度单位");
        }

        BigDecimal value = DecimalUtils.of(valueStr);
        BigDecimal celsius = fromUnit.conv.toC(value);
        BigDecimal result = toUnit.conv.fromC(celsius);

        return ToolResult.success("━━━━ 温度换算 ━━━━\n"
                + DecimalUtils.fmt(value) + " " + fromUnit.displayName + " = " + DecimalUtils.fmt(result) + " " + toUnit.displayName
                + "\n━━━━━━━━━━━━━━━━");
    }

    /** 从英文缩写、中文名称或多个候选别名中查找温标。 */
    private TempUnit findUnit(String key) {
        return UnitAliasResolver.find(UNITS, key);
    }
}
