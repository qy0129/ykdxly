package com.wechat.link.file.service;

import com.wechat.link.llm.config.LLMProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * 基于 Apache Tika 的文档内容提取服务
 * <p>
 * 自动识别并提取 docx / pdf / xlsx / pptx / txt / html 等格式的纯文本内容。
 * 支持嵌入式 OCR（需 Tesseract 本机库），默认使用 tika-parsers-standard-package。
 * </p>
 */
@Slf4j
@Service
public class TikaDocumentExtractor {

    private final Tika tika;
    private final int maxExtractChars;

    public TikaDocumentExtractor(LLMProperties properties) {
        this.tika = new Tika();
        this.maxExtractChars = properties.getDocument().getMaxExtractChars();
        log.info("[TikaExtractor] 初始化完成，最大提取字符数: {}", maxExtractChars);
    }

    /**
     * 从文件字节中提取纯文本内容
     *
     * @param fileBytes 文件字节数组
     * @param fileName  文件名（用于日志，Tika 自动检测类型）
     * @return 提取的纯文本（空文件返回空字符串）
     * @throws TikaExtractException 提取失败时抛出
     */
    public String extractText(byte[] fileBytes, String fileName) {
        if (fileBytes == null || fileBytes.length == 0) {
            log.warn("[TikaExtractor] 文件 [{}] 内容为空", fileName);
            return "";
        }

        long start = System.currentTimeMillis();
        log.info("[TikaExtractor] 开始提取 [{}]，大小: {}KB", fileName, fileBytes.length / 1024);

        try {
            String text = tika.parseToString(new ByteArrayInputStream(fileBytes));

            if (text == null || text.isBlank()) {
                log.warn("[TikaExtractor] [{}] 提取结果为空（可能为纯图片PDF或扫描件）", fileName);
                return "";
            }

            // 截断过长内容
            if (text.length() > maxExtractChars) {
                log.warn("[TikaExtractor] [{}] 内容过长 ({}字符)，截断至 {}字符",
                        fileName, text.length(), maxExtractChars);
                text = text.substring(0, maxExtractChars)
                        + "\n\n...（内容过长，仅展示前 " + maxExtractChars + " 字符）";
            }

            long elapsed = System.currentTimeMillis() - start;
            log.info("[TikaExtractor] [{}] 提取完成，耗时 {}ms，字符数: {}",
                    fileName, elapsed, text.length());
            return text;

        } catch (TikaException e) {
            log.error("[TikaExtractor] [{}] Tika 解析异常: {}", fileName, e.getMessage());
            throw new TikaExtractException("Tika 解析失败: " + fileName, e);
        } catch (IOException e) {
            log.error("[TikaExtractor] [{}] 读取异常: {}", fileName, e.getMessage());
            throw new TikaExtractException("文件读取失败: " + fileName, e);
        }
    }

    /**
     * 包装异常
     */
    public static class TikaExtractException extends RuntimeException {
        public TikaExtractException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
