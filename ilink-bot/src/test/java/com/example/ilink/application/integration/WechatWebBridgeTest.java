package com.example.ilink.application.integration;

import com.example.ilink.adapter.outbound.web.WebEventBroker;
import com.example.ilink.adapter.outbound.web.WebArtifactStore;
import com.example.ilink.application.conversation.ConversationSession;
import com.example.ilink.application.conversation.UserSessionStore;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WechatWebBridgeTest {

    @TempDir
    Path tempDir;

    @Test
    void pairingMirrorsOnlyBotVisibleMessagesAndSendsWithoutDuplicateRecord() throws Exception {
        WebEventBroker events = new WebEventBroker();
        WechatWebBridge bridge = new WechatWebBridge(events);
        Gateway gateway = new Gateway();
        bridge.attach(gateway);

        WechatWebBridge.Pairing pairing = bridge.beginPairing("web-user");
        assertTrue(bridge.consumePairing("wechat-user", "绑定 " + pairing.code()));
        assertTrue(bridge.status("web-user").paired());
        assertTrue(bridge.status("web-user").ready());

        bridge.recordIncoming("wechat-user", "你好");
        assertEquals(1, bridge.messages("web-user").size());
        assertEquals("wechat_input", bridge.messages("web-user").getFirst().source());
        var mirrored = events.history(WechatWebBridge.EVENT_STREAM, 0).getLast().event();
        assertEquals("WECHAT", mirrored.metadata().get("channel"));
        assertEquals("wechat_input", mirrored.metadata().get("source"));
        assertTrue(mirrored.metadata().containsKey("messageId"));
        assertTrue(mirrored.metadata().containsKey("createdAtMillis"));

        assertTrue(bridge.syncWebInput("web-user", "电脑端消息", true));
        assertEquals(List.of("电脑端消息"), gateway.texts);
        assertEquals(1, bridge.messages("web-user").size(), "outbound channel owns its own mirror record");

        bridge.mirrorWebReply("web-user", "最终回复");
        assertEquals(List.of("电脑端消息", "最终回复"), gateway.texts);
        assertTrue(bridge.processWebInput("web-user", "从电脑继续任务"));
        assertEquals(List.of("从电脑继续任务"), gateway.processedTexts);
        assertEquals(List.of("从电脑继续任务"), gateway.mirroredInputs);
        assertEquals("web_input", bridge.messages("web-user").getLast().source());
        assertFalse(bridge.status("other-web-user").paired());
    }

    @Test
    void connectedLoginIdentityActivatesWebWorkspaceBeforeConversationIsReady() {
        WebEventBroker events = new WebEventBroker();
        WechatWebBridge bridge = new WechatWebBridge(events);
        Gateway gateway = new Gateway();
        gateway.canSend = false;
        bridge.attach(gateway);

        bridge.updateActiveUser("wechat-user");
        WechatWebBridge.Status status = bridge.activate("web-user");

        assertTrue(status.connected());
        assertTrue(status.paired());
        assertFalse(status.ready());
        assertEquals("wechat-user", status.wechatUserId());
        assertEquals("微信已登录，请先在手机向 Bot 发送一条消息", status.detail());
    }

    @Test
    void replySyncFlagsAreIsolatedByRequestAndSendFailureDoesNotLeakState() throws Exception {
        WebEventBroker events = new WebEventBroker();
        WechatWebBridge bridge = new WechatWebBridge(events);
        Gateway gateway = new Gateway();
        bridge.attach(gateway);
        bridge.updateActiveUser("wechat-user");
        bridge.activate("web-user");

        assertTrue(bridge.syncWebInput("web-user", "request-a", "消息 A", true));
        assertTrue(bridge.syncWebInput("web-user", "request-b", "消息 B", false));
        bridge.mirrorWebReply("web-user", "request-b", "回复 B");
        bridge.mirrorWebReply("web-user", "request-a", "回复 A");
        assertEquals(List.of("消息 A", "消息 B", "回复 A"), gateway.texts);

        gateway.failSend = true;
        assertFalse(bridge.syncWebInput("web-user", "request-c", "失败消息", true));
        gateway.failSend = false;
        bridge.mirrorWebReply("web-user", "request-c", "不应补发");
        assertEquals(List.of("消息 A", "消息 B", "回复 A"), gateway.texts);
        assertEquals("sync_failed", events.history(WechatWebBridge.EVENT_STREAM, 0)
                .getLast().event().metadata().get("integrationType"));
    }

    @Test
    void mirrorFailureStillProcessesWebInput() throws Exception {
        WebEventBroker events = new WebEventBroker();
        WechatWebBridge bridge = new WechatWebBridge(events);
        Gateway gateway = new Gateway();
        gateway.failMirror = true;
        bridge.attach(gateway);
        bridge.updateActiveUser("wechat-user");
        bridge.activate("web-user");

        assertTrue(bridge.processWebInput("web-user", "继续完成任务"));
        assertEquals(List.of("继续完成任务"), gateway.processedTexts);
        assertEquals("sync_failed", events.history(WechatWebBridge.EVENT_STREAM, 0)
                .getLast().event().metadata().get("integrationType"));
    }

    @Test
    void imageRepliesAreStoredForThePairedWebWorkspace() {
        WebEventBroker events = new WebEventBroker();
        WebArtifactStore artifacts = new WebArtifactStore(tempDir);
        WechatWebBridge bridge = new WechatWebBridge(events, artifacts);
        bridge.updateActiveUser("wechat-user");
        bridge.attach(new Gateway());
        bridge.activate("web-user");

        bridge.recordOutgoingImage("wechat-user", new byte[] {1, 2, 3}, "draw.png", "图片已生成");

        WechatWebBridge.Message message = bridge.messages("web-user").getLast();
        assertEquals("image", message.kind());
        assertFalse(message.artifactId().isBlank());
        assertTrue(artifacts.find("web-user", message.artifactId()).isPresent());
        var event = events.history(WechatWebBridge.EVENT_STREAM, 0).getLast().event();
        assertEquals("image", event.metadata().get("kind"));
        assertEquals(message.artifactId(), event.metadata().get("artifactId"));

        bridge.recordOutgoingFile("wechat-user", new byte[] {4, 5}, "reply.mp3", "语音回复");
        WechatWebBridge.Message audio = bridge.messages("web-user").getLast();
        assertEquals("audio", audio.kind());
        assertEquals("audio/mpeg", audio.contentType());
    }

    @Test
    void webSessionSwitchChangesTheWechatConversationForTheNextMessage() {
        AtomicReference<String> activeSession = new AtomicReference<>("wechat-session-a");
        UserSessionStore sessions = (UserSessionStore) Proxy.newProxyInstance(
                UserSessionStore.class.getClassLoader(), new Class<?>[] {UserSessionStore.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getCurrentSession")) {
                        LocalDateTime now = LocalDateTime.now();
                        return new ConversationSession("wechat-user", activeSession.get(), now, now);
                    }
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    return null;
                });
        WechatWebBridge bridge = new WechatWebBridge(new WebEventBroker(), null, sessions);

        bridge.updateActiveUser("wechat-user");
        activeSession.set("web-session-b");
        bridge.updateActiveUser("wechat-user");

        assertEquals("web-session-b", bridge.sessionIdFor("wechat-user"));
    }

    private static final class Gateway implements WechatWebBridge.Gateway {
        private final List<String> texts = new ArrayList<>();
        private final List<String> processedTexts = new ArrayList<>();
        private final List<String> mirroredInputs = new ArrayList<>();
        private boolean canSend = true;
        private boolean failSend;
        private boolean failMirror;
        @Override public boolean connected() { return true; }
        @Override public boolean canSend(String userId) { return canSend; }
        @Override public String status() { return "connected"; }
        @Override public void sendText(String userId, String text) {
            if (failSend) throw new IllegalStateException("send failed");
            texts.add(text);
        }
        @Override public void sendFile(String userId, byte[] content, String fileName, String caption) { }
        @Override public void mirrorWebInput(String userId, String text) {
            if (failMirror) throw new IllegalStateException("mirror failed");
            mirroredInputs.add(text);
        }
        @Override public void processText(String userId, String text) { processedTexts.add(text); }
    }
}
