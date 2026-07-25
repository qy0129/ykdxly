import ast
import base64
import json
import os
import sys

import fitz


if len(sys.argv) < 4:
    print("[Error] Usage: script.py input.pdf output.pdf image.png [params_json]", file=sys.stderr)
    sys.exit(1)

input_path = sys.argv[1]
output_path = sys.argv[2]
image_path = sys.argv[3]

def load_params(raw):
    if not raw:
        return {"position": "new_page"}
    if raw.lstrip().startswith("{"):
        try:
            return json.loads(raw)
        except json.JSONDecodeError:
            try:
                value = ast.literal_eval(raw)
                if isinstance(value, dict):
                    return value
            except (SyntaxError, ValueError):
                pass
            if "=" in raw:
                result = {}
                for item in raw.strip()[1:-1].split(","):
                    key, value = item.split("=", 1)
                    value = value.strip()
                    result[key.strip()] = int(value) if value.isdigit() else value
                return result
            raise
    padded = raw + "=" * (-len(raw) % 4)
    decoded = base64.urlsafe_b64decode(padded).decode("utf-8")
    return json.loads(decoded)


params = load_params(sys.argv[4]) if len(sys.argv) > 4 else {"position": "new_page"}

MARGIN = 30
MIN_GAP_HEIGHT = 72


def text_rects(page):
    rects = []
    for block in page.get_text("blocks"):
        if len(block) >= 7 and block[6] == 0 and str(block[4]).strip():
            rects.append(fitz.Rect(block[:4]))
    return sorted(rects, key=lambda rect: (rect.y0, rect.x0))


def image_rects(page):
    rects = []
    seen = set()
    for image in page.get_images(full=True):
        for rect in page.get_image_rects(image[0]):
            key = tuple(round(value, 2) for value in (rect.x0, rect.y0, rect.x1, rect.y1))
            if key not in seen:
                seen.add(key)
                rects.append(rect)
    return sorted(rects, key=lambda rect: (rect.y0, rect.x0))


def occupied_rects(page):
    return sorted(text_rects(page) + image_rects(page), key=lambda rect: (rect.y0, rect.x0))


def vertical_gaps(page):
    top = MARGIN
    bottom = page.rect.height - MARGIN
    intervals = []
    for rect in occupied_rects(page):
        y0 = max(top, rect.y0 - 6)
        y1 = min(bottom, rect.y1 + 6)
        if y1 > y0:
            intervals.append((y0, y1))
    intervals.sort()

    merged = []
    for start, end in intervals:
        if merged and start <= merged[-1][1]:
            merged[-1] = (merged[-1][0], max(merged[-1][1], end))
        else:
            merged.append((start, end))

    gaps = []
    cursor = top
    for start, end in merged:
        if start > cursor:
            gaps.append((cursor, start))
        cursor = max(cursor, end)
    if cursor < bottom:
        gaps.append((cursor, bottom))
    return gaps


def insert_in_vertical_gap(page, image, start, end):
    if end - start < MIN_GAP_HEIGHT:
        return False
    target = fitz.Rect(MARGIN, start, page.rect.width - MARGIN, end)
    page.insert_image(target, filename=image, keep_proportion=True, overlay=True)
    return True


def append_to_document_end(doc, image, warning):
    page = doc.new_page(pno=len(doc))
    target = fitz.Rect(50, 50, page.rect.width - 50, page.rect.height - 50)
    page.insert_image(target, filename=image, keep_proportion=True, overlay=True)
    print(f"[Warning] {warning}，图片已追加到文档末尾的新页面。")


def requested_page(doc, values):
    page_number = values.get("page")
    if page_number is None:
        return None
    try:
        page_index = int(page_number) - 1
    except (TypeError, ValueError):
        return None
    return page_index if 0 <= page_index < len(doc) else -2


def gap_after_anchor(page, anchor):
    next_top = page.rect.height - MARGIN
    for rect in occupied_rects(page):
        if rect.y0 >= anchor.y1 - 0.5 and rect != anchor:
            next_top = min(next_top, rect.y0 - 6)
    return anchor.y1 + 6, next_top


def gap_before_anchor(page, anchor):
    previous_bottom = MARGIN
    for rect in occupied_rects(page):
        if rect.y1 <= anchor.y0 + 0.5 and rect != anchor:
            previous_bottom = max(previous_bottom, rect.y1 + 6)
    return previous_bottom, anchor.y0 - 6


def find_text_anchor(doc, values, page_index):
    anchor_text = values.get("anchor_text")
    if not anchor_text:
        return None
    remaining = max(1, int(values.get("occurrence", 1)))
    candidates = [page_index] if page_index is not None else list(range(len(doc)))
    for index in candidates:
        rects = doc[index].search_for(anchor_text)
        if remaining <= len(rects):
            return index, rects[remaining - 1]
        remaining -= len(rects)
    return None


