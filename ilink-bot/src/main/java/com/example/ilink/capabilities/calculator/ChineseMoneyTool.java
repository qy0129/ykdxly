package com.example.ilink.capabilities.calculator;

import com.example.ilink.application.tooling.Tool;
import com.example.ilink.application.tooling.ToolArguments;
import com.example.ilink.application.tooling.ToolContext;
import com.example.ilink.application.tooling.ToolDefinition;
import com.example.ilink.application.tooling.ToolResult;
import com.google.gson.JsonObject;

import java.math.BigDecimal;

/** 将人民币数字金额转换为中文财务大写金额。 */
public final class ChineseMoneyTool implements Tool {

    public static final String NAME = "chinese_money";

    private static final String[] CN_DIGITS = {"零", "壹", "贰", "叁", "肆", "伍", "陆", "柒", "捌", "玖"};
    private static final String[] CN_POSITIONS = {"", "拾", "佰", "仟"};
    private static final String[] CN_SECTIONS = {"", "万", "亿"};
    private static final String CN_DOLLAR = "元";
    private static final String CN_DIME = "角";
    private static final String CN_CENT = "分";
    private static final String CN_INTEGER = "整";

    private static final BigDecimal MAX_AMOUNT = DecimalUtils.of("999999999.99");
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final ToolDefinition definition;

    /** 创建人民币大写工具并声明 Function Calling 参数。 */
    public ChineseMoneyTool() {
        JsonObject properties = new JsonObject();
        properties.add("amount", ToolDefinition.stringProperty("金额，最大 999999999.99，如 12345.67"));
        this.definition = new ToolDefinition(
                NAME, "人民币大写",
                "将阿拉伯数字金额转换为中文大写金额，支持到分，最大支持 999999999.99",
                ToolDefinition.objectParameters(properties, "amount"), true);
    }

    @Override
    /** 返回人民币大写工具的标准定义。 */
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    /** 校验金额范围后，转换整数和角分部分并拼接结果。 */
    public ToolResult execute(ToolContext context, JsonObject arguments) {
        String amountStr = ToolArguments.requireString(arguments, "amount");

        BigDecimal amount;
        try {
            amount = DecimalUtils.of(amountStr);
        } catch (Exception e) {
            return ToolResult.failure("无效的金额格式");
        }

        if (amount.compareTo(ZERO) < 0) {
            return ToolResult.failure("金额不能为负数");
        }
        if (amount.compareTo(MAX_AMOUNT) > 0) {
            return ToolResult.failure("金额超出最大支持范围（999999999.99）");
        }

        String result = convertToChinese(amount);

        return ToolResult.success("━━━━ 人民币大写 ━━━━\n"
                + "数字: " + amountStr + "\n"
                + "大写: " + result + "\n"
                + "━━━━━━━━━━━━━━");
    }

    /** 将金额分为整数部分和两位小数部分，分别生成大写文本。 */
    private String convertToChinese(BigDecimal amount) {
        long intPart = amount.longValue();
        int decimalPart = amount.remainder(BigDecimal.ONE).multiply(new BigDecimal("100")).intValue();
        decimalPart = Math.abs(decimalPart);

        StringBuilder sb = new StringBuilder();

        if (intPart == 0) {
            sb.append("零");
        } else {
            sb.append(convertInteger(String.valueOf(intPart)));
        }
        sb.append(CN_DOLLAR);

        if (decimalPart == 0) {
            sb.append(CN_INTEGER);
        } else {
            int jiao = decimalPart / 10;
            int fen = decimalPart % 10;
            if (jiao > 0) {
                sb.append(CN_DIGITS[jiao]).append(CN_DIME);
            }
            if (fen > 0) {
                if (jiao == 0) sb.append("零");
                sb.append(CN_DIGITS[fen]).append(CN_CENT);
            }
        }

        return sb.toString();
    }

    /** 按四位一组处理整数部分，组合万和亿等节单位。 */
    private String convertInteger(String numStr) {
        int len = numStr.length();
        int padLen = (4 - len % 4) % 4;
        StringBuilder padded = new StringBuilder();
        for (int i = 0; i < padLen; i++) padded.append('0');
        padded.append(numStr);

        String paddedStr = padded.toString();
        int sectionCount = paddedStr.length() / 4;

        StringBuilder result = new StringBuilder();
        boolean lastSectionHadValue = false;

        for (int i = 0; i < sectionCount; i++) {
            String section = paddedStr.substring(i * 4, i * 4 + 4);
            String sectionStr = convertSection(section);

            if (!sectionStr.isEmpty()) {
                if (lastSectionHadValue) {
                    // Add zero for empty sections between
                } else if (result.length() > 0 && i > 0) {
                    if (!result.toString().endsWith("零")) {
                        result.append("零");
                    }
                }
                result.append(sectionStr);
                result.append(CN_SECTIONS[sectionCount - 1 - i]);
                lastSectionHadValue = true;
            } else {
                lastSectionHadValue = false;
            }
        }

        return result.toString();
    }

    /** 转换一个四位数字节，并处理节内连续零。 */
    private String convertSection(String section) {
        StringBuilder result = new StringBuilder();
        boolean anyNonZero = false;
        boolean lastIsZero = false;

        for (int i = 0; i < 4; i++) {
            int digit = section.charAt(i) - '0';
            if (digit == 0) {
                if (anyNonZero && !lastIsZero) {
                    result.append("零");
                }
                lastIsZero = true;
            } else {
                result.append(CN_DIGITS[digit]);
                result.append(CN_POSITIONS[3 - i]);
                lastIsZero = false;
                anyNonZero = true;
            }
        }

        return result.toString();
    }
}
