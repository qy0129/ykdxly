package com.example.ilink.feature.food;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlatformStoreDataServiceTest {

    @Test
    void parsesMatchingElemeStoreFromWrappedResponse() {
        String json = """
                {"restaurant_with_foods":[
                  {"restaurant":{"id":"E123","name":"肯德基（杭州大厦店）"}},
                  {"restaurant":{"id":"E456","name":"肯德基（武林店）"}}
                ]}
                """;

        assertEquals("E456", ElemeDataService.findStoreId(json, "肯德基(武林店)"));
    }

    @Test
    void doesNotUseUnrelatedElemeStoreId() {
        String json = "[{\"id\":\"E123\",\"name\":\"肯德基（杭州大厦店）\"}]";

        assertEquals("", ElemeDataService.findStoreId(json, "外婆家（武林店）"));
    }

    @Test
    void parsesMeituanIdOnlyWhenStoreNameIsNearby() {
        String body = "{\"name\":\"肯德基\\uFF08武林店\\uFF09\",\"wmPoiId\":123456}";

        assertEquals("123456", MeituanDataService.findStoreId(body, "肯德基（武林店）"));
        assertEquals("", MeituanDataService.findStoreId(body, "外婆家（武林店）"));
    }
}
