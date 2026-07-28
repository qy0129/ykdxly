package com.example.ilink.capabilities.documents;

import com.example.ilink.bootstrap.Config;
import com.example.ilink.application.conversation.ChatHistoryStore;
import com.example.ilink.capabilities.documents.rag.Retriever;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public final class DocumentAiService {

    private final HttpClient httpClient;
    private final Gson gson = new Gson();
    private final ChatHistoryStore history;
    private final Retriever retriever;

    public DocumentAiService(HttpClient httpClient, ChatHistoryStore history) {
        this(httpClient, history, null);
    }

    public DocumentAiService(HttpClient httpClient, ChatHistoryStore history, Retriever retriever) {
        this.httpClient = httpClient;
        this.history = history;
        this.retriever = retriever;
    }

    public String editViaMarkdown(String fileName, String outputExt, String documentText, String userRequest) {
        String system = """
                你是文档编辑助手。根据用户要求修改文档内容，只输出修改后的 Markdown 格式正文。
                规则：
                1. 只输出纯文本 Markdown，不要任何解释、代码块包裹符或文件名标注。
                2. 保留原文的所有内容，只按用户要求修改。
                3. 使用 Markdown 语法标注结构：`#` 标题、`**粗体**`、`- 列表`、`| 表格 |`。
                4. 输出格式为 %s。
                5. 文件名：%s""".formatted(outputExt, fileName);
        return callLlm(system, "原文内容：\n" + documentText + "\n\n用户要求：\n" + userRequest, 0.1);
    }

    public String generateEditScript(String fileName, String inputExt, String outputExt,
                                     String documentText, String userRequest) {
        return generateEditScript(fileName, inputExt, outputExt, documentText, userRequest, null, null);
    }

    public String generateEditScript(String fileName, String inputExt, String outputExt,
                                     String documentText, String userRequest, String imagePath) {
        return generateEditScript(fileName, inputExt, outputExt, documentText, userRequest, imagePath, null);
    }

    public String generateEditScript(String fileName, String inputExt, String outputExt,
                                     String documentText, String userRequest, String imagePath,
                                     String documentStructure) {
        String system = editScriptPrompt(fileName, inputExt, outputExt, imagePath);
        StringBuilder user = new StringBuilder();
        if (documentStructure != null && !documentStructure.isBlank()) {
            user.append("文档结构（段落编号供定位用）：\n").append(documentStructure).append("\n\n");
        }
        user.append("原文内容：\n").append(documentText).append("\n\n用户要求：\n").append(userRequest);
        return callLlm(system, user.toString(), 0.1);
    }

    /**
     * 解析用户编辑请求为结构化操作（模板模式）。
     * 返回 JSON: {"operation":"insert_image|delete_image|insert_text|delete_text|replace_text|custom","params":{...}}
     */
    public String parseEditOperation(String userRequest, String documentStructure,
                                     String inputExt, boolean hasImage) {
        String system = """
                你是文档编辑意图解析器。根据用户请求判断操作类型并提取参数。
                只输出一行 JSON，不要任何解释。

                可选操作类型：
                - insert_image：用户要求在文档中插入/添加/放入图片
                - delete_image：用户要求删除/移除文档中的图片
                - insert_text：用户要求在文档指定位置新增文字
                - delete_text：用户要求删除某段文字中的全部或任意子串
                - replace_text：用户要求把原文中的全部或任意子串替换为新文字
                - custom：以上都不匹配的复杂操作

                输出格式：
                {"operation":"操作类型","params":{"key":"value"}}

                各操作的 params：
                - PDF insert_image：
                  {"position":"new_page|page_auto|after_content|between_text_image|before_text|after_text|before_image|after_image|coordinates",
                   "page":页码(从1开始),"anchor_text":"作为定位依据的原文片段","occurrence":第几次出现(从1开始),
                   "text_index":文字块序号(从1开始),"image_index":已有图片序号(从1开始),
                   "x":横坐标点数,"y":纵坐标点数,"width":宽度点数,"height":高度点数}
                  "放到第一页" → position="page_auto", page=1
                  "放到第3页‘签字’后面" → position="after_text", page=3, anchor_text="签字"
                  "放在第2页内容后" → position="after_content", page=2
                  "放在第一段文字和证书图片中间" → position="between_text_image", text_index=1, image_index=1
                  PDF 默认 position="new_page"（没有任何位置要求时才新建页）
                - DOCX insert_image：
                  {"position":"end|new_page|page_auto|after_paragraph|before_paragraph|before_text|after_text|before_image|after_image|between_paragraph_image",
                   "page":页码(从1开始),"anchor_text":"作为定位依据的原文片段","occurrence":第几次出现(从1开始),
                   "paragraph_index":段落编号(从0开始),"image_index":已有图片序号(从1开始)}
                  "第一段文字和图片中间" → position="between_paragraph_image", paragraph_index=0, image_index=1
                  "在第2页‘项目负责人’后插图" → position="after_text", page=2, anchor_text="项目负责人"
                  "新建一页插入图片" → position="new_page"
                  DOCX 默认 position="end"（文档末尾）
                - delete_image: {"scope":"all|页码数字"}。数字始终表示页码，不表示第几张图片
                - insert_text：
                  {"position":"before_text|after_text|before_paragraph|after_paragraph|page_start|page_end|coordinates",
                   "page":可选页码,"anchor_text":"定位原文","occurrence":第几次出现,"paragraph_index":可选段落编号,
                   "text":"要插入的文字","x":PDF横坐标,"y":PDF纵坐标}
                - delete_text：
                  {"target_text":"必须原样删除的精确子串","page":可选页码,"occurrence":第几次出现,"scope":"first|all"}
                - replace_text：
                  {"target_text":"必须原样替换的精确子串","new_text":"替换后的文字","page":可选页码,
                   "occurrence":第几次出现,"scope":"first|all"}
                  "删除‘合同编号：ABC-001’中的‘ABC-’" → delete_text, target_text="ABC-"
                  "把第2页第3次出现的‘旧名称’改成‘新名称’" → replace_text, page=2, occurrence=3,
                   target_text="旧名称", new_text="新名称"
                - custom: {"reason":"为什么无法匹配模板"}

                规则：
                - 必须优先使用文档结构中的 [页X-TN]、[页X-IMGN]、[PN] 和 [IMGN @ PN] 锚点。
                - 用户只指定第X页时必须返回 position="page_auto" 和 page=X，绝不能返回文档末尾。
                - 用户引用了一段具体原文时，必须把最短且足以唯一定位的原文放入 anchor_text 或 target_text，禁止只返回段落序号。
                - “删掉这段文字中的一部分”必须使用 delete_text，target_text 只能是用户明确要求删除的那一部分，不能删除整段。
                - 未明确说“全部”时 scope="first"；明确说“所有/全部/每一处”时 scope="all"。
                - PDF 完全未指定插入位置时 position="new_page"
                - DOCX 未指定插入位置时 position="end"
                - DOCX 用户说“新建一页、另起一页、单独一页”时必须返回 position="new_page"
                - 用户说"第3段后面" → position="after_paragraph", paragraph_index=2
                - 用户说"删除所有图片" → scope="all"
                - 用户说"删除第2页的图片" → scope="2"
                - 当前文档格式：%s
                - 有图片可插入：%s
                """.formatted(inputExt, hasImage ? "是" : "否");

        StringBuilder user = new StringBuilder();
        if (documentStructure != null && !documentStructure.isBlank()) {
            user.append("文档结构：\n").append(documentStructure).append("\n\n");
        }
        user.append("用户请求：").append(userRequest);
        return callLlm(system, user.toString(), 0.0);
    }

    public String repairEditScript(String wrongScript, String errorLog, String inputExt, String outputExt,
                                   String fileName, String userRequest) {
        String system = """
                你是 Python 脚本修复专家。根据报错信息修复以下文档编辑脚本。
                规则：
                1. 严格只输出纯 Python 代码，不要 Markdown、注释或解释。
                2. 脚本接受两个命令行参数：sys.argv[1] = 输入路径，sys.argv[2] = 输出路径。
                3. 使用标准代码结构：import sys -> def process(inp, outp) -> if __name__ ... -> try/except -> sys.exit(0/1)
                4. 分析报错原因（API 调用错误、属性不存在、类型不匹配等），修正后输出完整可运行代码。
                5. 禁止导入 os、subprocess、socket、requests、urllib、ctypes，禁止 eval/exec/getattr 和删除、移动文件。
                6. 输入格式：%s，输出格式：%s。文件名：%s"""
                .formatted(inputExt, outputExt, fileName);
        String user = """
                【原始需求】%s

                【失败代码】:
                %s

                【Python 报错堆栈 (Traceback)】:
                %s

                请分析报错原因，修正代码。只输出修复后的纯 Python 代码。"""
                .formatted(userRequest, wrongScript, errorLog);
        return callLlm(system, user, 0.2);
    }

    private String editScriptPrompt(String fileName, String inputExt, String outputExt, String imagePath) {
        String sameFormat = inputExt.equals(outputExt) ? "是" : "否";
        String formatSpecific = formatSpecificGuidance(inputExt, outputExt);
        boolean hasImage = imagePath != null && !imagePath.isBlank();
        String imageSection = hasImage
                ? ("\n图片路径（需插入到文档中，作为 sys.argv[3]）：" + imagePath)
                : "";

        return """
                你是一个专为 Java 后端 ProcessBuilder 调用的自动化文档脚本生成引擎。
                你的任务是根据用户的文档修改需求，生成一段零语法错误、零运行期异常、绝对可执行的 Python 脚本。

                ---

                ### 1. 受限运行环境与命令行契约

                Java 执行器将通过以下命令唤起你的脚本：
                `python edit.py "input.%s" "output.%s"%s`

                你的 Python 代码必须严格遵循以下入口结构，不允许任何偏离：

                ```python
                import sys
                from pathlib import Path

                if len(sys.argv) < 3:
                    print("[Error] Missing input/output arguments", file=sys.stderr)
                    sys.exit(1)

                input_path = sys.argv[1]
                output_path = sys.argv[2]
                %s

                def process_document(inp_path, outp_path):
                    # [核心编辑逻辑]
                    pass

                if __name__ == "__main__":
                    try:
                        if not Path(input_path).is_file():
                            raise FileNotFoundError(f"Input file not found: {input_path}")

                        process_document(input_path, output_path)

                        if not Path(output_path).is_file() or Path(output_path).stat().st_size == 0:
                            raise RuntimeError("Output file generation failed or file is empty")

                        sys.exit(0)
                    except Exception as e:
                        import traceback
                        print(f"[Execution Error]: {e}", file=sys.stderr)
                        traceback.print_exc(file=sys.stderr)
                        sys.exit(1)
                ```

                ### 2. 各格式标准 API 避坑指南（必须遵守）

                %s

                ### 3. 标准代码模版参考（Few-Shot）

                你必须严格参考以下与当前格式对应的完整代码模版，确保导入保护、API 调用、文件保存全部正确：

                %s

                ### 4. 严格防护与约束条件

                - 纯代码输出：仅输出纯 Python 代码。严禁包含 Markdown 代码块包裹符（如 ```python ）、任何解释性文字或系统提示。
                - 禁止网络访问：禁止导入 requests, urllib, socket 等网络库。
                - 禁止主机操作：禁止导入 os、subprocess、ctypes、winreg，禁止 eval、exec、getattr，不得删除或移动任何文件。
                - 完整依赖：不允许出现未定义的变量或缺失的 import 语句。
                - 用户只要求修改文字时，不得删除图片、表格、图表、形状等非文字元素。仅当用户明确要求删除图片/表格时才操作。

                ### 4. 上下文数据输入

                输入文件名：%s
                输入格式：%s
                目标输出格式：%s
                同格式编辑：%s%s

                请直接输出可直接保存为 edit.py 的 Python 代码："""
                .formatted(inputExt, outputExt,
                        hasImage ? " \"image_path\"" : "",
                        hasImage ? "image_path = sys.argv[3]" : "",
                        formatSpecific, fewShotTemplate(inputExt, outputExt), fileName, inputExt, outputExt, sameFormat, imageSection);
    }

    private String formatSpecificGuidance(String inputExt, String outputExt) {
        String ext = inputExt.equals(outputExt) ? inputExt : outputExt;

        StringBuilder sb = new StringBuilder();

        if (inputExt.equals("docx") || outputExt.equals("docx")) {
            sb.append("""
                📄 DOCX 格式 (python-docx)
                - ⚠️ 禁止使用 OxmlElement、lxml、etree 等底层 XML 操作！python-docx 有高级 API，不需要操作 XML。
                - 删除图片/绘制元素：python-docx 无直接 .delete() API。必须遍历 Paragraph 中的 Run，通过 XML 节点移除：
                  for p in doc.paragraphs:
                      for r in p.runs:
                          drawings = r._r.xpath('.//w:drawing')
                          if drawings:
                              r._r.getparent().remove(r._r)
                  表格内的图片同理：遍历 table → row → cell → paragraph → run。
                - 表格修改：优先修改 cell.paragraphs[0].text，避免重新赋值 cell.text 丢失原表格的单元格边框与宽度属性。
                - 插入图片（正确做法，禁止用 XML）：
                  from docx import Document
                  from docx.shared import Inches
                  doc = Document(inp_path)
                  # 末尾插入：
                  doc.add_paragraph().add_run().add_picture(img_path, width=Inches(4))
                  # 指定段落后插入：
                  para = doc.paragraphs[N]  # N 为段落索引
                  new_para = doc.add_paragraph()
                  para._element.addnext(new_para._element)
                  new_para.add_run().add_picture(img_path, width=Inches(4))
                  doc.save(outp_path)
                - 插入分页符（正确做法，禁止用 XML set 属性）：
                  from docx.enum.text import WD_BREAK
                  doc.add_paragraph().add_run().add_break(WD_BREAK.PAGE)
                - 插入图片位置规则：
                  ① 用户指定位置（如"第3段后面"）→ 在 doc.paragraphs[2] 之后插入
                  ② 用户说"文档末尾" → doc.add_paragraph() 后 add_picture
                  ③ 用户未指定位置 → 默认插入到文档末尾
                  ④ 参考"文档结构"中的 [P1][P2]... 编号定位准确段落索引
                - 读取：doc = Document(inp_path) ，保存：doc.save(outp_path)

                """);
        }

        if (inputExt.equals("xlsx") || outputExt.equals("xlsx")) {
            sb.append("""
                📊 XLSX 格式 (openpyxl)
                - 加载原文件：wb = openpyxl.load_workbook(inp_path)
                - 数据格式：只改动 cell.value，不要更改 cell.data_type 或重新初始化样式，防止丢失单元格公式与主题色彩。
                - 保存：wb.save(outp_path)

                """);
        }

        if (inputExt.equals("pptx") || outputExt.equals("pptx")) {
            sb.append("""
                🖼️ PPTX 格式 (python-pptx)
                - 双层遍历：for slide in prs.slides: -> for shape in slide.shapes:
                - 修改文本：检测 shape.has_text_frame，修改 shape.text_frame.text 或遍历 paragraph.runs。
                - 插入图片：slide.shapes.add_picture(img_path, left, top, width, height) （left/top/width/height 为 Emu 或 Inches/Cm）
                - 读取：prs = Presentation(inp_path)，保存：prs.save(outp_path)

                """);
        }

        if (inputExt.equals("pdf") || outputExt.equals("pdf")) {
            sb.append("""
                📕 PDF 格式 (fitz / PyMuPDF)
                - 替换文字：使用 Redaction 遮罩法替代原字，避免版面排版错位：
                  doc = fitz.open(inp_path)
                  for page in doc:
                      rects = page.search_for("原文本")
                      for rect in rects:
                          page.add_redact_annot(rect, fill=(1, 1, 1))
                          page.apply_redactions()
                          page.insert_text(rect.tl, "新文本", fontsize=11, fontname="helv")
                  doc.save(outp_path)
                - 插入图片：
                  # ✅ 正确（全页插入）：
                  page.insert_image(page.rect, filename=img_path)
                  # ✅ 正确（指定区域，x0<x1, y0<y1）：
                  page.insert_image(fitz.Rect(50, 50, 200, 200), filename=img_path)
                  # ❌ 错误：fitz.Rect(0,0,0,0) 宽度/高度为 0 会报 "rect must be finite and not empty"
                  # img_path 为 sys.argv[3]，参数必须是 filename= 或 stream= 关键字参数
                - 插入图片位置规则：
                  ① 用户指定页码 → doc[页码-1].insert_image(...)
                  ② 用户说"最后一页" → doc[-1].insert_image(...)
                  ③ 用户未指定位置 → 在最后一页底部插入（使用 page.rect 的下半部分）
                  ④ 参考"文档结构"中的 [页1][页2]... 定位目标页
                  ⑤ 插入新页放图片：new_page = doc.new_page(); new_page.insert_image(new_page.rect, filename=img_path)
                - ⚠️ Page 对象不可迭代！不要写 if xref in page 或 for x in page
                - 删除/列举图片：for img in page.get_images(): xref = img[0]
                - 删除图片：page.delete_image(xref)  或 doc.delete_image(xref)
                - 删除内容块：page.read_contents(清理后重新 set_contents)
                - 读取：doc = fitz.open(inp_path)，保存：doc.save(outp_path)

                """);
        }

        if (sb.isEmpty()) {
            sb.append("""
                📝 TXT / MD / CSV 格式
                - 必须显式声明 encoding='utf-8'，避免 Windows 环境下触发 GBK 编码导致的 UnicodeDecodeError。
                - 读取：with open(inp_path, 'r', encoding='utf-8') as f: text = f.read()
                - 写入：with open(outp_path, 'w', encoding='utf-8') as f: f.write(text)

                """);
        }

        return sb.toString();
    }

    private String fewShotTemplate(String inputExt, String outputExt) {
        String ext = inputExt.equals(outputExt) ? inputExt : outputExt;
        boolean hasDocx = inputExt.equals("docx") || outputExt.equals("docx");
        boolean hasXlsx = inputExt.equals("xlsx") || outputExt.equals("xlsx");
        boolean hasPptx = inputExt.equals("pptx") || outputExt.equals("pptx");
        boolean hasPdf  = inputExt.equals("pdf")  || outputExt.equals("pdf");
        boolean isText  = !hasDocx && !hasXlsx && !hasPptx && !hasPdf;

        StringBuilder sb = new StringBuilder();

        if (hasDocx) {
            sb.append("""
                📄 DOCX 标准模版：
                ```python
                try:
                    from docx import Document
                    from docx.shared import Inches, Pt, RGBColor, Cm, Emu
                    from docx.enum.text import WD_ALIGN_PARAGRAPH
                    from docx.enum.table import WD_TABLE_ALIGNMENT
                    from docx.oxml.ns import qn
                except ImportError:
                    print("[Error] python-docx not installed", file=sys.stderr)
                    sys.exit(1)

                def modify_docx(inp, outp):
                    doc = Document(inp)
                    # 遍历段落
                    for p in doc.paragraphs:
                        for r in p.runs:
                            if "旧文字" in r.text:
                                r.text = r.text.replace("旧文字", "新文字")
                                r.bold = True
                                r.font.size = Pt(12)
                    # 插入图片
                    # run = doc.paragraphs[0].add_run()
                    # run.add_picture(image_path, width=Inches(4))
                    # 删除图片
                    # for p in doc.paragraphs:
                    #     for r in p.runs:
                    #         for d in list(r._element.findall(qn('w:drawing'))):
                    #             r._element.remove(d)
                    doc.save(outp)
                ```
                """);
        }

        if (hasXlsx) {
            sb.append("""
                📊 XLSX 标准模版：
                ```python
                try:
                    import openpyxl
                    from openpyxl.utils import get_column_letter
                except ImportError:
                    print("[Error] openpyxl not installed", file=sys.stderr)
                    sys.exit(1)

                def modify_xlsx(inp, outp):
                    wb = openpyxl.load_workbook(inp)
                    ws = wb.active
                    for row in ws.iter_rows():
                        for cell in row:
                            if cell.value and "旧" in str(cell.value):
                                cell.value = str(cell.value).replace("旧", "新")
                    wb.save(outp)
                ```
                """);
        }

        if (hasPptx) {
            sb.append("""
                🖼️ PPTX 标准模版：
                ```python
                try:
                    from pptx import Presentation
                    from pptx.util import Inches, Pt, Emu, Cm
                    from pptx.dml.color import RGBColor
                    from pptx.enum.text import PP_ALIGN
                except ImportError:
                    print("[Error] python-pptx not installed", file=sys.stderr)
                    sys.exit(1)

                def modify_pptx(inp, outp):
                    prs = Presentation(inp)
                    for slide in prs.slides:
                        for shape in slide.shapes:
                            if shape.has_text_frame:
                                for p in shape.text_frame.paragraphs:
                                    for r in p.runs:
                                        if "旧" in r.text:
                                            r.text = r.text.replace("旧", "新")
                    # 插入图片
                    # slide = prs.slides[0]
                    # slide.shapes.add_picture(image_path, Inches(1), Inches(1), width=Inches(4))
                    prs.save(outp)
                ```
                """);
        }

        if (hasPdf) {
            sb.append("""
                📕 PDF 标准模版：
                ```python
                try:
                    import fitz
                except ImportError:
                    print("[Error] PyMuPDF not installed", file=sys.stderr)
                    sys.exit(1)

                def modify_pdf(inp, outp):
                    doc = fitz.open(inp)
                    for page in doc:
                        rects = page.search_for("旧文字")
                        for rect in rects:
                            page.add_redact_annot(rect, fill=(1, 1, 1))
                            page.apply_redactions()
                            page.insert_text(rect.tl, "新文字", fontsize=11, fontname="helv")
                    doc.save(outp)
                ```
                """);
        }

        if (isText) {
            sb.append("""
                📝 TXT/MD/CSV 标准模版：
                ```python
                def modify_text(inp, outp):
                    with open(inp, 'r', encoding='utf-8') as f:
                        text = f.read()
                    text = text.replace("旧文字", "新文字")
                    with open(outp, 'w', encoding='utf-8') as f:
                        f.write(text)
                ```
                """);
        }

        return sb.toString();
    }

    private String callLlm(String system, String userContent, double temperature) {
        try {
            JsonObject body = new JsonObject();
            body.addProperty("model", Config.DOCUMENT_MODEL);
            body.addProperty("temperature", temperature);
            body.addProperty("enable_thinking", false);
            JsonArray messages = new JsonArray();

            JsonObject sys = new JsonObject();
            sys.addProperty("role", "system");
            sys.addProperty("content", system);
            messages.add(sys);

            JsonObject user = new JsonObject();
            user.addProperty("role", "user");
            user.addProperty("content", userContent);
            messages.add(user);
            body.add("messages", messages);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(Config.API_BASE_URL))
                    .timeout(Config.DOCUMENT_REQ_TIMEOUT)
                    .header("Authorization", "Bearer " + Config.API_KEY)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                System.err.println("[Document] LLM 调用失败: HTTP " + response.statusCode() + ": " + response.body());
                return null;
            }

            JsonObject responseJson = JsonParser.parseString(response.body()).getAsJsonObject();
            String content = responseJson.getAsJsonArray("choices").get(0).getAsJsonObject()
                    .getAsJsonObject("message").get("content").getAsString();

            content = content.trim();
            if (content.startsWith("```")) {
                int firstLine = content.indexOf('\n');
                int lastFence = content.lastIndexOf("```");
                if (firstLine >= 0 && lastFence > firstLine) {
                    content = content.substring(firstLine + 1, lastFence).trim();
                }
            }
            return content;
        } catch (Exception e) {
            System.err.println("[Document] LLM 调用失败: " + e.getMessage());
            return null;
        }
    }

    public String chatWithDocument(String userId, String userMessage, String fileName, String documentText) {
        try {
            JsonObject body = new JsonObject();
            body.addProperty("model", Config.DOCUMENT_MODEL);
            body.addProperty("temperature", 0.2);
            body.addProperty("enable_thinking", false);
            JsonArray messages = new JsonArray();

            JsonObject system = new JsonObject();
            String systemPrompt = "你是文件助手。必须只根据提供的文件内容回答，不确定的内容要明确说明。"
                    + "回答要准确、分点、保留文件中的关键事实。如果参考了某个来源段落，请在回答末尾标注 [来源：文件名·第X段]。文件名：" + fileName;
            system.addProperty("role", "system");
            system.addProperty("content", systemPrompt);
            messages.add(system);

            String contextText;
            if (retriever != null) {
                try {
                    retriever.indexDocument(userId, fileName, documentText);
                    String ragContext = retriever.buildContext(userId, userMessage, 3);
                    contextText = ragContext.isEmpty() ? documentText : ragContext;
                } catch (Exception e) {
                    System.err.println("[RAG] 检索失败，降级为全文本模式: " + e.getMessage());
                    contextText = documentText;
                }
            } else {
                contextText = documentText.length() > Config.DOCUMENT_MAX_TEXT_CHARS
                        ? documentText.substring(0, Config.DOCUMENT_MAX_TEXT_CHARS)
                        : documentText;
            }

            JsonObject user = new JsonObject();
            user.addProperty("role", "user");
            user.addProperty("content", "文件内容：\n" + contextText + "\n\n用户要求：\n" + userMessage);
            messages.add(user);
            body.add("messages", messages);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(Config.API_BASE_URL))
                    .timeout(Config.DOCUMENT_REQ_TIMEOUT)
                    .header("Authorization", "Bearer " + Config.API_KEY)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                System.err.println("[Document] HTTP " + response.statusCode() + ": " + response.body());
                return null;
            }

            JsonObject responseJson = JsonParser.parseString(response.body()).getAsJsonObject();
            String reply = responseJson.getAsJsonArray("choices").get(0).getAsJsonObject()
                    .getAsJsonObject("message").get("content").getAsString();
            history.add(userId, userMessage, reply);
            return reply;
        } catch (Exception e) {
            System.err.println("[Document] 文件问答失败: " + e.getMessage());
            return null;
        }
    }

    public String generateDocument(String userId, String userMessage) {
        try {
            JsonObject body = new JsonObject();
            body.addProperty("model", Config.DOCUMENT_MODEL);
            body.addProperty("temperature", 0.2);
            body.addProperty("enable_thinking", false);

            JsonArray messages = new JsonArray();
            JsonObject system = new JsonObject();
            system.addProperty("role", "system");
            system.addProperty("content", "你是文档助手。根据用户要求生成可直接写入 DOCX 或 PDF 的完整正文，"
                    + "使用清晰的标题和段落，不要解释生成过程。");
            messages.add(system);

            JsonObject user = new JsonObject();
            user.addProperty("role", "user");
            user.addProperty("content", userMessage);
            messages.add(user);
            body.add("messages", messages);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(Config.API_BASE_URL))
                    .timeout(Config.DOCUMENT_REQ_TIMEOUT)
                    .header("Authorization", "Bearer " + Config.API_KEY)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                System.err.println("[Document] 生成失败: HTTP " + response.statusCode() + ": " + response.body());
                return null;
            }

            JsonObject responseJson = JsonParser.parseString(response.body()).getAsJsonObject();
            return responseJson.getAsJsonArray("choices").get(0).getAsJsonObject()
                    .getAsJsonObject("message").get("content").getAsString();
        } catch (Exception e) {
            System.err.println("[Document] 生成失败: " + e.getMessage());
            return null;
        }
    }
}
