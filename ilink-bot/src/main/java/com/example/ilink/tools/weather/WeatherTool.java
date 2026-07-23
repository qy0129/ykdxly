package com.example.ilink.tools.weather;

import com.example.ilink.feature.weather.WeatherLocation;
import com.example.ilink.feature.weather.WeatherService;
import com.example.ilink.tools.core.Tool;
import com.example.ilink.tools.core.ToolArguments;
import com.example.ilink.tools.core.ToolContext;
import com.example.ilink.tools.core.ToolDefinition;
import com.example.ilink.tools.core.ToolResult;
import com.google.gson.JsonObject;

import java.util.List;

/** Function Calling 天气查询工具，包装 WeatherService。 */
public final class WeatherTool implements Tool {

    public static final String NAME = "get_weather";

    private final WeatherService weatherService;
    private final ToolDefinition definition;

    /** 创建天气工具。 */
    public WeatherTool(WeatherService weatherService) {
        this.weatherService = weatherService;

        JsonObject properties = new JsonObject();
        properties.add("location", ToolDefinition.stringProperty(
                "需要查询的英文地点名，尽量包含城市、省份或国家，例如 Beijing, China"));
        properties.add("day", ToolDefinition.enumStringProperty(
                "查询日期和时段", "today", "tomorrow", "today_morning", "today_afternoon",
                "today_evening", "tomorrow_morning", "tomorrow_afternoon", "tomorrow_evening"));
        this.definition = new ToolDefinition(
                NAME,
                "天气查询",
                "查询指定城市、区县或乡镇今天或明天的天气。用户没有提供地点时不要调用。",
                ToolDefinition.objectParameters(properties, "location", "day"),
                true);
    }

    /** 返回天气工具定义。 */
    @Override
    public ToolDefinition definition() {
        return definition;
    }

    /** 搜索地点；唯一匹配时继续查询天气，多条匹配时返回候选地点。 */
    @Override
    public ToolResult execute(ToolContext context, JsonObject arguments) throws Exception {
        String locationName = ToolArguments.requireString(arguments, "location");
        String day = ToolArguments.string(arguments, "day", "today");
        List<WeatherLocation> locations = weatherService.searchLocations(locationName);
        if (locations.isEmpty()) {
            return ToolResult.failure("没有找到地点“" + locationName + "”，请补充省、市或国家。");
        }

        if (locations.size() > 1) {
            return ToolResult.success("找到多个同名地点，需要用户选择。",
                    new WeatherOutput(locations, day, null));
        }

        int dayOffset = WeatherService.dayOffset(day);
        String weatherText = weatherService.queryWeather(
                locations.get(0), dayOffset, WeatherService.period(day));
        return ToolResult.success(weatherText, new WeatherOutput(locations, day, weatherText));
    }

    /** 天气工具额外返回给应用层的结构化数据。 */
    public record WeatherOutput(List<WeatherLocation> locations, String day, String weatherText) {
    }
}
