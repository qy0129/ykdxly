package com.example.ilink.capabilities.documents;

/** 当前用户正在处理的文档及其解析文本。 */
public record DocumentRecord(String fileName, String extension, String path, String text) {
}
