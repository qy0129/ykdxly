package com.example.ilink.capabilities.express;

import com.example.ilink.capabilities.express.ExpressService.ExpressResult;
import com.example.ilink.capabilities.express.ExpressService.TrackingItem;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpressPageServiceTest {

    @Test
    void createsDesktopCompatiblePageAndStoresResult() {
        ExpressPageService service = new ExpressPageService();
        service.useBaseUrl("https://express.example.com/");
        ExpressResult result = result();

        String url = service.createPage(result);
        String token = url.substring(url.lastIndexOf('/') + 1);

        assertTrue(url.startsWith("https://express.example.com/express/view/"));
        assertArrayEquals(result.items().toArray(), service.getItems(token).toArray());
        assertTrue(service.getResult(token) == result);
    }

    @Test
    void rendersRouteFromEarliestToLatestTrackingArea() {
        ExpressResult result = new ExpressResult(true, "", "0", "SF1234567890", "shunfeng",
                "顺丰速运", List.of(
                new TrackingItem("2026-07-24 12:00:00", "快件到达北京转运中心", "北京", "110100"),
                new TrackingItem("2026-07-24 10:00:00", "快件到达南京转运中心", "南京", "320100"),
                new TrackingItem("2026-07-24 08:00:00", "快件已从杭州仓发出", "杭州", "330100")), "");
        String html = new ExpressPageRenderer().render("token", result);
        String mapData = html.substring(html.indexOf("window.__MAP_DATA__"));

        assertTrue(html.contains("window.__MAP_DATA__"));
        assertTrue(mapData.indexOf("杭州") < mapData.indexOf("南京"));
        assertTrue(mapData.indexOf("南京") < mapData.indexOf("北京"));
        assertTrue(mapData.contains("\"hasRoute\":true"));
        assertTrue(html.contains("/express/static/js/express.js"));
    }

    @Test
    void leavesMapDataAvailableWhenThereAreFewerThanTwoRealLocations() {
        String html = new ExpressPageRenderer().render("token", result());

        assertTrue(html.contains("\"hasRoute\":false"));
        assertFalse(html.contains("预计12分钟到达"));
        assertFalse(html.contains("中货车配送"));
    }

    @Test
    void keepsUnknownCityForClientSideGeocoding() {
        ExpressResult result = new ExpressResult(true, "", "0", "SF1234567890", "shunfeng",
                "顺丰速运", List.of(new TrackingItem(
                "2026-07-24 12:00:00", "快件到达宁波转运中心", "宁波", "330200")), "");

        String html = new ExpressPageRenderer().render("token", result);

        assertTrue(html.contains("宁波"));
        assertTrue(html.contains("\"hasRoute\":false"));
    }

    @Test
    void generatesQrCodeForExpressPage() {
        ExpressPageService service = new ExpressPageService();

        byte[] qr = service.generateQrCode("http://localhost:8089/express/view/token", 300);

        assertFalse(qr.length == 0);
    }

    @Test
    void routeScriptUsesSamePointListForLabelsAndMarkers() throws Exception {
        String script = new String(ExpressPageServiceTest.class.getClassLoader()
                .getResourceAsStream("static/express/js/express.js").readAllBytes());

        assertTrue(script.contains("var start = points[0]"));
        assertTrue(script.contains("var end = points[points.length - 1]"));
        assertTrue(script.contains("var currentPosition = resolved[resolved.length - 1]"));
        assertTrue(script.contains("mapWrap.hidden = false"));
        assertTrue(script.contains("if (!hasRoute)"));
        assertTrue(script.contains("function renderAddresses()"));
        assertTrue(script.contains("new window.BMap.DrivingRoute"));
        assertTrue(script.contains("new window.BMap.Geocoder"));
        assertTrue(script.contains("strokeColor: '#e53935'"));
        assertTrue(script.contains("'📍'"));
        assertTrue(script.contains("'🚚'"));
        assertFalse(script.contains("'●', displayName(current), 'truck'"));
        assertFalse(script.contains("预计12分钟到达"));
    }

    private ExpressResult result() {
        return new ExpressResult(true, "", "0", "SF1234567890", "shunfeng",
                "顺丰速运", List.of(new TrackingItem(
                "2026-07-24 12:00:00", "到达南京转运中心", "南京", "320100")), "");
    }
}
