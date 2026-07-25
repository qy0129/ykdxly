package com.example.ilink.feature.food;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.PushPromiseHandler;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FoodPreferenceMapperTest {

    @Test
    void mapsCommonLightFoodPreferenceWithoutCallingModel() {
        StubHttpClient client = new StubHttpClient("");
        FoodPreferenceMapper mapper = mapper(client);

        assertEquals(java.util.List.of("粥", "汤面", "馄饨", "蒸菜"),
                mapper.mapKeywords("清淡一点的食物 有什么推荐"));
        assertEquals(0, client.requestCount);
    }

    @Test
    void keepsConcreteRestaurantKeywordWithoutCallingModel() {
        StubHttpClient client = new StubHttpClient("");
        FoodPreferenceMapper mapper = mapper(client);

        assertEquals(java.util.List.of("麦当劳"), mapper.mapKeywords("麦当劳"));
        assertEquals(0, client.requestCount);
    }

    @Test
    void usesModelForUnmappedAbstractPreference() {
        String response = "{\"choices\":[{\"message\":{\"content\":"
                + "\"[\\\"粤菜\\\",\\\"炖汤\\\",\\\"砂锅\\\"]\"}}]}";
        StubHttpClient client = new StubHttpClient(response);
        FoodPreferenceMapper mapper = mapper(client);

        assertEquals(java.util.List.of("粤菜", "炖汤", "砂锅"),
                mapper.mapKeywords("想吃点温和但有滋味的东西"));
        assertEquals(1, client.requestCount);
    }

    private FoodPreferenceMapper mapper(StubHttpClient client) {
        return new FoodPreferenceMapper(
                client, "test-key", "https://example.com/chat/completions", "test-model");
    }

    private static final class StubHttpClient extends HttpClient {
        private final String body;
        private int requestCount;

        private StubHttpClient(String body) {
            this.body = body;
        }

        @Override
        public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }

        @Override
        public Optional<Duration> connectTimeout() { return Optional.empty(); }

        @Override
        public Redirect followRedirects() { return Redirect.NEVER; }

        @Override
        public Optional<ProxySelector> proxy() { return Optional.empty(); }

        @Override
        public SSLContext sslContext() {
            try {
                return SSLContext.getDefault();
            } catch (java.security.NoSuchAlgorithmException error) {
                throw new AssertionError(error);
            }
        }

        @Override
        public SSLParameters sslParameters() { return new SSLParameters(); }

        @Override
        public Optional<Authenticator> authenticator() { return Optional.empty(); }

        @Override
        public Version version() { return Version.HTTP_1_1; }

        @Override
        public Optional<Executor> executor() { return Optional.empty(); }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, BodyHandler<T> handler) {
            requestCount++;
            return (HttpResponse<T>) new StubHttpResponse(body, request);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, BodyHandler<T> handler) {
            return CompletableFuture.completedFuture(send(request, handler));
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, BodyHandler<T> handler,
                PushPromiseHandler<T> pushPromiseHandler) {
            return sendAsync(request, handler);
        }
    }

    private record StubHttpResponse(String body, HttpRequest request)
            implements HttpResponse<String> {

        @Override
        public int statusCode() { return 200; }

        @Override
        public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }

        @Override
        public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (a, b) -> true); }

        @Override
        public Optional<SSLSession> sslSession() { return Optional.empty(); }

        @Override
        public URI uri() { return request.uri(); }

        @Override
        public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }
}
