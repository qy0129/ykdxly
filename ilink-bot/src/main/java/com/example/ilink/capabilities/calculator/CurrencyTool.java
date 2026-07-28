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

/** 使用内置参考汇率完成常见币种之间的换算。 */
public final class CurrencyTool implements Tool {

    public static final String NAME = "currency_convert";

    private static final class CurrencyInfo {
        final String code;
        final String chineseName;
        final BigDecimal rateToCny;

        CurrencyInfo(String code, String chineseName, String rateToCny) {
            this.code = code;
            this.chineseName = chineseName;
            this.rateToCny = DecimalUtils.of(rateToCny);
        }
    }

    private static final LinkedHashMap<String, CurrencyInfo> CURRENCIES = new LinkedHashMap<>();

    static {
        CURRENCIES.put("CNY", new CurrencyInfo("CNY", "人民币", "1"));
        CURRENCIES.put("USD", new CurrencyInfo("USD", "美元", "7.24"));
        CURRENCIES.put("EUR", new CurrencyInfo("EUR", "欧元", "7.85"));
        CURRENCIES.put("JPY", new CurrencyInfo("JPY", "日元", "0.048"));
        CURRENCIES.put("HKD", new CurrencyInfo("HKD", "港币", "0.93"));
        CURRENCIES.put("GBP", new CurrencyInfo("GBP", "英镑", "9.15"));
        CURRENCIES.put("AUD", new CurrencyInfo("AUD", "澳元", "4.70"));
        CURRENCIES.put("CAD", new CurrencyInfo("CAD", "加元", "5.25"));
        CURRENCIES.put("CHF", new CurrencyInfo("CHF", "瑞士法郎", "8.05"));
        CURRENCIES.put("SGD", new CurrencyInfo("SGD", "新加坡元", "5.35"));
        CURRENCIES.put("SEK", new CurrencyInfo("SEK", "瑞典克朗", "0.67"));
        CURRENCIES.put("KRW", new CurrencyInfo("KRW", "韩元", "0.0052"));
        CURRENCIES.put("NOK", new CurrencyInfo("NOK", "挪威克朗", "0.66"));
        CURRENCIES.put("NZD", new CurrencyInfo("NZD", "新西兰元", "4.30"));
        CURRENCIES.put("INR", new CurrencyInfo("INR", "印度卢比", "0.086"));
        CURRENCIES.put("MXN", new CurrencyInfo("MXN", "墨西哥比索", "0.39"));
        CURRENCIES.put("TWD", new CurrencyInfo("TWD", "新台币", "0.22"));
        CURRENCIES.put("ZAR", new CurrencyInfo("ZAR", "南非兰特", "0.39"));
        CURRENCIES.put("BRL", new CurrencyInfo("BRL", "巴西雷亚尔", "1.31"));
        CURRENCIES.put("DKK", new CurrencyInfo("DKK", "丹麦克朗", "1.05"));
        CURRENCIES.put("PLN", new CurrencyInfo("PLN", "波兰兹罗提", "1.79"));
        CURRENCIES.put("THB", new CurrencyInfo("THB", "泰铢", "0.20"));
        CURRENCIES.put("ILS", new CurrencyInfo("ILS", "以色列新谢克尔", "1.95"));
        CURRENCIES.put("MYR", new CurrencyInfo("MYR", "马来西亚林吉特", "1.55"));
        CURRENCIES.put("PHP", new CurrencyInfo("PHP", "菲律宾比索", "0.13"));
        CURRENCIES.put("IDR", new CurrencyInfo("IDR", "印尼盾", "0.00045"));
        CURRENCIES.put("CZK", new CurrencyInfo("CZK", "捷克克朗", "0.31"));
        CURRENCIES.put("AED", new CurrencyInfo("AED", "阿联酋迪拉姆", "1.97"));
        CURRENCIES.put("TRY", new CurrencyInfo("TRY", "土耳其里拉", "0.21"));
        CURRENCIES.put("HUF", new CurrencyInfo("HUF", "匈牙利福林", "0.02"));
        for (Map.Entry<String, CurrencyInfo> e : new LinkedHashMap<>(CURRENCIES).entrySet()) {
            CurrencyInfo ci = e.getValue();
            CURRENCIES.put(ci.chineseName, new CurrencyInfo(ci.code, ci.chineseName, ci.rateToCny.toPlainString()));
        }
    }

    private final ToolDefinition definition;

    /** 创建货币换算工具并声明 Function Calling 参数。 */
    public CurrencyTool() {
        JsonObject properties = new JsonObject();
        properties.add("amount", ToolDefinition.stringProperty("待转换的金额数值，如 100"));
        properties.add("from", ToolDefinition.stringProperty("源货币，支持代码(USD/CNY)或中文(美元/人民币)"));
        properties.add("to", ToolDefinition.stringProperty("目标货币，支持代码(USD/CNY)或中文(美元/人民币)"));
        this.definition = new ToolDefinition(
                NAME, "货币换算",
                "使用内置参考汇率进行货币换算，支持30种主要货币，包括人民币、美元、欧元、日元、港币、英镑、澳元等",
                ToolDefinition.objectParameters(properties, "amount", "from", "to"), true);
    }

    @Override
    /** 返回货币换算工具的标准定义。 */
    public ToolDefinition definition() { return definition; }

    @Override
    /** 将输入金额先折算为人民币，再转换为目标币种。 */
    public ToolResult execute(ToolContext context, JsonObject arguments) {
        String amountStr = ToolArguments.requireString(arguments, "amount");
        String fromKey = ToolArguments.requireString(arguments, "from");
        String toKey = ToolArguments.requireString(arguments, "to");

        CurrencyInfo fromCurrency = resolveCode(fromKey);
        CurrencyInfo toCurrency = resolveCode(toKey);
        if (fromCurrency == null || toCurrency == null) {
            return ToolResult.failure("不支持的货币");
        }

        BigDecimal amount = DecimalUtils.of(amountStr);
        BigDecimal cnyAmount = DecimalUtils.mul(amount, fromCurrency.rateToCny);
        BigDecimal result = DecimalUtils.div(cnyAmount, toCurrency.rateToCny);

        return ToolResult.success("━━━━ 货币换算 ━━━━\n"
                + DecimalUtils.fmt(amount) + " " + fromCurrency.chineseName + "(" + fromCurrency.code + ")"
                + " = " + DecimalUtils.fmt(result) + " " + toCurrency.chineseName + "(" + toCurrency.code + ")"
                + "\n━━━━━━━━━━━━━━");
    }

    /** 从币种代码、中文名或多个候选别名中查找币种。 */
    private CurrencyInfo resolveCode(String key) {
        return UnitAliasResolver.find(CURRENCIES, key);
    }
}
