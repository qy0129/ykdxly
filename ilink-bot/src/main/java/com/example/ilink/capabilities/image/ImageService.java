package com.example.ilink.capabilities.image;

import java.net.http.HttpClient;
import java.nio.file.Path;

/**
 * 图片能力门面。
 *
 * <p>统一暴露图片分析、生成和编辑接口，隐藏 VisionService 与
 * ImageGenerationService 的具体实现。</p>
 */
public final class ImageService {

    private final VisionService visionService;
    private final ImageGenerationService generationService;

    /** 创建图片服务门面。 */
    public ImageService(HttpClient httpClient) {
        this.visionService = new VisionService(httpClient);
        this.generationService = new ImageGenerationService(httpClient);
    }

    /** 调用视觉模型分析图片。 */
    public String vision(String userMessage, String base64Image) throws Exception {
        return visionService.vision(userMessage, base64Image);
    }

    /** 调用图片生成服务。 */
    public GeneratedImage generateImage(String prompt, String imageSize) throws Exception {
        return generationService.generateImage(prompt, imageSize);
    }

    /** 调用图片编辑服务。 */
    public GeneratedImage editImage(Path sourceImage, String prompt) throws Exception {
        return generationService.editImage(sourceImage, prompt);
    }
}