def insert_at_coordinates(page, image, values):
    x = float(values.get("x", MARGIN))
    y = float(values.get("y", MARGIN))
    width = float(values.get("width", 240))
    height = float(values.get("height", 180))
    target = fitz.Rect(x, y, x + width, y + height) & page.rect
    if target.width < 10 or target.height < 10:
        return False
    for occupied in occupied_rects(page):
        if target.intersects(occupied):
            return False
    page.insert_image(target, filename=image, keep_proportion=True, overlay=True)
    return True


def insert_between_text_and_image(page, image, text_index, image_index):
    texts = text_rects(page)
    images = image_rects(page)
    if not (1 <= text_index <= len(texts) and 1 <= image_index <= len(images)):
        return False
    text_rect = texts[text_index - 1]
    existing_image = images[image_index - 1]
    if text_rect.y1 <= existing_image.y0:
        return insert_in_vertical_gap(page, image, text_rect.y1 + 6, existing_image.y0 - 6)
    if existing_image.y1 <= text_rect.y0:
        return insert_in_vertical_gap(page, image, existing_image.y1 + 6, text_rect.y0 - 6)
    return False


def insert_image(inp, outp, image, values):
    doc = fitz.open(inp)
    position = values.get("position", "new_page")
    page_index = requested_page(doc, values)

    if page_index == -2:
        append_to_document_end(doc, image, "指定页不存在")
    elif position == "new_page":
        insert_at = page_index + 1 if page_index is not None else len(doc)
        page = doc.new_page(pno=insert_at)
        target = fitz.Rect(50, 50, page.rect.width - 50, page.rect.height - 50)
        page.insert_image(target, filename=image, keep_proportion=True, overlay=True)
    elif position == "page_auto":
        target_index = page_index if page_index is not None else len(doc) - 1
        page = doc[target_index]
        gaps = vertical_gaps(page)
        largest = max(gaps, key=lambda gap: gap[1] - gap[0], default=None)
        if largest is None or not insert_in_vertical_gap(page, image, *largest):
            append_to_document_end(doc, image, "你指定的页面空间不足")
    elif position == "coordinates":
        target_index = page_index if page_index is not None else 0
        page = doc[target_index]
        if not insert_at_coordinates(page, image, values):
            append_to_document_end(doc, image, "你指定的坐标越界或与现有内容重叠")
    elif position == "after_content":
        target_index = page_index if page_index is not None else len(doc) - 1
        page = doc[target_index]
        content_bottom = max((rect.y1 for rect in occupied_rects(page)), default=MARGIN)
        if not insert_in_vertical_gap(page, image, content_bottom + 8, page.rect.height - MARGIN):
            append_to_document_end(doc, image, "你指定的位置空间不足")
    elif position == "between_text_image":
        text_index = int(values.get("text_index", 1))
        image_index = int(values.get("image_index", 1))
        candidates = [page_index] if page_index is not None else list(range(len(doc)))
        inserted = any(
            insert_between_text_and_image(doc[index], image, text_index, image_index)
            for index in candidates
        )
        if not inserted:
            append_to_document_end(doc, image, "你指定的文字和图片之间空间不足或未找到对应内容")
    elif position in {"before_text", "after_text", "before_image", "after_image"}:
        text_anchor = find_text_anchor(doc, values, page_index) \
            if position in {"before_text", "after_text"} else None
        target_index = text_anchor[0] if text_anchor is not None \
            else (page_index if page_index is not None else 0)
        page = doc[target_index]
        if position in {"before_text", "after_text"}:
            if text_anchor is not None:
                anchor = text_anchor[1]
                gap = gap_before_anchor(page, anchor) if position == "before_text" \
                    else gap_after_anchor(page, anchor)
            else:
                index = int(values.get("text_index", 1))
                anchors = text_rects(page)
                if 1 <= index <= len(anchors):
                    gap = gap_before_anchor(page, anchors[index - 1]) if position == "before_text" \
                        else gap_after_anchor(page, anchors[index - 1])
                else:
                    gap = None
        else:
            index = int(values.get("image_index", 1))
            anchors = image_rects(page)
            if 1 <= index <= len(anchors):
                gap = gap_before_anchor(page, anchors[index - 1]) if position == "before_image" \
                    else gap_after_anchor(page, anchors[index - 1])
            else:
                gap = None
        if gap is None or not insert_in_vertical_gap(page, image, *gap):
            append_to_document_end(doc, image, "你指定的锚点位置空间不足或未找到对应内容")
    else:
        append_to_document_end(doc, image, "无法识别具体插入位置")

    doc.save(outp)


if __name__ == "__main__":
    try:
        if not os.path.exists(input_path):
            raise FileNotFoundError(f"Input file not found: {input_path}")
        if not os.path.exists(image_path):
            raise FileNotFoundError(f"Image file not found: {image_path}")
        insert_image(input_path, output_path, image_path, params)
        if not os.path.exists(output_path) or os.path.getsize(output_path) == 0:
            raise RuntimeError("Output file generation failed")
        sys.exit(0)
    except Exception as exc:
        import traceback
        print(f"[Execution Error]: {exc}", file=sys.stderr)
        traceback.print_exc(file=sys.stderr)
        sys.exit(1)
