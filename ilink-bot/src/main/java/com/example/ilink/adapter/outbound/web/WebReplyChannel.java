package com.example.ilink.adapter.outbound.web;

import com.example.ilink.application.messaging.AgentEvent;
import com.example.ilink.application.messaging.ChannelType;
import com.example.ilink.application.messaging.ConsoleLog;
import com.example.ilink.application.messaging.ReplyChannel;
import com.example.ilink.application.messaging.RequestLogContext;
import com.example.ilink.application.conversation.ChatHistoryStore;
import com.example.ilink.application.integration.WechatWebBridge;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Web implementation of the outbound channel port. */
public final class WebReplyChannel implements ReplyChannel {

    private final WebEventBroker events;
    private final WebArtifactStore artifacts;
    private final ChatHistoryStore history;
    private final WechatWebBridge wechatBridge;
    private final ConcurrentHashMap<String, AtomicLong> generations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> completedTexts = new ConcurrentHashMap<>();
    private final ThreadLocal<RequestScope> requestScope = new ThreadLocal<>();

    public WebReplyChannel(WebEventBroker events, WebArtifactStore artifacts) {
        this(events, artifacts, null);
    }

    public WebReplyChannel(WebEventBroker events, WebArtifactStore artifacts,
                           ChatHistoryStore history) {
        this(events, artifacts, history, null);
    }

    public WebReplyChannel(WebEventBroker events, WebArtifactStore artifacts,
                           ChatHistoryStore history, WechatWebBridge wechatBridge) {
        this.events = events;
        this.artifacts = artifacts;
        this.history = history;
        this.wechatBridge = wechatBridge;
    }

    public void beginRequest(String recipientId, String sessionId, String requestId) {
        long generation = generations.computeIfAbsent(requestId, ignored -> new AtomicLong()).get();
        completedTexts.remove(requestId);
        requestScope.set(new RequestScope(recipientId, sessionId, requestId, generation));
    }

    public void endRequest() {
        requestScope.remove();
    }

    public void cancel(String requestId) {
        generations.computeIfAbsent(requestId, ignored -> new AtomicLong()).incrementAndGet();
        completedTexts.remove(requestId);
    }

    public String consumeCompletedText(String requestId) {
        return completedTexts.remove(requestId);
    }

    @Override
    public void startTyping(String recipientId) {
        if (!canPublish(recipientId)) return;
        publishScoped(recipientId, new AgentEvent(
                AgentEvent.Type.STATUS, "正在处理", Map.of("state", "working")));
    }

    @Override
    public void sendText(String recipientId, String text) {
        ensureCanPublish(recipientId);
        rememberCompletedText(text);
        if (wechatBridge != null && text != null && !text.isBlank()) {
            RequestScope scope = requestScope.get();
            wechatBridge.mirrorWebReply(recipientId, scope == null ? "" : scope.requestId(), text);
        }
        publishScoped(recipientId, new AgentEvent(
                AgentEvent.Type.COMPLETED, text, Map.of("kind", "text", "state", "idle")));
        ConsoleLog.botMessage(ChannelType.WEB, recipientId, text);
    }

    @Override
    public void sendImage(String recipientId, byte[] content, String fileName, String caption) throws Exception {
        ensureCanPublish(recipientId);
        RequestScope scope = requestScope.get();
        String ownerId = scope == null ? recipientId : scope.recipientId();
        ConsoleLog.info("回复发送", "向用户发送图片，用户标识=" + recipientId + "，文件名="
                + ConsoleLog.summary(fileName) + "，文件大小=" + (content == null ? 0 : content.length)
                + "字节，说明=" + ConsoleLog.summary(caption));
        WebArtifactStore.Artifact artifact = artifacts.save(ownerId, content, fileName, imageContentType(fileName));
        AgentEvent event = artifactEvent("image", artifact, caption);
        rememberArtifact(ownerId, artifact, event, "image");
        rememberCompletedText(event.content());
        publishScoped(recipientId, event);
    }

