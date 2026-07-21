package com.wechat.link.file.service;

import com.wechat.link.llm.config.LLMProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Markdown → Word/PDF 文档转换服务
 * <p>
 * 通过 ProcessBuilder 调用本地 Python 脚本 markdown_to_docx.py，
 * 将 LLM 生成的 Markdown 文本转换为 .docx 或 .pdf 文件。
 * 临时文件在 try-finally 中闭环清理。
 * </p>
 */
@Slf4j
@Service
public class DocumentConvertService {

    private final LLMProperties.DocumentConfig config;

    public DocumentConvertService(LLMProperties properties) {
        this.config = properties.getDocument();
        log.info("[DocumentConvert] 初始化，python={}, script={}, timeout={}s",
                config.getPythonPath(), config.getConvertScript(), config.getTimeout());
    }

    /**
     * 将 Markdown 文本转换为目标格式文件
     *
     * @param markdownText LLM 输出的 Markdown 内容
     * @param targetFormat 目标格式：docx / pdf
     * @return 生成文件的字节数组
     * @throws DocumentConvertException 转换失败时抛出
     */
    public byte[] convertMarkdownToFile(String markdownText, String targetFormat) {
        if (markdownText == null || markdownText.isBlank()) {
            throw new DocumentConvertException("Markdown 内容为空");
        }
        if (!"docx".equalsIgnoreCase(targetFormat) && !"pdf".equalsIgnoreCase(targetFormat)) {
            throw new DocumentConvertException("不支持的目标格式: " + targetFormat + "，仅支持 docx/pdf");
        }

        String fmt = targetFormat.toLowerCase();
        Path inputMd = null;
        Path outputFile = null;

        try {
            // 1. 创建临时输入 .md 文件
            inputMd = Files.createTempFile("md_input_", ".md");
            Files.writeString(inputMd, markdownText, StandardCharsets.UTF_8);

            // 2. 创建临时输出文件路径（脚本负责写入）
            outputFile = Files.createTempFile("doc_output_", "." + fmt);

            // 3. 构建转换命令
            ProcessBuilder pb = new ProcessBuilder(
                    config.getPythonPath(),
                    config.getConvertScript(),
                    inputMd.toAbsolutePath().toString(),
                    outputFile.toAbsolutePath().toString()
            );

            // 合并错误流到标准输出，方便日志
            pb.redirectErrorStream(true);

            log.info("[DocumentConvert] 执行转换: {} -> {}, markdown大小={}字符",
                    inputMd.getFileName(), fmt, markdownText.length());

            long start = System.currentTimeMillis();
            Process process = pb.start();

            // 读取进程输出（防止缓冲区满导致死锁）
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            // 等待完成
            boolean finished = process.waitFor(config.getTimeout(), java.util.concurrent.TimeUnit.SECONDS);
            int exitCode = finished ? process.exitValue() : -1;

            if (!finished) {
                process.destroyForcibly();
                throw new DocumentConvertException("转换超时（" + config.getTimeout() + "s）");
            }

            if (exitCode != 0) {
                log.error("[DocumentConvert] 转换失败，exitCode={}, 输出: {}", exitCode, output);
                throw new DocumentConvertException("Python 脚本返回非零退出码: " + exitCode);
            }

            // 4. 读取生成的文件
            byte[] result = Files.readAllBytes(outputFile);
            long elapsed = System.currentTimeMillis() - start;
            log.info("[DocumentConvert] 转换成功，耗时 {}ms，输出大小: {}KB",
                    elapsed, result.length / 1024);

            if (result.length == 0) {
                throw new DocumentConvertException("生成的文件为空");
            }

            return result;

        } catch (IOException e) {
            log.error("[DocumentConvert] 文件I/O异常", e);
            throw new DocumentConvertException("文件操作失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DocumentConvertException("转换进程被中断", e);
        } finally {
            // 5. 清理临时文件
            deleteQuietly(inputMd);
            deleteQuietly(outputFile);
        }
    }

    private void deleteQuietly(Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
                log.debug("[DocumentConvert] 临时文件已删除: {}", path.getFileName());
            } catch (IOException e) {
                log.warn("[DocumentConvert] 临时文件删除失败: {}", path.getFileName(), e);
            }
        }
    }

    public static class DocumentConvertException extends RuntimeException {
        public DocumentConvertException(String message) {
            super(message);
        }

        public DocumentConvertException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
