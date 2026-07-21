package com.example.ilink.feature.document;

import com.example.ilink.config.Config;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBody;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageSz;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class DocxEditor {
    public DocumentService.DocxEditResult editDocx(Path original, List<DocumentService.TextEdit> edits) throws IOException {
        List<String> unmatchedTargets = new ArrayList<>();
        int appliedEdits = 0;

        try (InputStream input = Files.newInputStream(original);
             XWPFDocument document = new XWPFDocument(input);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (DocumentService.TextEdit edit : edits) {
                if ("append".equals(edit.type())) {
                    document.createParagraph().createRun().setText(edit.replacement());
                    appliedEdits++;
                    continue;
                }

                boolean replaced = false;
                for (XWPFParagraph paragraph : editableParagraphs(document)) {
                    if (replaceFirst(paragraph, edit.target(), edit.replacement())) {
                        replaced = true;
                        appliedEdits++;
                        break;
                    }
                }
                if (!replaced) {
                    unmatchedTargets.add(edit.target());
                }
            }
            document.write(output);
            return new DocumentService.DocxEditResult(output.toByteArray(), appliedEdits, unmatchedTargets);
        }
    }

    private List<XWPFParagraph> editableParagraphs(XWPFDocument document) {
        List<XWPFParagraph> paragraphs = new ArrayList<>(document.getParagraphs());
        for (XWPFTable table : document.getTables()) {
            collectTableParagraphs(table, paragraphs);
        }
        return paragraphs;
    }

    private void collectTableParagraphs(XWPFTable table, List<XWPFParagraph> paragraphs) {
        for (var row : table.getRows()) {
            for (var cell : row.getTableCells()) {
                paragraphs.addAll(cell.getParagraphs());
                for (XWPFTable nestedTable : cell.getTables()) {
                    collectTableParagraphs(nestedTable, paragraphs);
                }
            }
        }
    }

    private boolean replaceFirst(XWPFParagraph paragraph, String target, String replacement) {
        if (target == null || target.isEmpty()) return false;

        List<RunText> runTexts = new ArrayList<>();
        StringBuilder paragraphText = new StringBuilder();
        for (XWPFRun run : paragraph.getRuns()) {
            String text = run.getText(0);
            if (text == null || text.isEmpty()) continue;
            int start = paragraphText.length();
            paragraphText.append(text);
            runTexts.add(new RunText(run, text, start, paragraphText.length()));
        }

        int matchStart = paragraphText.indexOf(target);
        if (matchStart < 0) return false;
        int matchEnd = matchStart + target.length();
        RunText startRun = null;
        RunText endRun = null;
        for (RunText runText : runTexts) {
            if (startRun == null && matchStart >= runText.start() && matchStart < runText.end()) {
                startRun = runText;
            }
            if (matchEnd > runText.start() && matchEnd <= runText.end()) {
                endRun = runText;
                break;
            }
        }
        if (startRun == null || endRun == null) return false;

        int startOffset = matchStart - startRun.start();
        int endOffset = matchEnd - endRun.start();
        if (startRun == endRun) {
            setRunText(startRun.run(), startRun.text().substring(0, startOffset)
                    + replacement + startRun.text().substring(endOffset));
            return true;
        }

        setRunText(startRun.run(), startRun.text().substring(0, startOffset) + replacement);
        boolean inMatch = false;
        for (RunText runText : runTexts) {
            if (runText == startRun) {
                inMatch = true;
                continue;
            }
            if (!inMatch) continue;
            if (runText == endRun) {
                setRunText(runText.run(), runText.text().substring(endOffset));
                break;
            }
            setRunText(runText.run(), "");
        }
        return true;
    }

    private void setRunText(XWPFRun run, String text) {
        var ctr = run.getCTR();
        if (ctr.sizeOfTArray() == 0) {
            run.setText(text);
            return;
        }
        ctr.getTArray(0).setStringValue(text);
        while (ctr.sizeOfTArray() > 1) {
            ctr.removeT(1);
        }
    }

    private record RunText(XWPFRun run, String text, int start, int end) {
    }
}
