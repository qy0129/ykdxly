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
    public static volatile boolean RUNNING = true;

    public static void main(String[] args) {
        // 启动Spring容器
        ConfigurableApplicationContext context = SpringApplication.run(DemoApplication.class, args);
        System.out.println("====================程序启动完成====================");
        System.out.println("输入命令操作，输入 exit 退出程序");

        new Thread(() -> {
            Scanner scanner = new Scanner(System.in);
            while (RUNNING) {
                System.out.print("> ");
                String cmd = scanner.nextLine().trim();
                // ========== 命令调用处增加异常捕获 ==========
                try {
                    handleCommand(cmd, context);
                } catch (Exception e) {
                    // 捕获所有异常，展示错误消息
                    printErrorMsg("执行命令【" + cmd + "】发生异常：" + e.getMessage());
                    // 如果需要打印完整堆栈便于调试打开下面一行
                    // e.printStackTrace();
                }
            }
            scanner.close();
        }, "command-input-thread").start();
    }

    /**
     * 命令分发处理器
     */
    private static void handleCommand(String cmd, ConfigurableApplicationContext context) throws Exception{
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
                context.close();
                System.exit(0);
                break;
            case "":
                break;
            default:
                printErrorMsg("未知命令！输入 help 查看可用指令");
        }
    }

    /**
     * 统一打印错误消息方法
     */
    private static void printErrorMsg(String message){
        // 控制台标识错误信息，醒目区分正常输出
        System.out.println("\033[31m[ERROR] " + message + "\033[0m");
    }

    private static void printHelp() {
        System.out.println("===== 可用命令列表 =====");
        System.out.println("help      显示帮助信息");
        System.out.println("version   查看程序版本");
        System.out.println("status    查看程序运行状态");
        System.out.println("exit      退出程序");
    }

    private static void printVersion() {
        System.out.println("当前程序版本：" + APP_VERSION);
    }

    private static void printStatus(ConfigurableApplicationContext context) {
        System.out.println("===== 程序状态信息 =====");
        System.out.println("程序是否运行中：" + context.isRunning());
        System.out.println("容器是否已启动：" + context.isActive());
        System.out.println("Bean总数：" + context.getBeanDefinitionCount());
    }
}