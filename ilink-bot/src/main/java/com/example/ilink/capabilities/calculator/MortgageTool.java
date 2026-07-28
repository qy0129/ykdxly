package com.example.ilink.capabilities.calculator;

import com.example.ilink.application.tooling.Tool;
import com.example.ilink.application.tooling.ToolArguments;
import com.example.ilink.application.tooling.ToolContext;
import com.example.ilink.application.tooling.ToolDefinition;
import com.example.ilink.application.tooling.ToolResult;
import com.example.ilink.application.tooling.MoneyUtils;
import com.google.gson.JsonObject;

import java.math.BigDecimal;
import java.math.MathContext;

/** 计算商业贷款、公积金贷款和组合贷款的还款信息。 */
public final class MortgageTool implements Tool {

    public static final String NAME = "mortgage_calc";

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal ONE_HUNDRED = DecimalUtils.of("100");

    private final ToolDefinition definition;

    /** 创建房贷计算工具并声明 Function Calling 参数。 */
    public MortgageTool() {
        JsonObject properties = new JsonObject();
        properties.add("type", ToolDefinition.enumStringProperty("贷款类型", "商业", "公积金", "组合"));
        properties.add("amount", ToolDefinition.stringProperty("贷款总额（元），如 1000000"));
        properties.add("years", ToolDefinition.stringProperty("贷款年限，如 30"));
        properties.add("rate", ToolDefinition.stringProperty("商业贷款利率（百分比），如 3.85"));
        properties.add("fund_amount", ToolDefinition.stringProperty("公积金贷款金额（组合贷必填），如 600000"));
        properties.add("fund_rate", ToolDefinition.stringProperty("公积金贷款利率（百分比），组合贷默认 3.1"));
        this.definition = new ToolDefinition(
                NAME, "房贷计算",
                "房贷计算器，支持商业贷款、公积金贷款和组合贷款，同时计算等额本息和等额本金两种还款方式",
                ToolDefinition.objectParameters(properties, "type", "amount", "years", "rate"), true);
    }

    @Override
    /** 返回房贷计算工具的标准定义。 */
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    /** 读取贷款类型、金额、年限和利率，输出两种还款方式的结果。 */
    public ToolResult execute(ToolContext context, JsonObject arguments) {
        String type = ToolArguments.requireString(arguments, "type");
        String amountStr = ToolArguments.requireString(arguments, "amount");
        String yearsStr = ToolArguments.requireString(arguments, "years");
        String rateStr = ToolArguments.requireString(arguments, "rate");

        BigDecimal amount = DecimalUtils.of(amountStr);
        int years = Integer.parseInt(yearsStr);
        BigDecimal annualRate = DecimalUtils.div(DecimalUtils.of(rateStr), ONE_HUNDRED);

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            return ToolResult.failure("贷款金额必须大于0");
        }
        if (years <= 0) {
            return ToolResult.failure("贷款年限必须大于0");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("━━━━ 房贷计算 ━━━━\n");

        switch (type) {
            case "商业":
                calcSingleLoan(sb, "商业贷款", amount, years, annualRate);
                break;
            case "公积金":
                calcSingleLoan(sb, "公积金贷款", amount, years, annualRate);
                break;
            case "组合":
                String fundAmountStr = ToolArguments.requireString(arguments, "fund_amount");
                String fundRateStr = ToolArguments.string(arguments, "fund_rate", "3.1");
                BigDecimal fundAmount = DecimalUtils.of(fundAmountStr);
                BigDecimal fundAnnualRate = DecimalUtils.div(DecimalUtils.of(fundRateStr), ONE_HUNDRED);
                BigDecimal bizAmount = DecimalUtils.sub(amount, fundAmount);

                if (bizAmount.compareTo(BigDecimal.ZERO) > 0) {
                    calcSingleLoan(sb, "商业贷款", bizAmount, years, annualRate);
                    sb.append("\n");
                }
                if (fundAmount.compareTo(BigDecimal.ZERO) > 0) {
                    calcSingleLoan(sb, "公积金贷款", fundAmount, years, fundAnnualRate);
                }

                if (bizAmount.compareTo(BigDecimal.ZERO) > 0 && fundAmount.compareTo(BigDecimal.ZERO) > 0) {
                    sb.append("\n");
                    sb.append("━━━ 组合贷合计 ━━━\n");
                    BizFundTotals totals = calcCombinedTotals(bizAmount, fundAmount, years, annualRate, fundAnnualRate);
                    sb.append(totals.equalPayment());
                    sb.append(totals.equalPrincipal());
                }
                break;
            default:
                return ToolResult.failure("不支持的贷款类型，请选择「商业」「公积金」或「组合」");
        }

        sb.append("━━━━━━━━━━━━━━");
        return ToolResult.success(sb.toString());
    }

