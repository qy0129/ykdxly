package com.example.ilink.feature.image;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GeneratedImageTest {

    @Test
    void detectsPngFromBytesWhenHeaderIsGeneric() {
        byte[] png = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        GeneratedImage image = GeneratedImage.from(png, "application/octet-stream");

        assertEquals("png", image.extension());
        assertEquals("image/png", image.contentType());
        assertEquals("draw.png", image.fileName("draw"));
    }

    @Test
    void detectsWebpFromBytes() {
        byte[] webp = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'};
        GeneratedImage image = GeneratedImage.from(webp, "image/png");

        assertEquals("webp", image.extension());
        assertEquals("image/webp", image.contentType());
    }
}
