package com.example.ilink.application.workflow.visual;

import com.example.ilink.application.messaging.ReplyChannel;
import com.example.ilink.bootstrap.Config;
import com.example.ilink.capabilities.visual.VisualCard;
import com.example.ilink.capabilities.visual.VisualCardRenderer;
import com.example.ilink.capabilities.web.TextLinkFormatter;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/** 先渲染整组图片，再无文字穿插地连续发送，便于微信左右滑动查看。 */
public final class VisualDeckSender {

    private final VisualCardRenderer renderer;
    private final Runnable sentMarker;
    private final BiConsumer<String, String> textRecorder;

    public VisualDeckSender(VisualCardRenderer renderer) {
        this(renderer, () -> { }, (userId, text) -> { });
    }

    public VisualDeckSender(VisualCardRenderer renderer, Runnable sentMarker) {
        this(renderer, sentMarker, (userId, text) -> { });
    }

    public VisualDeckSender(VisualCardRenderer renderer, Runnable sentMarker,
                            BiConsumer<String, String> textRecorder) {
        this.renderer = renderer;
        this.sentMarker = sentMarker;
        this.textRecorder = textRecorder;
    }

    /** 发送不需要用户选择的普通文字，并同步更新回复状态。 */
    public void sendText(ReplyChannel client, String userId, String text) throws Exception {
        String displayText = TextLinkFormatter.format(text);
        client.sendText(userId, displayText);
        sentMarker.run();
        textRecorder.accept(userId, displayText);
    }

    public void send(ReplyChannel client, String userId, List<VisualCard> cards,
                     String textFallback) throws Exception {
        if (!Config.VISUAL_CARDS_ENABLED || "text".equalsIgnoreCase(Config.VISUAL_CARDS_MODE)) {
            sendText(client, userId, textFallback);
            return;
        }
        List<VisualCard> deck = cards == null ? List.of() : cards.stream()
                .limit(Config.VISUAL_CARDS_MAX_DECK_SIZE)
                .toList();
        if (deck.isEmpty()) {
            sendText(client, userId, textFallback);
            return;
        }

        List<byte[]> images = new ArrayList<>();
        try {
            for (int index = 0; index < deck.size(); index++) {
                images.add(renderer.render(deck.get(index), index + 1, deck.size()));
            }
            for (int index = 0; index < images.size(); index++) {
                client.sendImage(userId, images.get(index), "card-" + (index + 1) + ".png", "");
                sentMarker.run();
            }
            if ("both".equalsIgnoreCase(Config.VISUAL_CARDS_MODE)) {
                sendText(client, userId, textFallback);
            } else {
                textRecorder.accept(userId, TextLinkFormatter.format(textFallback));
            }
        } catch (Exception error) {
            System.err.println("[视觉卡片] 发送失败，回退文本: " + error.getMessage());
            sendText(client, userId, textFallback);
        }
    }
}
