package com.example.ilink.feature.weather;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.io.IOException;
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
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AmapWeatherFallbackTest {

    @Test
    void usesAmapFirstForDomesticWeather() throws Exception {
        StubWeatherHttpClient client = new StubWeatherHttpClient();
        WeatherService service = new WeatherService(
                client, new AmapWeatherService(client, "test-key"));

        WeatherLocation location = service.searchLocations("杭州").getFirst();
        String weather = service.queryWeather(location, 0);
        WeatherSnapshot snapshot = service.queryWeatherSnapshot(location);

        assertEquals("杭州市", location.name());
        assertTrue(weather.contains("当前温度：37℃"));
        assertTrue(weather.contains("来源：高德开放平台"));
        assertEquals("cloudy", snapshot.visual().conditionGroup());
        assertEquals(37, snapshot.visual().temperature());
        assertTrue(snapshot.text().contains("湿度：40%"));
        assertEquals(0, client.openMeteoRequests);
    }

    @Test
    void usesOpenMeteoWhenAmapHasNoLocation() throws Exception {
        StubWeatherHttpClient client = new StubWeatherHttpClient(false);
        WeatherService service = new WeatherService(
                client, new AmapWeatherService(client, "test-key"));

        WeatherLocation location = service.searchLocations("London").getFirst();

        assertEquals("London", location.name());
        assertEquals("英国", location.country());
        assertEquals(1, client.openMeteoRequests);
    }

    private static final class StubWeatherHttpClient extends HttpClient {

        private final boolean amapLocationAvailable;
        private int openMeteoRequests;

        private StubWeatherHttpClient() {
            this(true);
        }

        private StubWeatherHttpClient(boolean amapLocationAvailable) {
            this.amapLocationAvailable = amapLocationAvailable;
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
        public <T> HttpResponse<T> send(HttpRequest request, BodyHandler<T> handler) throws IOException {
            String host = request.uri().getHost();
            if (host != null && host.contains("open-meteo.com")) {
                openMeteoRequests++;
                if (request.uri().getPath().endsWith("/v1/search")) {
                    return (HttpResponse<T>) new StubHttpResponse(openMeteoLocationResponse(), request);
                }
                throw new IOException("Unexpected Open-Meteo request: " + request.uri());
            }
            String path = request.uri().getPath();
            String query = request.uri().getQuery();
            String body;
            if (path.endsWith("/config/district")) {
                body = districtResponse();
            } else if (path.endsWith("/weather/weatherInfo") && query.contains("extensions=base")) {
                body = liveResponse();
            } else if (path.endsWith("/weather/weatherInfo") && query.contains("extensions=all")) {
                body = forecastResponse();
            } else {
                throw new IOException("Unexpected request: " + request.uri());
            }
            return (HttpResponse<T>) new StubHttpResponse(body, request);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, BodyHandler<T> handler) {
            try {
                return CompletableFuture.completedFuture(send(request, handler));
            } catch (IOException error) {
                return CompletableFuture.failedFuture(error);
            }
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, BodyHandler<T> handler,
                PushPromiseHandler<T> pushPromiseHandler) {
            return sendAsync(request, handler);
        }

        private String districtResponse() {
            if (!amapLocationAvailable) {
                return "{\"status\":\"1\",\"info\":\"OK\",\"districts\":[]}";
            }
            return "{\"status\":\"1\",\"info\":\"OK\",\"districts\":[{"
                    + "\"name\":\"杭州市\",\"adcode\":\"330100\","
                    + "\"center\":\"120.209947,30.245853\",\"level\":\"city\"}]}";
        }

        private String liveResponse() {
            return "{\"status\":\"1\",\"info\":\"OK\",\"lives\":[{"
                    + "\"weather\":\"多云\",\"temperature\":\"37\","
                    + "\"winddirection\":\"东南\",\"windpower\":\"≤3\","
                    + "\"humidity\":\"40\",\"reporttime\":\"2026-07-24 16:35:35\"}]}";
        }

        private String forecastResponse() {
            String today = LocalDate.now().toString();
            return "{\"status\":\"1\",\"info\":\"OK\",\"forecasts\":[{"
                    + "\"reporttime\":\"2026-07-24 16:35:35\",\"casts\":[{"
                    + "\"date\":\"" + today + "\",\"dayweather\":\"多云\","
                    + "\"nightweather\":\"雷阵雨\",\"daytemp\":\"37\","
                    + "\"nighttemp\":\"28\",\"daywind\":\"东南\","
                    + "\"nightwind\":\"东\",\"daypower\":\"≤3\","
                    + "\"nightpower\":\"≤3\"}]}]}";
        }

        private String openMeteoLocationResponse() {
            return "{\"results\":[{\"name\":\"London\",\"admin1\":\"England\","
                    + "\"admin2\":\"Greater London\",\"country\":\"英国\","
                    + "\"latitude\":51.5074,\"longitude\":-0.1278,"
                    + "\"feature_code\":\"PPLC\",\"population\":8982000}]}";
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
