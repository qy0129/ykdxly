---
name: documents
description: "Use when working on document processing, PDF, Word, Excel, reports, templates. Triggers: 文档, PDF, Word, Excel, 表格, 报告, 简历, 模板, documents, pdf, word, excel, report, template."
---

# Documents Skill

## 本模块职责

处理文档生成、PDF/Word/Excel 操作、模板填充，属于 `capabilities/documents` 目录。

## 负责人

D 成员

## 可用能力

- PDF 文档解析与生成
- Word 文档模板填充
- Excel 数据处理
- 报告自动生成
- 简历模板应用

## 代码位置

```
src/main/java/com/example/ilink/capabilities/documents/
src/test/java/com/example/ilink/capabilities/documents/
```

## 依赖关系

```
adapter → application → documents → platform
                          ↓
                    Apache POI (Word/Excel)
                    PDFBox (PDF)
                    poi-tl (模板引擎)
```

## 模板位置

```
src/main/resources/document_templates/
```

## 开发规范

1. 新增功能优先新增独立类
2. 文档操作要有流式处理（大文件）
3. 模板变量要有转义处理
4. 新增业务逻辑必须增加对应测试
5. 测试使用小型测试文档

## 测试要求

- 测试名称应体现行为，如 `fillTemplateProducesWord()`
- 覆盖正常流程、失败流程和关键边界条件
- 合并前必须通过 `.\mvnw.cmd test`

## 共享热点文件

修改以下文件前必须先沟通：
- `UserRequestHandler.java`
- `IntentRecognizer.java`
