package com.example.ilink.capabilities.finance;

import com.example.ilink.capabilities.finance.ExpenseSplitService;
import com.example.ilink.application.tooling.Tool;
import com.example.ilink.application.tooling.ToolArguments;
import com.example.ilink.application.tooling.ToolContext;
import com.example.ilink.application.tooling.ToolDefinition;
import com.example.ilink.application.tooling.ToolResult;
import com.google.gson.JsonObject;

/** Function Calling 多人费用分摊工具。 */
public final class ExpenseSplitTool implements Tool {

    public static final String NAME = "expense_split";

    private final ExpenseSplitService service;
    private final ToolDefinition definition;

    /** 注入负责参数提取和精确结算的费用分摊服务。 */
    public ExpenseSplitTool(ExpenseSplitService service) {
        this.service = service;
        JsonObject properties = new JsonObject();
        properties.add("request", ToolDefinition.stringProperty(
                "用户关于多人 AA、平分或不同付款金额结算的完整原话"));
        this.definition = new ToolDefinition(
                NAME,
                "多人费用分摊",
                "计算多人平均 AA、不同付款金额和最终转账方案。",
                ToolDefinition.objectParameters(properties, "request"),
                true);
    }

    /** 返回工具定义。 */
    @Override
    public ToolDefinition definition() {
        return definition;
    }

    /** 根据用户原话计算费用分摊。 */
    @Override
    public ToolResult execute(ToolContext context, JsonObject arguments) {
        return ToolResult.success(service.split(ToolArguments.requireString(arguments, "request")));
    }
}
