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

/** 将长度值按预置的单位倍率进行精确换算。 */
public final class LengthTool implements Tool {

    public static final String NAME = "length_convert";

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
        UNITS.put("m", new UnitInfo("米", "1"));
        UNITS.put("cm", new UnitInfo("厘米", "0.01"));
        UNITS.put("mm", new UnitInfo("毫米", "0.001"));
        UNITS.put("nm", new UnitInfo("纳米", "1e-9"));
        UNITS.put("pm", new UnitInfo("皮米", "1e-12"));
        UNITS.put("nmi", new UnitInfo("海里", "1852"));
        UNITS.put("mi", new UnitInfo("英里", "1609.344"));
        UNITS.put("fur", new UnitInfo("弗隆", "201.168"));
        UNITS.put("ftm", new UnitInfo("英寻", "1.8288"));
        UNITS.put("yd", new UnitInfo("码", "0.9144"));
        UNITS.put("ft", new UnitInfo("英尺", "0.3048"));
        UNITS.put("in", new UnitInfo("英寸", "0.0254"));
        UNITS.put("gongli", new UnitInfo("公里", "1000"));
        UNITS.put("li", new UnitInfo("里", "500"));
        UNITS.put("zhang", new UnitInfo("丈", "10/3"));
        UNITS.put("chi", new UnitInfo("尺", "1/3"));
        UNITS.put("cun", new UnitInfo("寸", "1/30"));
        UNITS.put("fen", new UnitInfo("分", "1/300"));
        UNITS.put("lii", new UnitInfo("厘", "1/3000"));
        UNITS.put("hao", new UnitInfo("毫", "1/30000"));
        UNITS.put("ld", new UnitInfo("光秒", "299792458"));
        UNITS.put("ly", new UnitInfo("光年", "9460730472580800"));
        for (Map.Entry<String, UnitInfo> e : new LinkedHashMap<>(UNITS).entrySet()) {
            UNITS.put(e.getValue().name, new UnitInfo(e.getValue().name, e.getValue().factorStr));
        }
        UNITS.put("meter", new UnitInfo("米", "1"));
        UNITS.put("meters", new UnitInfo("米", "1"));
        UNITS.put("metre", new UnitInfo("米", "1"));
        UNITS.put("metres", new UnitInfo("米", "1"));
        UNITS.put("centimeter", new UnitInfo("厘米", "0.01"));
        UNITS.put("centimeters", new UnitInfo("厘米", "0.01"));
        UNITS.put("centimetre", new UnitInfo("厘米", "0.01"));
        UNITS.put("centimetres", new UnitInfo("厘米", "0.01"));
        UNITS.put("millimeter", new UnitInfo("毫米", "0.001"));
        UNITS.put("millimeters", new UnitInfo("毫米", "0.001"));
        UNITS.put("millimetre", new UnitInfo("毫米", "0.001"));
        UNITS.put("millimetres", new UnitInfo("毫米", "0.001"));
        UNITS.put("kilometer", new UnitInfo("公里", "1000"));
        UNITS.put("kilometers", new UnitInfo("公里", "1000"));
        UNITS.put("kilometre", new UnitInfo("公里", "1000"));
        UNITS.put("kilometres", new UnitInfo("公里", "1000"));
        UNITS.put("foot", new UnitInfo("英尺", "0.3048"));
        UNITS.put("feet", new UnitInfo("英尺", "0.3048"));
        UNITS.put("inch", new UnitInfo("英寸", "0.0254"));
        UNITS.put("inches", new UnitInfo("英寸", "0.0254"));
        UNITS.put("yard", new UnitInfo("码", "0.9144"));
        UNITS.put("yards", new UnitInfo("码", "0.9144"));
        UNITS.put("mile", new UnitInfo("英里", "1609.344"));
        UNITS.put("miles", new UnitInfo("英里", "1609.344"));
    }

    private final ToolDefinition definition;

    /** 创建长度换算工具并声明 Function Calling 参数。 */
    public LengthTool() {
        JsonObject properties = new JsonObject();
        properties.add("value", ToolDefinition.stringProperty("要转换的数值，如 1"));
        properties.add("from", ToolDefinition.stringProperty("源单位，支持 m/meter/米、cm/centimeter/厘米、mm/millimeter/毫米、km/kilometer/公里、ft/foot/英尺、in/inch/英寸、yd/yard/码、mi/mile/英里 等"));
        properties.add("to", ToolDefinition.stringProperty("目标单位，支持 m/meter/米、cm/centimeter/厘米、mm/millimeter/毫米、km/kilometer/公里、ft/foot/英尺、in/inch/英寸、yd/yard/码、mi/mile/英里 等"));
        this.definition = new ToolDefinition(
                NAME, "长度换算",
                "长度单位换算，支持米、厘米、毫米、纳米、皮米、海里、英里、弗隆、英寻、码、英尺、英寸、公里、里、丈、尺、寸、分、厘、毫、光秒、光年等",
                ToolDefinition.objectParameters(properties, "value", "from", "to"), true);
    }

    @Override
    /** 返回长度换算工具的标准定义。 */
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

        return ToolResult.success("━━━━ 长度换算 ━━━━\n"
                + DecimalUtils.fmt(value) + " " + fromUnit.name + " = " + DecimalUtils.fmt(result) + " " + toUnit.name
                + "\n━━━━━━━━━━━━━━");
    }

    /** 从英文缩写、中文名称或多个候选别名中查找单位。 */
    private UnitInfo findUnit(String key) {
        return UnitAliasResolver.find(UNITS, key);
    }
}
