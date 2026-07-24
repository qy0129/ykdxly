package com.example.ilink.tools.express;

import com.example.ilink.feature.express.ExpressService;
import com.example.ilink.tools.core.Tool;
import com.example.ilink.tools.core.ToolArguments;
import com.example.ilink.tools.core.ToolContext;
import com.example.ilink.tools.core.ToolDefinition;
import com.example.ilink.tools.core.ToolResult;
import com.google.gson.JsonObject;

/** Function Calling 快递查询工具。 */
public final class ExpressTool implements Tool {

    public static final String NAME = "query_express";
    private final ExpressService expressService;
    private final ToolDefinition definition;

    public ExpressTool(ExpressService expressService) {
        this.expressService = expressService;
        JsonObject properties = new JsonObject();
        properties.add("tracking_no", ToolDefinition.stringProperty("需要查询的快递单号"));
        this.definition = new ToolDefinition(NAME, "快递查询", "根据快递单号查询最新物流轨迹。",
                ToolDefinition.objectParameters(properties, "tracking_no"), true);
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public ToolResult execute(ToolContext context, JsonObject arguments) throws Exception {
        String trackingNo = ToolArguments.requireString(arguments, "tracking_no");
        ExpressService.ExpressResult result = expressService.query(trackingNo);
        String output = ExpressService.format(result);
        return result.success() ? ToolResult.success(output, result) : ToolResult.failure(output);
    }
}
