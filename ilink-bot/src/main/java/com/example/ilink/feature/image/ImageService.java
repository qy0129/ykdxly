package com.example.ilink.feature.image;

import java.net.http.HttpClient;
import java.nio.file.Path;

public final class ImageService {

    private final VisionService visionService;
    private final ImageGenerationService generationService;

    public ImageService(HttpClient httpClient) {
        this.visionService = new VisionService(httpClient);
        this.generationService = new ImageGenerationService(httpClient);
    }

    public String vision(String userMessage, String base64Image) throws Exception {
        return visionService.vision(userMessage, base64Image);
    }

    public byte[] generateImage(String prompt, String imageSize) throws Exception {
        return generationService.generateImage(prompt, imageSize);
    }

    public byte[] editImage(Path sourceImage, String prompt) throws Exception {
        return generationService.editImage(sourceImage, prompt);
    }
}