    @Override
    public void sendFile(String recipientId, byte[] content, String fileName, String caption) throws Exception {
        ensureCanPublish(recipientId);
        RequestScope scope = requestScope.get();
        String ownerId = scope == null ? recipientId : scope.recipientId();
        ConsoleLog.info("回复发送", "向用户发送文件，用户标识=" + recipientId + "，文件名="
                + ConsoleLog.summary(fileName) + "，文件大小=" + (content == null ? 0 : content.length)
                + "字节，说明=" + ConsoleLog.summary(caption));
        WebArtifactStore.Artifact artifact = artifacts.save(ownerId, content, fileName, "");
        AgentEvent event = artifactEvent("file", artifact, caption);
        rememberArtifact(ownerId, artifact, event, "file");
        rememberCompletedText(event.content());
        publishScoped(recipientId, event);
    }

    @Override
    public void publish(String recipientId, AgentEvent event) {
        if (!canPublish(recipientId)) return;
        if (event.type() == AgentEvent.Type.TOOL_ACTIVITY || event.type() == AgentEvent.Type.ERROR) {
            System.out.println(logPrefix("Agent事件", recipientId) + " type="
                    + event.type().name().toLowerCase(java.util.Locale.ROOT)
                    + " preview=" + RequestLogContext.preview(event.content()));
        }
        publishScoped(recipientId, event);
    }

    @Override
    public boolean persistsOutboundMedia() {
        return history != null;
    }

    private void rememberArtifact(String ownerId, WebArtifactStore.Artifact artifact,
                                  AgentEvent event, String kind) {
        if (history == null) return;
        history.addAssistantMedia(ownerId, kind, artifact.id(), artifact.fileName(),
                artifact.contentType(), artifact.size(), event.content());
    }

    private boolean canPublish(String recipientId) {
        RequestScope scope = requestScope.get();
        if (scope == null) return true;
        return scope.generation() == generations.get(scope.requestId()).get();
    }

    private void ensureCanPublish(String recipientId) {
        if (!canPublish(recipientId)) throw new CancellationException("Web task was cancelled");
    }

    private void publishScoped(String recipientId, AgentEvent event) {
        RequestScope scope = requestScope.get();
        if (scope == null) {
            events.publish(recipientId, event);
            return;
        }
        Map<String, Object> metadata = new LinkedHashMap<>(event.metadata());
        metadata.put("sessionId", scope.sessionId());
        metadata.put("requestId", scope.requestId());
        events.publish(scope.recipientId(), new AgentEvent(event.type(), event.content(), metadata));
    }

    private void rememberCompletedText(String text) {
        RequestScope scope = requestScope.get();
        if (scope != null && text != null && !text.isBlank()) completedTexts.put(scope.requestId(), text);
    }

    private AgentEvent artifactEvent(String kind, WebArtifactStore.Artifact artifact, String caption) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("kind", kind);
        metadata.put("artifactId", artifact.id());
        metadata.put("fileName", artifact.fileName());
        metadata.put("contentType", artifact.contentType());
        metadata.put("size", artifact.size());
        metadata.put("state", "idle");
        String content = caption == null || caption.isBlank()
                ? ("image".equals(kind) ? "图片已生成" : "文件已生成")
                : caption;
        return new AgentEvent(AgentEvent.Type.COMPLETED, content, metadata);
    }

    private String logPrefix(String event, String recipientId) {
        RequestScope scope = requestScope.get();
        return RequestLogContext.prefix(ChannelType.WEB, event, recipientId,
                scope == null ? "" : scope.sessionId(), scope == null ? "" : scope.requestId());
    }

    private String imageContentType(String fileName) {
        String value = fileName == null ? "" : fileName.toLowerCase(java.util.Locale.ROOT);
        if (value.endsWith(".jpg") || value.endsWith(".jpeg")) return "image/jpeg";
        if (value.endsWith(".webp")) return "image/webp";
        return "image/png";
    }

    private record RequestScope(String recipientId, String sessionId, String requestId, long generation) {
    }
}
