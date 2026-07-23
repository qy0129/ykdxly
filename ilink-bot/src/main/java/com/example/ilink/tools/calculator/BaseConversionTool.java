package com.example.ilink.tools.calculator;

import com.example.ilink.tools.core.Tool;
import com.example.ilink.tools.core.ToolArguments;
import com.example.ilink.tools.core.ToolContext;
import com.example.ilink.tools.core.ToolDefinition;
import com.example.ilink.tools.core.ToolResult;
import com.google.gson.JsonObject;

/** 在二、八、十和十六进制之间转换整数表示。 */
public final class BaseConversionTool implements Tool {

    public static final String NAME = "base_convert";

    private final ToolDefinition definition;

    /** 创建进制转换工具并声明 Function Calling 参数。 */
    public BaseConversionTool() {
        JsonObject properties = new JsonObject();
        properties.add("value", ToolDefinition.stringProperty("要转换的数值，如 FF、1010"));
        properties.add("from_base", ToolDefinition.integerProperty("源进制，支持 2、8、10、16", 2, 16));
        properties.add("to_base", ToolDefinition.integerProperty("目标进制，支持 2、8、10、16，默认为 10", 2, 16));
        this.definition = new ToolDefinition(
                NAME, "进制转换",
                "进制转换，支持二进制(2)、八进制(8)、十进制(10)、十六进制(16)之间的互相转换",
                ToolDefinition.objectParameters(properties, "value", "from_base"), true);
    }

    @Override
    /** 返回进制转换工具的标准定义。 */
    public ToolDefinition definition() { return definition; }

    @Override
    /** 验证进制后，将源进制整数先解析为十进制，再输出目标进制文本。 */
    public ToolResult execute(ToolContext context, JsonObject arguments) {
        String value = ToolArguments.requireString(arguments, "value");
        int fromBase = ToolArguments.integer(arguments, "from_base", 10);
        int toBase = ToolArguments.integer(arguments, "to_base", 10);

        if (!isValidBase(fromBase) || !isValidBase(toBase)) {
            return ToolResult.failure("不支持的进制，仅支持 2、8、10、16");
        }

        try {
            long decimalValue = Long.parseLong(value, fromBase);
            String result = Long.toString(decimalValue, toBase).toUpperCase();

            return ToolResult.success("━━━━ 进制转换 ━━━━\n"
                    + value + " (" + fromBase + "进制) = " + result + " (" + toBase + "进制)"
                    + "\n━━━━━━━━━━━━━━");
        } catch (NumberFormatException e) {
            return ToolResult.failure("无效的数值: " + value + " 不是有效的 " + fromBase + " 进制数");
        }
    }

    /** 判断输入是否属于本工具承诺支持的四种进制。 */
    private static boolean isValidBase(int base) {
        return base == 2 || base == 8 || base == 10 || base == 16;
    }
}
