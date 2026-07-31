package com.example.ilink.capabilities.image;

import com.example.ilink.application.tooling.ToolManager;
import com.example.ilink.application.tooling.ToolContext;
import com.example.ilink.platform.persistence.DefaultUserSessionStore;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageAnalysisToolTest {

    @Test
    void acceptsMissingModelRequestAndFallsBackToGenericImageAnalysis() {
        ToolManager manager = new ToolManager().register(new ImageAnalysisTool(
                new ImageService(HttpClient.newHttpClient()), new DefaultUserSessionStore()));

        var result = manager.execute(ImageAnalysisTool.NAME, new ToolContext("user-1"), new JsonObject());

        assertFalse(result.success());
        assertTrue(result.output().contains("没有找到需要分析的图片"));
    }
}
