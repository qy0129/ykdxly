package com.example.demo;

import com.example.demo.service.WechatBotService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Scanner;

@SpringBootApplication
public class DemoApplication {

    public static final String APP_VERSION = "1.0.0";
    public static volatile boolean RUNNING = true;

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(DemoApplication.class, args);
        System.out.println("==================== 程序启动完成 ====================");
        System.out.println("输入命令操作，输入 help 查看可用指令");

        new Thread(() -> {
            try (Scanner scanner = new Scanner(System.in)) {
                while (RUNNING && scanner.hasNextLine()) {
                    System.out.print("> ");
                    String cmd = scanner.nextLine().trim();
                    try {
                        handleCommand(cmd, context);
                    } catch (Exception e) {
                        printErrorMsg("执行命令【" + cmd + "】发生异常：" + e.getMessage());
                    }
                }
            } catch (Exception ignored) {
            }
        }, "command-input-thread").start();
    }

    private static void handleCommand(String cmd, ConfigurableApplicationContext context) throws Exception {
        switch (cmd) {
            case "help":
                printHelp();
                break;
            case "version":
                printVersion();
                break;
            case "status":
                printStatus(context);
                break;
            case "bot":
                WechatBotService botService = context.getBean(WechatBotService.class);
                System.out.println("Bot 运行状态: " + (botService.isRunning() ? "运行中" : "已停止"));
                System.out.println("Bot 登录状态: " + (botService.isLoggedIn() ? "已登录" : "未登录"));
                break;
            case "bot start":
                context.getBean(WechatBotService.class).start();
                break;
            case "bot stop":
                context.getBean(WechatBotService.class).stop();
                break;
            case "exit":
                RUNNING = false;
                System.out.println("正在关闭程序...");
                context.close();
                System.exit(0);
                break;
            case "":
                break;
            default:
                printErrorMsg("未知命令！输入 help 查看可用指令");
        }
    }

    private static void printErrorMsg(String message) {
        System.out.println("\033[31m[ERROR] " + message + "\033[0m");
    }

    private static void printHelp() {
        System.out.println("===== 可用命令列表 =====");
        System.out.println("help          显示帮助信息");
        System.out.println("version       查看程序版本");
        System.out.println("status        查看程序运行状态");
        System.out.println("bot           查看 Bot 状态");
        System.out.println("bot start     启动微信 Bot（扫码登录）");
        System.out.println("bot stop      停止微信 Bot");
        System.out.println("exit          退出程序");
    }

    private static void printVersion() {
        System.out.println("当前程序版本：" + APP_VERSION);
    }

    private static void printStatus(ConfigurableApplicationContext context) {
        System.out.println("===== 程序状态信息 =====");
        System.out.println("程序是否运行中：" + context.isRunning());
        System.out.println("容器是否已启动：" + context.isActive());
        System.out.println("Bean总数：" + context.getBeanDefinitionCount());
        WechatBotService botService = context.getBean(WechatBotService.class);
        System.out.println("Bot 是否运行：" + botService.isRunning());
        System.out.println("Bot 是否登录：" + botService.isLoggedIn());
    }
    private static String loadApiKey() {
        try {
            Properties props = new Properties();
            Path path = Path.of("config.properties");
            if (Files.exists(path)) {
                try (InputStream in = Files.newInputStream(path)) {
                    props.load(in);
                    String key = props.getProperty("api.key");
                    if (key != null && !key.isBlank() && !key.contains("把你的key")) {
                        return key;
                    }
                }
            }
        } catch (Exception ignored) {}
        System.err.println("错误: 请创建 properties.properties 文件，内容为: api.key=你的Key");
        System.err.println("参考 config.properties.example");
        System.exit(1);
        return null;
    }
}
