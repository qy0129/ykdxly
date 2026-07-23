package com.example.ilink.tools.calculator;

import com.example.ilink.tools.core.Tool;
import com.example.ilink.tools.core.ToolArguments;
import com.example.ilink.tools.core.ToolContext;
import com.example.ilink.tools.core.ToolDefinition;
import com.example.ilink.tools.core.ToolResult;
import com.example.ilink.tools.core.MoneyUtils;
import com.google.gson.JsonObject;

import java.math.BigDecimal;
import java.util.LinkedHashMap;

/** 按内置税率计算工资薪金或全年一次性奖金个人所得税。 */
public final class TaxTool implements Tool {

    public static final String NAME = "tax_calc";

    private static final BigDecimal[] BRACKET_LIMITS = {
        DecimalUtils.of("36000"),
        DecimalUtils.of("144000"),
        DecimalUtils.of("300000"),
        DecimalUtils.of("420000"),
        DecimalUtils.of("660000"),
        DecimalUtils.of("960000")
    };

    private static final BigDecimal[] RATES = {
        DecimalUtils.of("0.03"),
        DecimalUtils.of("0.10"),
        DecimalUtils.of("0.20"),
        DecimalUtils.of("0.25"),
        DecimalUtils.of("0.30"),
        DecimalUtils.of("0.35"),
        DecimalUtils.of("0.45")
    };

    private static final BigDecimal[] QUICK_DEDUCTIONS = {
        DecimalUtils.of("0"),
        DecimalUtils.of("2520"),
        DecimalUtils.of("16920"),
        DecimalUtils.of("31920"),
        DecimalUtils.of("52920"),
        DecimalUtils.of("85920"),
        DecimalUtils.of("181920")
    };

    private static final BigDecimal SOCIAL_RATE = DecimalUtils.of("0.107");
    private static final BigDecimal HOUSING_RATE = DecimalUtils.of("0.12");
    private static final BigDecimal THRESHOLD = DecimalUtils.of("5000");

    private static final LinkedHashMap<String, BigDecimal> CITY_LIMITS = new LinkedHashMap<>();

    static {
        CITY_LIMITS.put("北京", DecimalUtils.of("35000"));
        CITY_LIMITS.put("上海", DecimalUtils.of("35000"));
        CITY_LIMITS.put("深圳", DecimalUtils.of("35000"));
        CITY_LIMITS.put("广州", DecimalUtils.of("30000"));
    }

    private static final BigDecimal DEFAULT_LIMIT = DecimalUtils.of("25000");

    private final ToolDefinition definition;

    /** 创建个税计算工具并声明 Function Calling 参数。 */
    public TaxTool() {
        JsonObject properties = new JsonObject();
        properties.add("type", ToolDefinition.enumStringProperty("薪资类型", "月薪", "年终奖"));
        properties.add("salary", ToolDefinition.stringProperty("月薪或年终奖金额，如 30000"));
        properties.add("city", ToolDefinition.stringProperty("所在城市，可选，影响社保基数上限。支持：北京、上海、深圳、广州等"));
        properties.add("special_deduction", ToolDefinition.stringProperty("每月专项附加扣除金额，可选，如 2000"));
        this.definition = new ToolDefinition(
                NAME, "个税计算",
                "个人所得税计算器，支持月薪和年终奖两种模式，自动计算社保、公积金、专项附加扣除、个税和税后收入",
                ToolDefinition.objectParameters(properties, "type", "salary"), true);
    }

    @Override
    /** 返回个税计算工具的标准定义。 */
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    /** 根据 type 参数分发至月薪或年终奖计算分支。 */
    public ToolResult execute(ToolContext context, JsonObject arguments) {
        String type = ToolArguments.requireString(arguments, "type");
        String salaryStr = ToolArguments.requireString(arguments, "salary");
        String city = ToolArguments.string(arguments, "city", "");
        String specialStr = ToolArguments.string(arguments, "special_deduction", "0");

        BigDecimal salary = DecimalUtils.of(salaryStr);
        BigDecimal specialDeduction = DecimalUtils.of(specialStr);

        if (salary.compareTo(BigDecimal.ZERO) <= 0) {
            return ToolResult.failure("薪资必须大于0");
        }

        BigDecimal cityLimit = CITY_LIMITS.getOrDefault(city, DEFAULT_LIMIT);
        BigDecimal socialBase = DecimalUtils.min(salary, cityLimit);

        StringBuilder sb = new StringBuilder();
        sb.append("━━━━ 个税计算 ━━━━\n");

        if ("月薪".equals(type)) {
            return calcMonthlySalary(sb, salary, socialBase, specialDeduction);
        } else if ("年终奖".equals(type)) {
            return calcYearEndBonus(sb, salary);
        } else {
            return ToolResult.failure("不支持的薪资类型，请选择「月薪」或「年终奖」");
        }
    }

