package com.example.ilink;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.listener.OnMessageListener;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * iLink 微信机器人 - 固定回复版
 *
 * 使用 SDK: wechat-ilink-sdk (io.github.lith0924:wechat-ilink-sdk:2.1.0)
 * 协议: 腾讯微信 iLink Bot API (https://ilinkai.weixin.qq.com)
 *
 * 工作流程:
 *   1. 获取二维码 → 终端打印URL → 微信扫码登录
 *   2. 长轮询接收用户消息
 *   3. 随机回复一句固定文本
 */
public class ILinkBot {

    private static final List<String> FIXED_REPLIES = List.of(
            "你好！我是微信小助手 🤖",
            "收到你的消息了！",
            "我现在还在开发中，只能做固定回复。",
            "这个问题很有趣，我会记下来让开发者改进！",
            "你可以跟我说点别的～",
            "嗯嗯，我在听。",
            "谢谢你的消息！",
            "今天天气真不错 😊",
            "继续加油！",
            "收到 👌"
    );

    private final Random random = new Random();
    private volatile boolean running = true;

    public void start() throws Exception {
        System.out.println("========================================");
        System.out.println("  iLink 微信机器人 - 固定回复版");
        System.out.println("  SDK: wechat-ilink-sdk v2.1.0");
        System.out.println("========================================\n");

        ILinkClient client = ILinkClient.builder()
                .onMessage(createMessageHandler())
                .build();

        // 第一步：获取二维码
        System.out.println("正在获取登录二维码...");
        String qrcodeImg = client.executeLogin(); // base64 图片数据
        String qrcodeParam = client.getQrcode();  // 二维码参数

        // 二维码可能是链接 URL 或图片 base64
        if (qrcodeImg.startsWith("http")) {
            System.out.println("请浏览器打开此链接扫码登录:");
            System.out.println(qrcodeImg + "\n");
        } else {
            String raw = qrcodeImg.replaceFirst("^data:image/[a-zA-Z]+;base64,", "");
            Files.write(Path.of("qrcode.png"), Base64.getDecoder().decode(raw));
            System.out.println("二维码已保存到 qrcode.png，请打开并用微信扫码登录\n");
        }

        // 第二步：等待扫码确认
        System.out.println("等待扫码中...");
        while (!client.isLoggedIn()) {
            Thread.sleep(2000);
            System.out.println("  状态: 等待扫码...");
        }

        System.out.println("登录成功！开始监听消息... (Ctrl+C 退出)\n");

        // 第三步：长轮询收消息
        while (running) {
            try {
                List<WeixinMessage> messages = client.getUpdates();
                if (messages != null && !messages.isEmpty()) {
                    for (WeixinMessage msg : messages) {
                        handleMessage(client, msg);
                    }
                }
            } catch (Exception e) {
                if (running) {
                    System.err.println("轮询异常: " + e.getMessage());
                    Thread.sleep(5000);
                }
            }
        }

        client.close();
    }

    private OnMessageListener createMessageHandler() {
        return messages -> {
            // SDK 内部自动调 getUpdates 后回调此方法
        };
    }

    private void handleMessage(ILinkClient client, WeixinMessage msg) {
        try {
            String fromUser = msg.getFrom_user_id();
            List<com.github.wechat.ilink.sdk.core.model.MessageItem> items = msg.getItem_list();

            if (items == null || items.isEmpty()) return;

            // 只处理文本消息
            com.github.wechat.ilink.sdk.core.model.MessageItem first = items.get(0);
            String text = null;
            if (first.getText_item() != null) {
                text = first.getText_item().getText();
            }

            System.out.println("[" + fromUser + "] " + (text != null ? text : "[非文本消息]"));

            if (text != null) {
                String reply = FIXED_REPLIES.get(random.nextInt(FIXED_REPLIES.size()));
                System.out.println("[Bot] → " + reply);
                client.sendText(fromUser, reply);
            }
        } catch (Exception e) {
            System.err.println("处理消息异常: " + e.getMessage());
        }
    }

    public void stop() {
        running = false;
    }

    public static void main(String[] args) throws Exception {
        ILinkBot bot = new ILinkBot();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n正在退出...");
            bot.stop();
        }));

        bot.start();
    }
}
