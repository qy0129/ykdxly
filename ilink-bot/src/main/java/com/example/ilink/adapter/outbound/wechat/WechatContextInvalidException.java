package com.example.ilink.adapter.outbound.wechat;

import java.util.Locale;

/**
 * iLink 服务端拒绝当前会话上下文，需等待下一条入站消息刷新 Token。
 */
public final class WechatContextInvalidException extends Exception {
    public WechatContextInvalidException(String userId, Throwable cause) {
        super("微信会话上下文已失效，等待用户新消息刷新：" + userId, cause);
    }

    public static boolean matches(Throwable error) {
        boolean hasRetCode = false;
        boolean hasPrepareFailure = false;
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(Locale.ROOT);
                hasRetCode |= normalized.contains("ret=-2");
                hasPrepareFailure |= normalized.contains("prepare failed");
            }
            if (hasRetCode && hasPrepareFailure) return true;
            current = current.getCause();
        }
        return hasRetCode && hasPrepareFailure;
    }
}
