package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import java.util.Scanner;

@SpringBootApplication
public class DemoApplication {
    // 版本常量
    public static final String APP_VERSION = "1.0.0";
    // 运行状态标记
    public static boolean RUNNING = true;

    public static void main(String[] args) {
        // 启动Spring容器
        ConfigurableApplicationContext context = SpringApplication.run(DemoApplication.class, args);
        System.out.println("====================程序启动完成====================");
        System.out.println("输入命令操作，输入 exit 退出程序");

        // 开启控制台命令监听
        Scanner scanner = new Scanner(System.in);
        while (RUNNING) {
            System.out.print("> ");
            String cmd = scanner.nextLine().trim();
            handleCommand(cmd, context);
        }
        scanner.close();
        context.close();
    }

    /**
     * 命令分发处理器
     */
    private static void handleCommand(String cmd, ConfigurableApplicationContext context) {
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
            case "exit":
                RUNNING = false;
                System.out.println("正在关闭程序...");
                break;
            case "":
                // 空输入忽略
                break;
            default:
                System.out.println("未知命令！输入 help 查看可用指令");
        }
    }

    /**
     * help 命令：打印帮助信息
     */
    private static void printHelp() {
        System.out.println("===== 可用命令列表 =====");
        System.out.println("help      显示帮助信息");
        System.out.println("version   查看程序版本");
        System.out.println("status    查看程序运行状态");
        System.out.println("exit      退出程序");
    }

    /**
     * version 命令：显示版本
     */
    private static void printVersion() {
        System.out.println("当前程序版本：" + APP_VERSION);
    }

    /**
     * status 命令：显示运行状态
     */
    private static void printStatus(ConfigurableApplicationContext context) {
        System.out.println("===== 程序状态信息 =====");
        System.out.println("程序是否运行中：" + context.isRunning());
        System.out.println("容器是否已启动：" + context.isActive());
        System.out.println("Bean总数：" + context.getBeanDefinitionCount());
    }
}