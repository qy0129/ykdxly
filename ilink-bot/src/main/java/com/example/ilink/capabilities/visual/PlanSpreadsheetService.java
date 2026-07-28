package com.example.ilink.capabilities.visual;

import com.example.ilink.capabilities.planning.PlanTask;
import com.example.ilink.capabilities.planning.TaskPlan;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;

/** 将当前计划导出为可筛选和继续编辑的 Excel 表格。 */
public final class PlanSpreadsheetService {

    public byte[] export(TaskPlan plan) {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("计划");
            CellStyle header = headerStyle(workbook);
            String[] columns = {"序号", "任务", "说明", "日期", "预计分钟", "优先级", "状态"};
            Row headerRow = sheet.createRow(0);
            for (int index = 0; index < columns.length; index++) {
                headerRow.createCell(index).setCellValue(columns[index]);
                headerRow.getCell(index).setCellStyle(header);
            }
            for (int index = 0; index < plan.tasks().size(); index++) {
                PlanTask task = plan.tasks().get(index);
                Row row = sheet.createRow(index + 1);
                row.createCell(0).setCellValue(index + 1);
                row.createCell(1).setCellValue(task.title());
                row.createCell(2).setCellValue(task.description());
                row.createCell(3).setCellValue(task.scheduledDate());
                row.createCell(4).setCellValue(task.estimatedMinutes());
                row.createCell(5).setCellValue(priority(task.priority()));
                row.createCell(6).setCellValue("completed".equals(task.status()) ? "已完成" : "待完成");
            }
            sheet.createFreezePane(0, 1);
            sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(
                    0, Math.max(0, plan.tasks().size()), 0, columns.length - 1));
            for (int index = 0; index < columns.length; index++) {
                sheet.autoSizeColumn(index);
                sheet.setColumnWidth(index, Math.min(15_000, sheet.getColumnWidth(index) + 800));
            }
            workbook.write(output);
            return output.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("计划表导出失败", e);
        }
    }

    private CellStyle headerStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setFillForegroundColor(IndexedColors.DARK_GREEN.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        return style;
    }

    private String priority(String value) {
        return switch (value) {
            case "high" -> "高";
            case "low" -> "低";
            default -> "中";
        };
    }
}