    /** 按累计预扣法计算当月工资需要缴纳的个人所得税。 */
    private ToolResult calcMonthlySalary(StringBuilder sb, BigDecimal salary, BigDecimal socialBase, BigDecimal specialDeduction) {
        BigDecimal social = DecimalUtils.mul(socialBase, SOCIAL_RATE);
        BigDecimal housing = DecimalUtils.mul(socialBase, HOUSING_RATE);
        BigDecimal totalDeductions = DecimalUtils.add(DecimalUtils.add(DecimalUtils.add(social, housing), specialDeduction), THRESHOLD);
        BigDecimal taxableMonthly = DecimalUtils.sub(salary, totalDeductions);

        if (taxableMonthly.compareTo(BigDecimal.ZERO) <= 0) {
            BigDecimal afterTax = DecimalUtils.sub(DecimalUtils.sub(salary, social), housing);
            sb.append("薪资: ").append(fmtMoney(salary)).append("\n");
            sb.append("社保: ").append(fmtMoney(social)).append(" (基数 ").append(fmtMoney(socialBase)).append(")\n");
            sb.append("公积金: ").append(fmtMoney(housing)).append(" (基数 ").append(fmtMoney(socialBase)).append(")\n");
            sb.append("专项扣除: ").append(fmtMoney(specialDeduction)).append("\n");
            sb.append("免征额: ").append(fmtMoney(THRESHOLD)).append("\n");
            sb.append("应纳税所得额(月): 0.00\n");
            sb.append("应纳税所得额(年): 0.00\n");
            sb.append("税率: 0%\n");
            sb.append("速算扣除数: 0\n");
            sb.append("月应纳税额: 0.00\n");
            sb.append("税后月薪: ").append(fmtMoney(afterTax)).append("\n");
            sb.append("━━━━━━━━━━━━━━");
            return ToolResult.success(sb.toString());
        }

        BigDecimal taxableAnnual = DecimalUtils.mul(taxableMonthly, DecimalUtils.of("12"));
        int bracket = findBracket(taxableAnnual);
        BigDecimal rate = RATES[bracket];
        BigDecimal quickDeduction = QUICK_DEDUCTIONS[bracket];
        BigDecimal annualTax = DecimalUtils.sub(DecimalUtils.mul(taxableAnnual, rate), quickDeduction);
        if (annualTax.compareTo(BigDecimal.ZERO) < 0) annualTax = BigDecimal.ZERO;
        BigDecimal monthlyTax = DecimalUtils.div(annualTax, DecimalUtils.of("12"));
        BigDecimal afterTax = DecimalUtils.sub(DecimalUtils.sub(DecimalUtils.sub(salary, social), housing), monthlyTax);

        sb.append("薪资: ").append(fmtMoney(salary)).append("\n");
        sb.append("社保: ").append(fmtMoney(social)).append(" (基数 ").append(fmtMoney(socialBase)).append(")\n");
        sb.append("公积金: ").append(fmtMoney(housing)).append(" (基数 ").append(fmtMoney(socialBase)).append(")\n");
        sb.append("专项扣除: ").append(fmtMoney(specialDeduction)).append("\n");
        sb.append("免征额: ").append(fmtMoney(THRESHOLD)).append("\n");
        sb.append("应纳税所得额(月): ").append(fmtMoney(taxableMonthly)).append("\n");
        sb.append("应纳税所得额(年): ").append(fmtMoney(taxableAnnual)).append("\n");
        sb.append("税率: ").append(DecimalUtils.fmt(DecimalUtils.mul(rate, DecimalUtils.of("100")))).append("%\n");
        sb.append("速算扣除数: ").append(fmtMoney(quickDeduction)).append("\n");
        sb.append("月应纳税额: ").append(fmtMoney(monthlyTax)).append("\n");
        sb.append("税后月薪: ").append(fmtMoney(afterTax)).append("\n");
        sb.append("━━━━━━━━━━━━━━");
        return ToolResult.success(sb.toString());
    }

    /** 按全年一次性奖金单独计税规则计算应纳税额。 */
    private ToolResult calcYearEndBonus(StringBuilder sb, BigDecimal bonus) {
        BigDecimal monthlyEquiv = DecimalUtils.div(bonus, DecimalUtils.of("12"));
        int bracket = findBracket(monthlyEquiv);
        BigDecimal rate = RATES[bracket];
        BigDecimal quickDeduction = QUICK_DEDUCTIONS[bracket];
        BigDecimal tax = DecimalUtils.sub(DecimalUtils.mul(bonus, rate), quickDeduction);
        if (tax.compareTo(BigDecimal.ZERO) < 0) tax = BigDecimal.ZERO;
        BigDecimal afterTax = DecimalUtils.sub(bonus, tax);

        sb.append("年终奖: ").append(fmtMoney(bonus)).append("\n");
        sb.append("月均: ").append(fmtMoney(monthlyEquiv)).append("\n");
        sb.append("税率: ").append(DecimalUtils.fmt(DecimalUtils.mul(rate, DecimalUtils.of("100")))).append("%\n");
        sb.append("速算扣除数: ").append(fmtMoney(quickDeduction)).append("\n");
        sb.append("应纳税额: ").append(fmtMoney(tax)).append("\n");
        sb.append("税后奖金: ").append(fmtMoney(afterTax)).append("\n");
        sb.append("━━━━━━━━━━━━━━");
        return ToolResult.success(sb.toString());
    }

    /** 返回应纳税所得额对应的税率档位下标。 */
    private int findBracket(BigDecimal value) {
        for (int i = 0; i < BRACKET_LIMITS.length; i++) {
            if (value.compareTo(BRACKET_LIMITS[i]) <= 0) {
                return i;
            }
        }
        return BRACKET_LIMITS.length;
    }

    /** 将金额按公共两位小数规则输出。 */
    private static String fmtMoney(BigDecimal value) {
        return MoneyUtils.format(value);
    }
}
