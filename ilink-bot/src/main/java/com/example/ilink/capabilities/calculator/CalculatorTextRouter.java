package com.example.ilink.capabilities.calculator;

import com.example.ilink.application.tooling.ToolContext;
import com.example.ilink.application.tooling.ToolManager;
import com.example.ilink.application.tooling.ToolResult;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CalculatorTextRouter {

    private static final Pattern CONV_PATTERN = Pattern.compile(
        "(\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)\\s*([^\\d\\s]+)\\s*(?:to|=|in)?\\s*([^\\d\\s]+)");

    private static final Map<String, String> COMMAND_TOOL_MAP = new LinkedHashMap<>();
    private static final Map<String, String> COMMAND_HELP = new LinkedHashMap<>();

    static {
        COMMAND_TOOL_MAP.put("长度", LengthTool.NAME);
        COMMAND_TOOL_MAP.put("重量", WeightTool.NAME);
        COMMAND_TOOL_MAP.put("温度", TemperatureTool.NAME);
        COMMAND_TOOL_MAP.put("时间", TimeTool.NAME);
        COMMAND_TOOL_MAP.put("面积", AreaTool.NAME);
        COMMAND_TOOL_MAP.put("体积", VolumeTool.NAME);
        COMMAND_TOOL_MAP.put("速度", SpeedTool.NAME);
        COMMAND_TOOL_MAP.put("汇率", CurrencyTool.NAME);
        COMMAND_TOOL_MAP.put("进制", BaseConversionTool.NAME);
        COMMAND_TOOL_MAP.put("bmi", BMITool.NAME);
        COMMAND_TOOL_MAP.put("个税", TaxTool.NAME);
        COMMAND_TOOL_MAP.put("房贷", MortgageTool.NAME);
        COMMAND_TOOL_MAP.put("大写", ChineseMoneyTool.NAME);
        COMMAND_TOOL_MAP.put("称呼", RelationTool.NAME);

        COMMAND_HELP.put("长度", "#长度 1m = cm");
        COMMAND_HELP.put("重量", "#重量 1kg = g");
        COMMAND_HELP.put("温度", "#温度 100c = f");
        COMMAND_HELP.put("时间", "#时间 1d = hr");
        COMMAND_HELP.put("面积", "#面积 1sqm = sqft");
        COMMAND_HELP.put("体积", "#体积 1cum = lit");
        COMMAND_HELP.put("速度", "#速度 1kmh = mph");
        COMMAND_HELP.put("汇率", "#汇率 100 usd = cny");
        COMMAND_HELP.put("进制", "#进制 2 101010\n#进制 16 ff to 10");
        COMMAND_HELP.put("bmi", "#bmi 身高cm 体重kg");
        COMMAND_HELP.put("个税", "#个税 月薪 25000\n#个税 年终奖 50000");
        COMMAND_HELP.put("房贷", "#房贷 商业 100万 30年 利率4.1%");
        COMMAND_HELP.put("大写", "#大写 12345.67");
        COMMAND_HELP.put("称呼", "#称呼 爸爸的哥哥");
    }

    private final ToolManager toolManager;

    /** 使用应用统一工具管理器，避免命令路由重复注册工具实例。 */
    public CalculatorTextRouter(ToolManager toolManager) {
        this.toolManager = toolManager;
    }

    /** 创建独立的计算命令路由，供单元测试或独立调用使用。 */
    public CalculatorTextRouter() {
        this(new ToolManager()
            .register(new LengthTool())
            .register(new WeightTool())
            .register(new TemperatureTool())
            .register(new TimeTool())
            .register(new AreaTool())
            .register(new VolumeTool())
            .register(new SpeedTool())
            .register(new CurrencyTool())
            .register(new BaseConversionTool())
            .register(new BMITool())
            .register(new TaxTool())
            .register(new MortgageTool())
            .register(new ChineseMoneyTool())
            .register(new RelationTool()));
    }

    public boolean isCalculatorCommand(String text) {
        if (text == null || text.isBlank()) return false;
        String trimmed = text.trim();
        return trimmed.startsWith("#") || trimmed.startsWith("＃");
    }

    public String handle(String text) {
        return handle("system", text);
    }

    /** 使用指定用户上下文处理计算命令。 */
    public String handle(String userId, String text) {
        String input = text.trim();
        if (input.startsWith("＃")) input = "#" + input.substring(1);
        if (!input.startsWith("#")) return null;

        String rest = input.substring(1).trim();
        if (rest.isEmpty() || "计算帮助".equals(rest) || "帮助".equals(rest)) {
            return buildHelpText();
        }

        int spaceIdx = rest.indexOf(' ');
        String cmdName;
        String args;

        if (spaceIdx > 0) {
            cmdName = rest.substring(0, spaceIdx).trim().toLowerCase();
            args = rest.substring(spaceIdx + 1).trim();
        } else {
            cmdName = rest.toLowerCase();
            args = "";
        }

        String toolName = resolveCommand(cmdName);
        if (toolName == null) {
            return "未知命令: " + cmdName + "，发送 #计算帮助 查看全部工具";
        }

        if (args.isEmpty() || "help".equalsIgnoreCase(args) || "帮助".equals(args)) {
            String chCmd = findChineseCommand(toolName);
            String example = COMMAND_HELP.getOrDefault(chCmd, toolName);
            return "━━━━ " + chCmd + " 使用帮助 ━━━━\n" + example + "\n━━━━━━━━━━━━━━━━━━";
        }

        ToolContext context = new ToolContext(userId);
        JsonObject jsonArgs = parseTextArgs(toolName, cmdName, args);
        if (jsonArgs == null) {
            return "格式错误，发送 #" + cmdName + " help 查看帮助";
        }

        ToolResult result = toolManager.execute(toolName, context, jsonArgs);
        return result.output();
    }

    private String resolveCommand(String cmdName) {
        if (cmdName.startsWith("#")) cmdName = cmdName.substring(1);
        String toolName = COMMAND_TOOL_MAP.get(cmdName);
        if (toolName != null) return toolName;
        for (Map.Entry<String, String> e : COMMAND_TOOL_MAP.entrySet()) {
            if (e.getKey().equalsIgnoreCase(cmdName)) return e.getValue();
        }
        return null;
    }

    private String findChineseCommand(String toolName) {
        for (Map.Entry<String, String> e : COMMAND_TOOL_MAP.entrySet()) {
            if (e.getValue().equals(toolName)) return e.getKey();
        }
        return toolName;
    }

    private JsonObject parseTextArgs(String toolName, String cmdName, String args) {
        switch (toolName) {
            case LengthTool.NAME:
            case WeightTool.NAME:
            case TemperatureTool.NAME:
            case TimeTool.NAME:
            case AreaTool.NAME:
            case VolumeTool.NAME:
            case SpeedTool.NAME:
                return parseConvArgs(args);
            case CurrencyTool.NAME:
                return parseCurrencyArgs(args);
            case BaseConversionTool.NAME:
                return parseBaseConvArgs(args);
            case BMITool.NAME:
                return parseBMIArgs(args);
            case TaxTool.NAME:
                return parseTaxArgs(args);
            case MortgageTool.NAME:
                return parseMortgageArgs(args);
            case ChineseMoneyTool.NAME:
                return parseSimpleArgs(args, "amount");
            case RelationTool.NAME:
                return parseSimpleArgs(args, "chain");
            default:
                return null;
        }
    }

    private JsonObject parseConvArgs(String text) {
        Matcher m = CONV_PATTERN.matcher(text.trim());
        if (!m.matches()) return null;
        JsonObject json = new JsonObject();
        json.addProperty("value", m.group(1));
        json.addProperty("from", m.group(2));
        json.addProperty("to", m.group(3));
        return json;
    }

    private JsonObject parseCurrencyArgs(String text) {
        String[] parts = text.trim().split("\\s+");
        if (parts.length < 2) return null;
        JsonObject json = new JsonObject();
        json.addProperty("amount", parts[0]);
        json.addProperty("from", parts[1]);
        json.addProperty("to", parts.length >= 3 ? parts[2] : "CNY");
        return json;
    }

    private JsonObject parseBaseConvArgs(String text) {
        String input = text.trim().toLowerCase().replace("to", " ").replace("->", " ").replace("→", " ");
        String[] parts = input.split("\\s+");
        if (parts.length < 2) return null;
        JsonObject json = new JsonObject();
        json.addProperty("value", parts[1]);
        json.addProperty("from_base", parts[0]);
        json.addProperty("to_base", parts.length >= 3 ? parts[2] : "10");
        return json;
    }

    private JsonObject parseBMIArgs(String text) {
        String input = text.trim().toLowerCase()
                .replace("身高", "").replace("体重", "")
                .replace("h=", "").replace("w=", "")
                .replace("cm", "").replace("kg", "").trim();
        String[] parts = input.split("\\s+");
        if (parts.length < 2) return null;
        JsonObject json = new JsonObject();
        json.addProperty("height_cm", parts[0]);
        json.addProperty("weight_kg", parts[1]);
        return json;
    }

    private JsonObject parseTaxArgs(String text) {
        String input = text.trim();
        boolean isBonus = input.startsWith("年终奖") || input.startsWith("奖金");
        JsonObject json = new JsonObject();
        if (isBonus) {
            json.addProperty("type", "年终奖");
            String rest = input.replace("年终奖", "").replace("奖金", "").trim();
            json.addProperty("salary", rest.split("\\s+")[0]);
        } else {
            json.addProperty("type", "月薪");
            String rest = input.replace("月薪", "").trim();
            String[] parts = rest.split("\\s+");
            if (parts.length < 1) return null;
            json.addProperty("salary", parts[0]);
            int idx = 1;
            if (idx < parts.length && isCity(parts[idx])) {
                json.addProperty("city", parts[idx]);
                idx++;
            }
            if (idx < parts.length && (parts[idx].equals("专项附加") || parts[idx].equals("专项"))) {
                idx++;
                if (idx < parts.length) json.addProperty("special_deduction", parts[idx]);
            }
        }
        return json;
    }

    private boolean isCity(String s) {
        return "北京".equals(s) || "上海".equals(s) || "深圳".equals(s) ||
               "广州".equals(s) || "成都".equals(s) || "杭州".equals(s) || "南京".equals(s);
    }

    private JsonObject parseMortgageArgs(String text) {
        String input = text.trim();
        JsonObject json = new JsonObject();
        if (input.startsWith("组合")) {
            json.addProperty("type", "组合");
            String rest = input.replace("组合", "").trim();
            String businessAmount = "", fundAmount = "", years = "", businessRate = "", fundRate = "";
            String[] parts = rest.split("\\s+");
            for (int i = 0; i < parts.length; i++) {
                String p = parts[i];
                if (p.contains("商业")) {
                    if (i + 1 < parts.length) businessAmount = parseAmountNum(parts[++i]);
                } else if (p.contains("公积金")) {
                    if (i + 1 < parts.length) fundAmount = parseAmountNum(parts[++i]);
                } else if (p.contains("年") && !p.contains("率")) {
                    years = p.replace("年", "");
                } else if (p.contains("利率") || p.contains("%")) {
                    String rate = p.replace("利率", "").replace("%", "");
                    if (businessRate.isEmpty()) businessRate = rate; else fundRate = rate;
                }
            }
            json.addProperty("amount", businessAmount);
            json.addProperty("years", years);
            json.addProperty("rate", businessRate);
            json.addProperty("fund_amount", fundAmount);
            json.addProperty("fund_rate", fundRate.isEmpty() ? businessRate : fundRate);
        } else if (input.startsWith("商业") || input.startsWith("商贷")) {
            json.addProperty("type", "商业");
            parseMortgageParts(json, input.replace("商业", "").replace("商贷", "").trim());
        } else if (input.startsWith("公积金")) {
            json.addProperty("type", "公积金");
            parseMortgageParts(json, input.replace("公积金贷款", "").replace("公积金", "").trim());
        } else {
            return null;
        }
        return json;
    }

    private void parseMortgageParts(JsonObject json, String rest) {
        String[] parts = rest.split("\\s+");
        for (String p : parts) {
            if (p.contains("万") || (p.matches("\\d+") && !p.contains("年") && !p.contains("率") && !p.contains("%"))) {
                json.addProperty("amount", parseAmountNum(p));
            } else if (p.contains("年") && !p.contains("率")) {
                json.addProperty("years", p.replace("年", ""));
            } else if (p.contains("利率") || p.contains("%")) {
                json.addProperty("rate", p.replace("利率", "").replace("%", ""));
            }
        }
        if (!json.has("amount")) json.addProperty("amount", "");
        if (!json.has("years")) json.addProperty("years", "");
        if (!json.has("rate")) json.addProperty("rate", "");
    }

    private String parseAmountNum(String s) {
        String clean = s.replace("万元", "万").replace("元", "").trim();
        if (clean.contains("万")) {
            try { return String.valueOf((long) (Double.parseDouble(clean.replace("万", "")) * 10000)); }
            catch (NumberFormatException e) { return clean; }
        }
        return clean;
    }

    private JsonObject parseSimpleArgs(String text, String paramName) {
        JsonObject json = new JsonObject();
        json.addProperty(paramName, text.trim());
        return json;
    }

    private String buildHelpText() {
        StringBuilder sb = new StringBuilder("━━━━ 计算工具帮助 ━━━━\n");
        for (Map.Entry<String, String> e : COMMAND_HELP.entrySet()) {
            sb.append("• ").append(e.getValue()).append("\n");
        }
        sb.append("━━━━━━━━━━━━━━━━━━");
        return sb.toString();
    }
}
