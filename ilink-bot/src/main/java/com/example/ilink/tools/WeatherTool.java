package com.example.ilink.tools;

/**
 * 天气工具的公共骨架。
 *
 * <p>本类只定义天气工具需要提供的能力，不包含网络请求、API Key、JSON 解析
 * 和回复格式化逻辑。小组成员可以继承本类，完成具体天气平台的接入。</p>
 */
public abstract class WeatherTool implements Tool {

    /** 查询指定城市当前天气。 */
    public abstract String queryCurrentWeather(String city) throws Exception;

    /**
     * 查询指定城市未来天气预报。
     *
     * @param city 城市名称
     * @param days 查询天数
     * @return 天气预报结果
     * @throws Exception 天气服务调用失败时抛出异常
     */
    public abstract String queryForecast(String city, int days) throws Exception;
}
