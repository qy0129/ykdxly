package com.example.ilink.tools.food;

import com.example.ilink.tools.core.Tool;
import com.example.ilink.tools.core.ToolArguments;
import com.example.ilink.tools.core.ToolContext;
import com.example.ilink.tools.core.ToolDefinition;
import com.example.ilink.tools.core.ToolResult;
import com.google.gson.JsonObject;


/**
 * 根据饮食目标和用户偏好生成可解释的外卖筛选条件与移动端平台入口。
 * 没有门店数据时不伪造店铺直达链接，避免发出无法打开或不对应的网页地址。
 */
public final class FoodDeliveryTool implements Tool {

    public static final String NAME = "food_delivery_recommendation";
    private final ToolDefinition definition;

    public FoodDeliveryTool() {
        JsonObject properties = new JsonObject();
        properties.add("goal", ToolDefinition.stringProperty("饮食目标，例如减脂、增肌、控糖或均衡饮食"));
        properties.add("preferences", ToolDefinition.stringProperty("用户口味、忌口、预算、餐食标准等完整描述"));
        definition = new ToolDefinition(NAME, "外卖推荐", "根据饮食目标和口味标准生成外卖筛选建议与手机端平台入口。",
                ToolDefinition.objectParameters(properties, "goal", "preferences"), true);
    }

    @Override
    public ToolDefinition definition() { return definition; }

    @Override
    public ToolResult execute(ToolContext context, JsonObject arguments) {
        String goal = ToolArguments.requireString(arguments, "goal");
        String preferences = ToolArguments.requireString(arguments, "preferences");
        Rule rule = ruleFor(goal, preferences);
        String keyword = rule.keyword();
        String output = "按你的条件为你筛选：\n"
                + "- 目标：" + goal + "\n"
                + "- 口味与标准：" + preferences + "\n"
                + "- 单餐建议：" + rule.calories() + "，" + rule.protein() + "\n"
                + "- 优先选择：" + rule.prefer() + "\n"
                + "- 尽量少选：" + rule.avoid() + "\n\n"
                + "推荐搜索词：" + keyword + "\n\n"
                + "手机端入口：\n"
                + "美团外卖：https://i.waimai.meituan.com/\n"
                + "饿了么：https://h5.ele.me/\n\n"
                + "打开后搜索上面的关键词，即可查看你当前位置可配送的店铺。"
                + "具体店铺直达链接会在接入门店数据后单独输出。";
        return ToolResult.success(output);
    }

    private Rule ruleFor(String goal, String preferences) {
        boolean spicy = preferences.contains("辣");
        boolean vegetarian = preferences.contains("素") || preferences.contains("不吃肉");
        String flavor = spicy ? "微辣" : "少油少盐";
        if (vegetarian) return new Rule("450 至 600 千卡", "蛋白质不少于 20 克", "豆腐、鸡蛋、菌菇、全谷物和蔬菜", "油炸素菜和高糖饮料", flavor + "素食轻食");
        if (goal.contains("增肌")) return new Rule("650 至 800 千卡", "蛋白质不少于 35 克", "牛肉、鸡胸肉、鸡蛋和适量主食", "只有油脂没有蛋白质的套餐", flavor + "高蛋白盖饭");
        if (goal.contains("控糖")) return new Rule("450 至 600 千卡", "蛋白质不少于 25 克", "全谷物、鱼肉蛋豆和大量蔬菜", "含糖饮料、浓酱和精制甜点", flavor + "控糖轻食");
        if (goal.contains("减脂")) return new Rule("450 至 650 千卡", "蛋白质不少于 30 克", "鸡胸肉、牛肉、鱼类、蔬菜和杂粮", "油炸、重糖饮品和高脂酱料", flavor + "减脂轻食");
        return new Rule("500 至 700 千卡", "蛋白质不少于 25 克", "一份蛋白质、两份蔬菜和适量主食", "过量油炸和含糖饮料", flavor + "营养均衡套餐");
    }

    private record Rule(String calories, String protein, String prefer, String avoid, String keyword) { }
}
