package com.example.ilink.capabilities.food;

import com.example.ilink.capabilities.food.FoodOrderService;
import com.example.ilink.application.tooling.Tool;
import com.example.ilink.application.tooling.ToolArguments;
import com.example.ilink.application.tooling.ToolContext;
import com.example.ilink.application.tooling.ToolDefinition;
import com.example.ilink.application.tooling.ToolResult;
import com.google.gson.JsonObject;

/** 根据餐厅名称返回美团和饿了么的移动端搜索兜底入口。 */
public final class FoodOrderTool implements Tool {

    public static final String NAME = "food_order";

    private final FoodOrderService service;
    private final ToolDefinition definition;

    public FoodOrderTool(FoodOrderService service) {
        this.service = service;
        JsonObject properties = new JsonObject();
        properties.add("restaurants", ToolDefinition.stringProperty("餐厅名称，多个名称用逗号分隔"));
        definition = new ToolDefinition(
                NAME,
                "点餐链接",
                "根据餐厅名称生成饿了么和美团外卖搜索兜底入口。",
                ToolDefinition.objectParameters(properties, "restaurants"),
                true);
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public ToolResult execute(ToolContext context, JsonObject arguments) {
        return ToolResult.success(
                service.generateLinks(ToolArguments.requireString(arguments, "restaurants")));
    }
}
