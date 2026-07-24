package com.example.ilink.tools.express;

import com.example.ilink.feature.express.ExpressPageService;
import com.example.ilink.feature.express.ExpressService;
import com.example.ilink.tools.core.Tool;
import com.example.ilink.tools.core.ToolArguments;
import com.example.ilink.tools.core.ToolContext;
import com.example.ilink.tools.core.ToolDefinition;
import com.example.ilink.tools.core.ToolResult;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** Function Calling 快递查询工具。 */
public final class ExpressTool implements Tool {

    public static final String NAME = "query_express";
    private final ExpressService expressService;
    private final ExpressPageService pageService;
    private final ToolDefinition definition;

    public ExpressTool(ExpressService expressService) {
        this(expressService, null);
    }

    public ExpressTool(ExpressService expressService, ExpressPageService pageService) {
        this.expressService = expressService;
        this.pageService = pageService;
        JsonObject properties = new JsonObject();
        properties.add("tracking_no", ToolDefinition.stringProperty("需要查询的快递单号"));
        properties.add("phone", ToolDefinition.stringProperty("完整手机号或手机号后四位"));
        this.definition = new ToolDefinition(NAME, "快递查询", "根据快递单号或手机号查询物流轨迹。",
                ToolDefinition.objectParameters(properties), true);
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public ToolResult execute(ToolContext context, JsonObject arguments) throws Exception {
        String trackingNo = ToolArguments.string(arguments, "tracking_no", "");
        String phone = ToolArguments.string(arguments, "phone", "");
        if (trackingNo.isBlank() && phone.isBlank()) {
            return ToolResult.failure("请提供快递单号或手机号码。");
        }
        if (trackingNo.isBlank()) return queryByPhone(phone);

        ExpressService.ExpressResult result = expressService.query(trackingNo, phone);
        String output = ExpressService.format(result);
        if (!result.success()) return ToolResult.failure(output);
        String pageUrl = pageService == null ? "" : pageService.createPage(result);
        if (!pageUrl.isBlank()) output += "\n\n物流详情：\n" + pageUrl;
        return ToolResult.success(output, new ExpressOutput(result, pageUrl));
    }

    private ToolResult queryByPhone(String phone) throws Exception {
        String cleanPhone = phone == null ? "" : phone.replaceAll("\\D", "");
        if (cleanPhone.length() < 4) return ToolResult.failure("手机号格式不正确，请提供完整手机号或后四位。");
        List<ExpressService.PhoneOrder> orders = expressService.queryByPhone(cleanPhone);
        if (orders.isEmpty()) return ToolResult.failure("没有查到该手机号关联的快递信息。");

        List<ExpressService.ExpressResult> results = new ArrayList<>();
        StringBuilder output = new StringBuilder("查到 ").append(orders.size()).append(" 个关联快递：\n");
        for (int index = 0; index < orders.size() && index < 6; index++) {
            ExpressService.PhoneOrder order = orders.get(index);
            ExpressService.ExpressResult result = expressService.query(
                    order.trackingNo(), order.courierCode(), cleanPhone);
            results.add(result);
            output.append('\n').append(index + 1).append(". ")
                    .append(order.courierName().isBlank() ? order.courierCode() : order.courierName())
                    .append(' ').append(order.trackingNo()).append('\n')
                    .append(ExpressService.format(result));
            String pageUrl = result.success() && pageService != null ? pageService.createPage(result) : "";
            if (!pageUrl.isBlank()) output.append("\n物流详情：").append(pageUrl);
            output.append('\n');
        }
        return ToolResult.success(output.toString().trim(), new PhoneOutput(cleanPhone, orders, results));
    }

    public record ExpressOutput(ExpressService.ExpressResult result, String pageUrl) { }

    public record PhoneOutput(String phone, List<ExpressService.PhoneOrder> orders,
                              List<ExpressService.ExpressResult> results) { }
}