    /** 计算并输出一笔贷款的等额本息和等额本金结果。 */
    private void calcSingleLoan(StringBuilder sb, String label, BigDecimal amount, int years, BigDecimal annualRate) {
        int months = years * 12;
        BigDecimal monthlyRate = DecimalUtils.div(annualRate, DecimalUtils.of("12"));

        sb.append("━━━ ").append(label).append(" ━━━\n");
        sb.append("贷款金额: ").append(fmtMoney(amount)).append("\n");
        sb.append("贷款年限: ").append(years).append("年\n");
        sb.append("年利率: ").append(DecimalUtils.fmt(DecimalUtils.mul(annualRate, ONE_HUNDRED))).append("%\n\n");

        calcEqualPayment(sb, amount, months, monthlyRate);
        sb.append("\n");
        calcEqualPrincipal(sb, amount, months, monthlyRate);
    }

    /** 按等额本息公式计算每月固定还款额和利息总额。 */
    private void calcEqualPayment(StringBuilder sb, BigDecimal amount, int months, BigDecimal monthlyRate) {
        BigDecimal one = DecimalUtils.of("1");
        BigDecimal monthlyPayment;

        if (monthlyRate.compareTo(BigDecimal.ZERO) == 0) {
            monthlyPayment = DecimalUtils.div(amount, DecimalUtils.of(String.valueOf(months)));
        } else {
            BigDecimal onePlusR = DecimalUtils.add(one, monthlyRate);
            BigDecimal factor = onePlusR.pow(months, MC);
            BigDecimal numerator = DecimalUtils.mul(amount, DecimalUtils.mul(monthlyRate, factor));
            BigDecimal denominator = DecimalUtils.sub(factor, one);
            monthlyPayment = DecimalUtils.div(numerator, denominator);
        }

        BigDecimal totalPayment = DecimalUtils.mul(monthlyPayment, DecimalUtils.of(String.valueOf(months)));
        BigDecimal totalInterest = DecimalUtils.sub(totalPayment, amount);

        sb.append("【等额本息】\n");
        sb.append("月供: ").append(fmtMoney(monthlyPayment)).append("\n");
        sb.append("总利息: ").append(fmtMoney(totalInterest)).append("\n");
        sb.append("还款总额: ").append(fmtMoney(totalPayment)).append("\n");
    }

    /** 按等额本金公式计算首月、末月还款和利息总额。 */
    private void calcEqualPrincipal(StringBuilder sb, BigDecimal amount, int months, BigDecimal monthlyRate) {
        BigDecimal monthlyPrincipal = DecimalUtils.div(amount, DecimalUtils.of(String.valueOf(months)));
        BigDecimal totalInterest = BigDecimal.ZERO;
        BigDecimal firstPayment = BigDecimal.ZERO;
        BigDecimal lastPayment = BigDecimal.ZERO;

        for (int i = 1; i <= months; i++) {
            BigDecimal remaining = DecimalUtils.sub(amount, DecimalUtils.mul(monthlyPrincipal, DecimalUtils.of(String.valueOf(i - 1))));
            BigDecimal interest = DecimalUtils.mul(remaining, monthlyRate);
            BigDecimal payment = DecimalUtils.add(monthlyPrincipal, interest);
            totalInterest = DecimalUtils.add(totalInterest, interest);
            if (i == 1) firstPayment = payment;
            if (i == months) lastPayment = payment;
        }

        BigDecimal totalPayment = DecimalUtils.add(amount, totalInterest);

        sb.append("【等额本金】\n");
        sb.append("月供本金: ").append(fmtMoney(monthlyPrincipal)).append("\n");
        sb.append("首月月供: ").append(fmtMoney(firstPayment)).append("\n");
        sb.append("末月月供: ").append(fmtMoney(lastPayment)).append("\n");
        sb.append("总利息: ").append(fmtMoney(totalInterest)).append("\n");
        sb.append("还款总额: ").append(fmtMoney(totalPayment)).append("\n");
    }

