package com.example.demo;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.PostConstruct;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

//import javax.annotation.PostConstruct;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;

@SpringBootApplication
@Component
public class DemoApplication {

    // =========从配置文件读取参数=========
    @Value("${dashscope.api.key}")
    private String dashscopeApiKey;

    @Value("${dashscope.api.url}")
    private String dashscopeUrl;

    @Value("${dashscope.model.name}")
    private String modelName;

    @Value("${app.version}")
    public String APP_VERSION;

    public static volatile boolean RUNNING = true;
    private static OkHttpClient httpClient;
    public static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(DemoApplication.class, args);
        System.out.println("====================程序启动完成====================");
        System.out.println("指令示例：weather 杭州");
        System.out.println("输入命令操作，输入 exit 退出程序");
    }

    // Spring容器初始化完成后启动控制台监听
    @PostConstruct
    public void startCommandListener() {
        // 初始化http客户端
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();

        new Thread(() -> {
            Scanner scanner = new Scanner(System.in);
            while (RUNNING) {
                System.out.print("> ");
                String cmdLine = scanner.nextLine().trim();
                try {
                    handleCommand(cmdLine);
                } catch (Exception e) {
                    printErrorMsg("执行命令发生异常：" + e.getMessage());
                    // e.printStackTrace(); //调试打开
                }
            }
            scanner.close();
        }, "command-input-thread").start();
    }

    /**
     * 命令分发
     */
    private void handleCommand(String cmdLine) {
        String[] parts = cmdLine.split("\\s+");
        String cmd = parts[0];

        switch (cmd) {
            case "help":
                printHelp();
                break;
            case "version":
                printVersion();
                break;
            case "status":
                printStatus();
                break;
            case "weather":
                // 校验是否传入城市
                if (parts.length < 2 || parts[1].isBlank()) {
                    printErrorMsg("参数缺失！正确格式：weather 城市名称，例如 weather 北京");
                    return;
                }
                String city = parts[1];
                queryWeather(city);
                break;
            case "exit":
                RUNNING = false;
                System.out.println("正在关闭程序...");
                System.exit(0);
                break;
            case "":
                break;
            default:
                printErrorMsg("未知命令！输入 help 查看可用指令");
        }
    }

    /**
     * 调用阿里云百炼查询天气
     */
    private void queryWeather(String city) {
        try {
            JSONObject bodyObj = new JSONObject();
            bodyObj.put("model", modelName);

            JSONObject input = new JSONObject();
            input.put("prompt", "简洁查询【" + city + "】今日天气，包含气温、天气状况、风力，不要多余内容");
            bodyObj.put("input", input);

            RequestBody requestBody = RequestBody.create(bodyObj.toJSONString(), JSON_TYPE);
            Request request = new Request.Builder()
                    .url(dashscopeUrl)
                    .header("Authorization", "Bearer " + dashscopeApiKey)
                    .post(requestBody)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    printErrorMsg("接口请求失败，HTTP状态码：" + response.code());
                    return;
                }
                String respText = response.body().string();
                JSONObject json = JSON.parseObject(respText);

                // 判断百炼返回业务错误
                String errorCode = json.getString("code");
                if (errorCode != null && !errorCode.isEmpty()) {
                    printErrorMsg("API调用失败：" + json.getString("message"));
                    return;
                }

                JSONObject output = json.getJSONObject("output");
                String weatherInfo = output.getString("text");

                System.out.println("\n==========" + city + " 天气信息==========");
                System.out.println(weatherInfo);
                System.out.println("========================================\n");
            }
        } catch (java.net.SocketTimeoutException e) {
            printErrorMsg("请求超时，请检查网络");
        } catch (Exception e) {
            printErrorMsg("天气查询异常：" + e.getMessage());
        }
    }

    /**
     * 红色错误信息打印
     */
    private static void printErrorMsg(String message) {
        System.out.println("\033[31m[ERROR] " + message + "\033[0m");
    }

    private void printHelp() {
        System.out.println("===== 可用命令列表 =====");
        System.out.println("help              显示帮助信息");
        System.out.println("version           查看程序版本");
        System.out.println("status            查看程序运行状态");
        System.out.println("weather 城市名    查询指定城市天气（例：weather 杭州）");
        System.out.println("exit              退出程序");
    }

    private void printVersion() {
        System.out.println("当前程序版本：" + APP_VERSION);
    }

    private void printStatus() {
        System.out.println("===== 程序状态信息 =====");
        System.out.println("程序运行标识：" + RUNNING);
        System.out.println("百炼模型：" + modelName);
    }
}