package com.example.ilink.application.workflow.travel;

import com.example.ilink.application.conversation.UserSessionStore;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaxiWorkflowTest {
    @Test
    void usesSavedCurrentLocationOnlyWhenRouteHasNoExplicitOrigin() {
        UserSessionStore sessions = (UserSessionStore) Proxy.newProxyInstance(
                UserSessionStore.class.getClassLoader(), new Class<?>[]{UserSessionStore.class},
                (proxy, method, args) -> {
                    if ("getCurrentLocation".equals(method.getName())) return "浙江省杭州市西湖区文三路";
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == long.class) return 0L;
                    return null;
                });
        TaxiWorkflow workflow = new TaxiWorkflow(null, null, sessions);

        assertEquals("浙江省杭州市西湖区文三路", workflow.resolveOrigin("user", ""));
        assertEquals("杭州东站", workflow.resolveOrigin("user", "杭州东站"));
    }
}
