package com.example.ilink.capabilities.documents;

/** 文件生成或编辑工具交给应用层发送的结果。 */
public record DocumentToolOutput(
        byte[] bytes,
        String extension,
        String fileName,
        String content,
        String caption) {
}
