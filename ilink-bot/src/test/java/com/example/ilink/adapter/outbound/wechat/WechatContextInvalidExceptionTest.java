package com.example.ilink.adapter.outbound.wechat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WechatContextInvalidExceptionTest {

    @Test
    void matchesWrappedPrepareFailure() {
        Throwable error = new RuntimeException("prepare failed",
                new IllegalStateException("ret=-2, errcode=null"));

        assertTrue(WechatContextInvalidException.matches(error));
    }

    @Test
    void ignoresOtherSendFailures() {
        assertFalse(WechatContextInvalidException.matches(
                new IllegalStateException("ret=-1, errmsg=network error")));
    }
}
