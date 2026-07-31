package com.example.ilink.capabilities.documents;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentFileTypeTest {

    @Test
    void recognizesAllSupportedInputFamilies() {
        for (String type : new String[]{"doc", "docx", "xls", "xlsx", "txt", "md", "csv", "pdf", "ppt", "pptx"}) {
            assertTrue(DocumentFileType.supportsInput(type), type);
        }
    }

    @Test
    void normalizesUserFacingOutputNames() {
        assertEquals("docx", DocumentFileType.fromUserText("生成 Word 文件"));
        assertEquals("xlsx", DocumentFileType.fromUserText("整理成 Excel 电子表格"));
        assertEquals("md", DocumentFileType.fromUserText("保存为 Markdown"));
        assertEquals("pptx", DocumentFileType.fromUserText("生成 PPT"));
    }

    @Test
    void presentationCanBeReadAndEditedButNotGenerated() {
        assertTrue(DocumentFileType.supportsInput("pptx"));
        assertTrue(DocumentFileType.canEditOutput("pptx"));
        assertFalse(DocumentFileType.canGenerate("pptx"));
    }

    @Test
    void legacyEditDefaultsUseModernFormats() {
        assertEquals("docx", DocumentFileType.defaultEditOutput("doc"));
        assertEquals("xlsx", DocumentFileType.defaultEditOutput("xls"));
        assertEquals("pptx", DocumentFileType.defaultEditOutput("ppt"));
    }
}
