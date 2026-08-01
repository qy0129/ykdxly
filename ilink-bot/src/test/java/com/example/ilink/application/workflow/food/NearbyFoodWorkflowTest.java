package com.example.ilink.application.workflow.food;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NearbyFoodWorkflowTest {

    @Test
    void explicitLocationOverridesModelAndRememberedLocation() {
        String location = NearbyFoodWorkflow.resolveLocation(
                "我现在在西湖附近，推荐三家适合午餐的餐厅。",
                "", "阿里巴巴高桥云港园区");

        assertEquals("西湖", location);
    }

    @Test
    void usesRememberedLocationOnlyWhenCurrentRequestHasNoLocation() {
        assertEquals("阿里巴巴高桥云港园区", NearbyFoodWorkflow.resolveLocation(
                "推荐附近适合午餐的餐厅", "", "阿里巴巴高桥云港园区"));
    }

    @Test
    void parsesRequestedRestaurantCount() {
        assertEquals(3, NearbyFoodWorkflow.requestedLimit(
                "我现在在西湖附近，推荐三家适合午餐的餐厅。"));
        assertEquals(5, NearbyFoodWorkflow.requestedLimit("推荐附近餐厅"));
        assertEquals(10, NearbyFoodWorkflow.requestedLimit("推荐二十家餐厅"));
    }

    @Test
    void usesPlaceFromPlannedFoodActionInsteadOfTimePhrase() {
        assertEquals("西湖", NearbyFoodWorkflow.explicitLocation(
                "查找西湖附近中午适合用餐的餐厅。上午去西湖，中午找附近餐厅"));
    }

    @Test
    void removesLocationAndGenericWordsFromFoodKeyword() {
        assertEquals("", NearbyFoodWorkflow.resolveMealKeyword("西湖附近中午适合用餐的餐厅"));
        assertEquals("川菜", NearbyFoodWorkflow.resolveMealKeyword("西湖附近的川菜"));
    }
}
