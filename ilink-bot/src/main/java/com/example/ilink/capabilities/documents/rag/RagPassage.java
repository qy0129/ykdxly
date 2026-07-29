package com.example.ilink.capabilities.documents.rag;

/** 一条带来源和相似度的检索结果。 */
public record RagPassage(String fileName, int chunkIndex, String text, double score) { }
