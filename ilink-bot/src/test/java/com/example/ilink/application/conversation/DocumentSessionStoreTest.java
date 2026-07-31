package com.example.ilink.application.conversation;

import com.example.ilink.capabilities.documents.DocumentRecord;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DocumentSessionStoreTest {

    @Test
    void usesLatestDocumentWithoutNameAndNamedDocumentWhenExplicitlyMentioned() {
        DocumentSessionStore store = new DocumentSessionStore();
        DocumentRecord first = new DocumentRecord("报告A.docx", "docx", "a.docx", "A");
        DocumentRecord latest = new DocumentRecord("报告B.docx", "docx", "b.docx", "B");
        store.set("user", first);
        store.set("user", latest);

        assertEquals("报告B.docx", store.resolve("user", "在这个文件后面增加文字").fileName());
        assertEquals("报告A.docx", store.resolve("user", "修改报告A.docx末尾文字").fileName());
        assertEquals("报告B.docx", store.get("user").fileName());
    }
}
