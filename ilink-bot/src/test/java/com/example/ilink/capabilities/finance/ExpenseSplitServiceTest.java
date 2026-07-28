package com.example.ilink.capabilities.finance;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLSession;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.PushPromiseHandler;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpClient.Version;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpenseSplitServiceTest {

    @Test
    void infersTotalFromPaymentsWhenUserDidNotStateTotal() {
        String modelResponse = "{\"choices\":[{\"message\":{\"content\":"
                + "\"{\\\"title\\\":\\\"聚餐\\\",\\\"total\\\":3097,"
                + "\\\"currency\\\":\\\"元\\\",\\\"participants\\\":["
                + "{\\\"name\\\":\\\"我\\\",\\\"paid\\\":100},"
                + "{\\\"name\\\":\\\"张三\\\",\\\"paid\\\":2000},"
                + "{\\\"name\\\":\\\"王二\\\",\\\"paid\\\":698},"
                + "{\\\"name\\\":\\\"李四\\\",\\\"paid\\\":99}]}\"}}]}";

        ExpenseSplitService service = new ExpenseSplitService(new StubHttpClient(modelResponse));
        String result = service.split("4个人聚餐，我花了100，张三花了2000，王二花了698，李四花了99，该怎么分摊");

        assertTrue(result.contains("总金额：2897.00元"));
        assertTrue(result.contains("我 转给 张三 624.25元"));
        assertTrue(result.contains("王二 转给 张三 26.25元"));
        assertTrue(result.contains("李四 转给 张三 625.25元"));
        assertTrue(result.contains("张三已付 2000.00元"), result);
    }

    @Test
    void keepsMismatchWhenUserExplicitlyStatesTotal() {
        String modelResponse = "{\"choices\":[{\"message\":{\"content\":"
                + "\"{\\\"title\\\":\\\"聚餐\\\",\\\"total\\\":3097,"
                + "\\\"currency\\\":\\\"元\\\",\\\"participants\\\":["
                + "{\\\"name\\\":\\\"我\\\",\\\"paid\\\":100},"
                + "{\\\"name\\\":\\\"张三\\\",\\\"paid\\\":2000},"
                + "{\\\"name\\\":\\\"王二\\\",\\\"paid\\\":698},"
                + "{\\\"name\\\":\\\"李四\\\",\\\"paid\\\":99}]}\"}}]}";

        ExpenseSplitService service = new ExpenseSplitService(new StubHttpClient(modelResponse));
        String result = service.split("4个人聚餐，总金额3097元，我花了100，张三花了2000，王二花了698，李四花了99");

        assertTrue(result.contains("已付款合计为 2897.00元，与总金额 3097.00元 不一致"));
    }

    private static final class StubHttpClient extends HttpClient {
        private final String body;

        private StubHttpClient(String body) {
            this.body = body;
        }

        @Override
        public Optional<java.net.CookieHandler> cookieHandler() { return Optional.empty(); }

        @Override
        public Optional<Duration> connectTimeout() { return Optional.empty(); }

        @Override
        public Redirect followRedirects() { return Redirect.NEVER; }

        @Override
        public Optional<java.net.ProxySelector> proxy() { return Optional.empty(); }

        @Override
        public javax.net.ssl.SSLContext sslContext() {
            try {
                return javax.net.ssl.SSLContext.getDefault();
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new AssertionError(e);
            }
        }

        @Override
        public javax.net.ssl.SSLParameters sslParameters() { return new javax.net.ssl.SSLParameters(); }

        @Override
        public Optional<java.net.Authenticator> authenticator() { return Optional.empty(); }

        @Override
        public Version version() { return Version.HTTP_1_1; }

        @Override
        public Optional<Executor> executor() { return Optional.empty(); }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, BodyHandler<T> handler) {
            return (HttpResponse<T>) new StubHttpResponse(body);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, BodyHandler<T> handler) {
            return CompletableFuture.completedFuture(send(request, handler));
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, BodyHandler<T> handler, PushPromiseHandler<T> pushPromiseHandler) {
            return CompletableFuture.completedFuture(send(request, handler));
        }
    }

    private static final class StubHttpResponse implements HttpResponse<String> {
        private final String body;

        private StubHttpResponse(String body) {
            this.body = body;
        }

        @Override
        public int statusCode() { return 200; }

        @Override
        public HttpRequest request() { return null; }

        @Override
        public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }

        @Override
        public HttpHeaders headers() { return HttpHeaders.of(java.util.Map.of(), (a, b) -> true); }

        @Override
        public String body() { return body; }

        @Override
        public Optional<SSLSession> sslSession() { return Optional.empty(); }

        @Override
        public URI uri() { return URI.create("https://example.com"); }

        @Override
        public Version version() { return Version.HTTP_1_1; }
    }
}
