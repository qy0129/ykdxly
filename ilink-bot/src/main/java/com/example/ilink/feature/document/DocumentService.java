package com.example.ilink.feature.document;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public final class DocumentService {

    private final DocumentParser parser = new DocumentParser();
    private final DocumentGenerator generator = new DocumentGenerator();
    private final DocxEditor editor = new DocxEditor();

    public ParsedDocument parse(Path path, String originalFileName) throws Exception {
        return parser.parse(path, originalFileName);
    }

    public byte[] createDocx(String title, String content) throws IOException {
        return generator.createDocx(title, content);
    }

    public byte[] createPdf(String title, String content) throws IOException {
        return generator.createPdf(title, content);
    }

    public DocxEditResult editDocx(Path original, List<TextEdit> edits) throws IOException {
        return editor.editDocx(original, edits);
    }

    public static String extension(String fileName) {
        return DocumentParser.extension(fileName);
    }

    public record ParsedDocument(String fileName, String extension, String text) {
    }

    public record TextEdit(String type, String target, String replacement) {
    }

    public record DocxEditResult(byte[] document, int appliedEdits, List<String> unmatchedTargets) {
    }
}
