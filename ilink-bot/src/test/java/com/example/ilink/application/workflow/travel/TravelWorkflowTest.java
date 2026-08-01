package com.example.ilink.application.workflow.travel;

import com.example.ilink.capabilities.travel.AmapService;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TravelWorkflowTest {

    @Test
    void usesSavedPreciseLocationOnlyWhenOriginIsMissing() {
        AmapService.Place current = new AmapService.Place("高桥云港园区", "120.1200", "30.2800");
        TravelWorkflow workflow = new TravelWorkflow(new AmapService(HttpClient.newHttpClient()),
                null, null,
                userId -> current);

        assertEquals(current, workflow.resolveSavedOrigin("user", ""));
        assertNull(workflow.resolveSavedOrigin("user", "杭州东站"));
    }

    @Test
    void extractsCompleteRouteFromCurrentLocationReply() {
        TravelWorkflow.RouteText route = TravelWorkflow.parseRouteText("从当前位置去西湖，再到杭州西站");

        assertEquals("", route.origin());
        assertEquals("杭州西站", route.destination());
        assertEquals(java.util.List.of("西湖"), route.stops());
    }

    @Test
    void extractsVisitDestinationWhenOnlyOneStopIsMentioned() {
        TravelWorkflow.RouteText route = TravelWorkflow.parseRouteText(
                "帮我规划明天杭州半日游：上午去西湖，中午找附近餐厅");

        assertEquals("西湖", route.destination());
        assertEquals(java.util.List.of(), route.stops());
    }

    @Test
    void removesModelAddedPlanWordsFromDestination() {
        assertEquals("西湖", TravelWorkflow.cleanLocation("西湖的行程"));
        assertEquals("杭州西站", TravelWorkflow.cleanLocation("杭州西站路线规划"));
    }

    @Test
    void removesUnrelatedPoiCandidatesAndPrefersExactPlaceName() {
        List<AmapService.Place> filtered = TravelWorkflow.relevantLocationCandidates("西湖",
                List.of(new AmapService.Place("云祁民宿(西湖灵隐寺店)", "120.1", "30.1"),
                        new AmapService.Place("西湖", "120.2", "30.2"),
                        new AmapService.Place("携程旅游(黄龙国际中心店)", "120.3", "30.3")));

        assertEquals(List.of("西湖", "云祁民宿(西湖灵隐寺店)"),
                filtered.stream().map(AmapService.Place::name).toList());
    }
}
