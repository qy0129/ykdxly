package com.example.ilink.feature.finance;

import com.example.ilink.config.Config;
import com.example.ilink.tools.core.MoneyUtils;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 解析自然语言中的消费信息，并计算多人 AA 或不同付款金额的结算方案。 */
public final class ExpenseSplitService {

    private final HttpClient httpClient;
    private final Gson gson = new Gson();

    /** 创建费用分摊服务。 */
    public ExpenseSplitService(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /** 从用户原话中提取账单信息并返回可直接执行的结算结果。 */
    public String split(String userInput) {
        SplitRequest request = extractRequest(userInput);
        return request == null
                ? "没能理解分摊信息。请说明总金额、参与人和已付款金额，例如：我、张三、李四吃饭共300元，我付了300元。"
                : calculate(request);
    }

    /** 调用模型把自然语言稳定转换为账单结构，不让模型参与实际金额运算。 */
    private SplitRequest extractRequest(String userInput) {
        try {
            JsonObject body = new JsonObject();
            body.addProperty("model", Config.ROUTER_MODEL);
            body.addProperty("temperature", 0.1);
            body.addProperty("enable_thinking", false);

            JsonArray messages = new JsonArray();
            JsonObject system = new JsonObject();
            system.addProperty("role", "system");
            system.addProperty("content", "你只提取多人费用分摊参数，必须只输出 JSON。"
                    + "格式：{\"title\":\"聚餐\",\"total\":300,\"currency\":\"元\","
                    + "\"participants\":[{\"name\":\"我\",\"paid\":300},{\"name\":\"张三\",\"paid\":0}]}。"
                    + "total 是消费总额；participants 必须包含全部参与者。"
                    + "用户说 AA、平分且没有付款明细时，所有 paid 填 0。"
                    + "用户说谁付了多少钱时如实填写 paid；未付款者填 0。"
                    + "不要补造参与者、金额或付款记录。");
            messages.add(system);

            JsonObject user = new JsonObject();
            user.addProperty("role", "user");
            user.addProperty("content", userInput);
            messages.add(user);
            body.add("messages", messages);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(Config.API_BASE_URL))
                    .timeout(Config.REQ_TIMEOUT)
                    .header("Authorization", "Bearer " + Config.API_KEY)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                System.err.println("[AA分摊] 参数提取失败，HTTP " + response.statusCode());
                return null;
            }

            JsonObject message = JsonParser.parseString(response.body()).getAsJsonObject()
                    .getAsJsonArray("choices").get(0).getAsJsonObject()
                    .getAsJsonObject("message");
            return parseRequest(message.get("content").getAsString());
        } catch (Exception e) {
            System.err.println("[AA分摊] 参数提取失败：" + e.getMessage());
            return null;
        }
    }

    /** 解析模型返回的 JSON，并进行基本的金额和参与人校验。 */
    private SplitRequest parseRequest(String content) {
        try {
            String json = content.trim();
            if (json.startsWith("```")) {
                int firstLineEnd = json.indexOf('\n');
                int closingFence = json.lastIndexOf("```");
                if (firstLineEnd >= 0 && closingFence > firstLineEnd) {
                    json = json.substring(firstLineEnd + 1, closingFence).trim();
                }
            }

            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            BigDecimal total = decimal(root.get("total"));
            JsonArray array = root.getAsJsonArray("participants");
            if (array == null || array.isEmpty()) {
                return null;
            }

            List<Participant> participants = new ArrayList<>();
            for (JsonElement element : array) {
                JsonObject item = element.getAsJsonObject();
                String name = string(item, "name");
                if (name.isBlank()) {
                    return null;
                }
                participants.add(new Participant(name, decimal(item.get("paid"))));
            }
            return new SplitRequest(string(root, "title"), total,
                    defaultCurrency(string(root, "currency")), participants);
        } catch (Exception e) {
            return null;
        }
    }

    /** 使用按分取整的金额计算，生成每个人的应付和最终转账方案。 */
    private String calculate(SplitRequest request) {
        if (request.participants().size() < 2) {
            return "分摊至少需要两位参与者。";
        }

        BigDecimal total = MoneyUtils.round(request.total());
        if (total.compareTo(BigDecimal.ZERO) <= 0) {
            return "总金额必须大于 0。";
        }

        Set<String> names = new HashSet<>();
        BigDecimal paidTotal = BigDecimal.ZERO;
        for (Participant participant : request.participants()) {
            if (!names.add(participant.name())) {
                return "参与者名称不能重复：" + participant.name();
            }
            if (participant.paid().compareTo(BigDecimal.ZERO) < 0) {
                return participant.name() + "的已付款金额不能为负数。";
            }
            paidTotal = paidTotal.add(participant.paid());
        }
        paidTotal = MoneyUtils.round(paidTotal);

        boolean pureAa = paidTotal.compareTo(BigDecimal.ZERO) == 0;
        if (!pureAa && paidTotal.compareTo(total) != 0) {
            return "已付款合计为 " + money(paidTotal) + request.currency() + "，与总金额 "
                    + money(total) + request.currency() + " 不一致。请补充或核对付款记录后再结算。";
        }

        List<Balance> balances = createBalances(request.participants(), total);
        StringBuilder reply = new StringBuilder();
        reply.append("【").append(request.title().isBlank() ? "费用" : request.title()).append("分摊】\n")
                .append("总金额：").append(money(total)).append(request.currency()).append("\n")
                .append("参与人数：").append(request.participants().size()).append("人\n")
                .append("每人应付：\n");
        for (Balance balance : balances) {
            reply.append("- ").append(balance.name()).append("：")
                    .append(money(balance.shouldPay())).append(request.currency()).append("\n");
        }

        if (pureAa) {
            return reply.append("\n当前未记录任何人付款，可按上述金额分别收款。").toString();
        }

        reply.append("\n付款与结算：\n");
        for (Balance balance : balances) {
            String status = balance.amount().compareTo(BigDecimal.ZERO) > 0
                    ? "应收回 " + money(balance.amount()) + request.currency()
                    : balance.amount().compareTo(BigDecimal.ZERO) < 0
                    ? "应补交 " + money(balance.amount().negate()) + request.currency()
                    : "刚好付清";
            reply.append("- ").append(balance.name()).append("已付 ")
                    .append(money(balance.paid())).append(request.currency()).append("，")
                    .append(status).append("\n");
        }

        List<String> transfers = settle(balances, request.currency());
        if (transfers.isEmpty()) {
            reply.append("\n全部已结清。");
        } else {
            reply.append("\n建议转账：\n");
            transfers.forEach(transfer -> reply.append("- ").append(transfer).append("\n"));
        }
        return reply.toString();
    }

    /** 将总金额均分到分，无法整除的分按参与者顺序补齐。 */
    private List<Balance> createBalances(List<Participant> participants, BigDecimal total) {
        BigDecimal base = total.divide(BigDecimal.valueOf(participants.size()), 2, java.math.RoundingMode.DOWN);
        BigDecimal remainder = total.subtract(base.multiply(BigDecimal.valueOf(participants.size())));
        List<Balance> balances = new ArrayList<>();
        for (Participant participant : participants) {
            BigDecimal share = base;
            if (remainder.compareTo(BigDecimal.ZERO) > 0) {
                share = share.add(MoneyUtils.CENT);
                remainder = remainder.subtract(MoneyUtils.CENT);
            }
            BigDecimal paid = MoneyUtils.round(participant.paid());
            balances.add(new Balance(participant.name(), paid, share, paid.subtract(share)));
        }
        return balances;
    }

    /** 让欠款者依次向多付款者转账，直到所有差额归零。 */
    private List<String> settle(List<Balance> balances, String currency) {
        List<Balance> debtors = balances.stream().filter(balance -> balance.amount().compareTo(BigDecimal.ZERO) < 0).toList();
        List<Balance> creditors = balances.stream().filter(balance -> balance.amount().compareTo(BigDecimal.ZERO) > 0).toList();
        List<String> transfers = new ArrayList<>();
        int debtorIndex = 0;
        int creditorIndex = 0;
        while (debtorIndex < debtors.size() && creditorIndex < creditors.size()) {
            Balance debtor = debtors.get(debtorIndex);
            Balance creditor = creditors.get(creditorIndex);
            BigDecimal amount = debtor.amount().negate().min(creditor.amount());
            transfers.add(debtor.name() + " 转给 " + creditor.name() + " " + money(amount) + currency);
            debtor.setAmount(debtor.amount().add(amount));
            creditor.setAmount(creditor.amount().subtract(amount));
            if (debtor.amount().compareTo(BigDecimal.ZERO) == 0) debtorIndex++;
            if (creditor.amount().compareTo(BigDecimal.ZERO) == 0) creditorIndex++;
        }
        return transfers;
    }

    /** 将金额按两位小数输出。 */
    private String money(BigDecimal value) {
        return MoneyUtils.format(value);
    }

    /** 读取可选字符串字段。 */
    private String string(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value == null || value.isJsonNull() ? "" : value.getAsString().trim();
    }

    /** 将 JSON 数字或数字字符串转换为金额。 */
    private BigDecimal decimal(JsonElement value) {
        return value == null || value.isJsonNull() ? BigDecimal.ZERO : value.getAsBigDecimal();
    }

    /** 默认使用人民币元作为币种。 */
    private String defaultCurrency(String currency) {
        return currency.isBlank() ? "元" : currency;
    }

    private record SplitRequest(String title, BigDecimal total, String currency, List<Participant> participants) {
    }

    private record Participant(String name, BigDecimal paid) {
    }

    private static final class Balance {
        private final String name;
        private final BigDecimal paid;
        private final BigDecimal shouldPay;
        private BigDecimal amount;

        private Balance(String name, BigDecimal paid, BigDecimal shouldPay, BigDecimal amount) {
            this.name = name;
            this.paid = paid;
            this.shouldPay = shouldPay;
            this.amount = amount;
        }

        private String name() { return name; }
        private BigDecimal paid() { return paid; }
        private BigDecimal shouldPay() { return shouldPay; }
        private BigDecimal amount() { return amount; }
        private void setAmount(BigDecimal amount) { this.amount = amount; }
    }
}
