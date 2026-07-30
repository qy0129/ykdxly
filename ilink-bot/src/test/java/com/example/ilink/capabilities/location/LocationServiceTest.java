package com.example.ilink.capabilities.location;

import com.example.ilink.application.conversation.UserSessionStore;
import com.example.ilink.capabilities.travel.AmapService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocationServiceTest {

    @Test
    void consumesTokenAfterSuccessfulLocationUpdate() throws Exception {
        AtomicReference<String> savedAddress = new AtomicReference<>();
        UserSessionStore sessions = fakeSessions(savedAddress);
        AmapService amap = new AmapService(HttpClient.newHttpClient()) {
            @Override public boolean isConfigured() { return true; }
            @Override public Place reverseGeocode(double longitude, double latitude, String coordinateSystem) {
                return new Place("浙江省杭州市西湖区", "120.1551", "30.2741");
            }
        };
        LocationService service = new LocationService(
                amap, sessions, new LocationLinkParser(HttpClient.newHttpClient()));
        service.useBaseUrl("https://location.example.com");
        String url = service.createAuthorizationUrl("user-1");
        String token = URI.create(url).getPath().substring("/location/authorize/".length());

        assertTrue(service.isTokenActive(token));
        LocationService.LocationUpdate update = service.submitGps(token, 30.2741, 120.1551, 15.0);

        assertEquals("浙江省杭州市西湖区", savedAddress.get());
        assertEquals("浙江省杭州市西湖区", update.address());
        assertFalse(service.isTokenActive(token));
        assertThrows(IllegalArgumentException.class,
                () -> service.submitGps(token, 30.2741, 120.1551, 15.0));
    }

    private UserSessionStore fakeSessions(AtomicReference<String> savedAddress) {
        return (UserSessionStore) Proxy.newProxyInstance(
                UserSessionStore.class.getClassLoader(), new Class<?>[]{UserSessionStore.class},
                (proxy, method, args) -> {
                    if ("setCurrentLocation".equals(method.getName())) {
                        savedAddress.set((String) args[1]);
                        return null;
                    }
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == long.class) return 0L;
                    return null;
                });
    }
}