    /** 汇总组合贷款中商业和公积金两部分的两种还款方式。 */
    private BizFundTotals calcCombinedTotals(BigDecimal bizAmount, BigDecimal fundAmount, int years,
                                              BigDecimal bizRate, BigDecimal fundRate) {
        int months = years * 12;

        BigDecimal bizMonthlyRate = DecimalUtils.div(bizRate, DecimalUtils.of("12"));
        BigDecimal fundMonthlyRate = DecimalUtils.div(fundRate, DecimalUtils.of("12"));
        BigDecimal one = DecimalUtils.of("1");

        // Equal payment combined
        BigDecimal bizMonthlyPayment;
        BigDecimal fundMonthlyPayment;
        if (bizMonthlyRate.compareTo(BigDecimal.ZERO) == 0) {
            bizMonthlyPayment = DecimalUtils.div(bizAmount, DecimalUtils.of(String.valueOf(months)));
        } else {
            BigDecimal onePlusR = DecimalUtils.add(one, bizMonthlyRate);
            BigDecimal factor = onePlusR.pow(months, MC);
            bizMonthlyPayment = DecimalUtils.div(
                DecimalUtils.mul(bizAmount, DecimalUtils.mul(bizMonthlyRate, factor)),
                DecimalUtils.sub(factor, one));
        }
        if (fundMonthlyRate.compareTo(BigDecimal.ZERO) == 0) {
            fundMonthlyPayment = DecimalUtils.div(fundAmount, DecimalUtils.of(String.valueOf(months)));
        } else {
            BigDecimal onePlusR = DecimalUtils.add(one, fundMonthlyRate);
            BigDecimal factor = onePlusR.pow(months, MC);
            fundMonthlyPayment = DecimalUtils.div(
                DecimalUtils.mul(fundAmount, DecimalUtils.mul(fundMonthlyRate, factor)),
                DecimalUtils.sub(factor, one));
        }
        BigDecimal combinedMonthlyPayment = DecimalUtils.add(bizMonthlyPayment, fundMonthlyPayment);
        BigDecimal combinedTotalPayment = DecimalUtils.mul(combinedMonthlyPayment, DecimalUtils.of(String.valueOf(months)));
        BigDecimal combinedTotalInterest = DecimalUtils.sub(combinedTotalPayment, DecimalUtils.add(bizAmount, fundAmount));

        // Equal principal combined
        BigDecimal bizMonthlyPrincipal = DecimalUtils.div(bizAmount, DecimalUtils.of(String.valueOf(months)));
        BigDecimal fundMonthlyPrincipal = DecimalUtils.div(fundAmount, DecimalUtils.of(String.valueOf(months)));
        BigDecimal bizFirstInterest = DecimalUtils.mul(bizAmount, bizMonthlyRate);
        BigDecimal fundFirstInterest = DecimalUtils.mul(fundAmount, fundMonthlyRate);

        StringBuilder eqPay = new StringBuilder();
        eqPay.append("【等额本息】\n");
        eqPay.append("月供合计: ").append(fmtMoney(combinedMonthlyPayment)).append("\n");
        eqPay.append("总利息: ").append(fmtMoney(combinedTotalInterest)).append("\n");
        eqPay.append("还款总额: ").append(fmtMoney(combinedTotalPayment)).append("\n");

        // Equal principal for combined
        BigDecimal totalPrincipal = DecimalUtils.add(bizAmount, fundAmount);
        BigDecimal totalInterestEP = BigDecimal.ZERO;
        for (int i = 1; i <= months; i++) {
            BigDecimal bizRemaining = DecimalUtils.sub(bizAmount, DecimalUtils.mul(bizMonthlyPrincipal, DecimalUtils.of(String.valueOf(i - 1))));
            BigDecimal fundRemaining = DecimalUtils.sub(fundAmount, DecimalUtils.mul(fundMonthlyPrincipal, DecimalUtils.of(String.valueOf(i - 1))));
            BigDecimal bizInterest = DecimalUtils.mul(bizRemaining, bizMonthlyRate);
            BigDecimal fundInterest = DecimalUtils.mul(fundRemaining, fundMonthlyRate);
            totalInterestEP = DecimalUtils.add(totalInterestEP, DecimalUtils.add(bizInterest, fundInterest));
        }
        BigDecimal totalPaymentEP = DecimalUtils.add(totalPrincipal, totalInterestEP);
        BigDecimal combinedFirstPayment = DecimalUtils.add(DecimalUtils.add(bizMonthlyPrincipal, bizFirstInterest),
                                                          DecimalUtils.add(fundMonthlyPrincipal, fundFirstInterest));

        StringBuilder eqPri = new StringBuilder();
        eqPri.append("【等额本金】\n");
        eqPri.append("月供本金合计: ").append(fmtMoney(DecimalUtils.add(bizMonthlyPrincipal, fundMonthlyPrincipal))).append("\n");
        eqPri.append("首月月供: ").append(fmtMoney(combinedFirstPayment)).append("\n");
        eqPri.append("总利息: ").append(fmtMoney(totalInterestEP)).append("\n");
        eqPri.append("还款总额: ").append(fmtMoney(totalPaymentEP)).append("\n");

        return new BizFundTotals(eqPay.toString(), eqPri.toString());
    }

    /** 将金额按公共两位小数规则输出。 */
    private static String fmtMoney(BigDecimal value) {
        return MoneyUtils.format(value);
    }

    private record BizFundTotals(String equalPayment, String equalPrincipal) {}
}
