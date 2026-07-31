import base64
import json
import os
import sys

import fitz


def load_params(raw):
    padding = "=" * (-len(raw) % 4)
    return json.loads(base64.urlsafe_b64decode(raw + padding).decode("utf-8"))


def selected_pages(doc, params):
    page = params.get("page")
    if isinstance(page, int) and 1 <= page <= len(doc):
        return [doc[page - 1]]
    return list(doc)


def process(input_path, output_path, params):
    doc = fitz.open(input_path)
    action = params.get("action", "replace_text")
    changed = 0

    if action == "insert_text":
        pages = selected_pages(doc, params)
        page = pages[0] if pages else doc[-1]
        point = fitz.Point(float(params.get("x", 50)), float(params.get("y", 80)))
        anchor = params.get("anchor_text", "")
        if anchor:
            matches = page.search_for(anchor)
            if matches:
                point = fitz.Point(matches[0].x0, matches[0].y1 + 14)
        page.insert_text(point, params.get("text", ""), fontsize=11)
        changed = 1
    else:
        target = params.get("target_text", "")
        replacement = "" if action == "delete_text" else params.get("new_text", "")
        scope = params.get("scope", "first")
        occurrence = max(1, int(params.get("occurrence", 1)))
        seen = 0
        for page in selected_pages(doc, params):
            chosen = []
            for rect in page.search_for(target):
                seen += 1
                if scope == "all" or seen == occurrence:
                    chosen.append(rect)
                    if scope != "all":
                        break
            for rect in chosen:
                page.add_redact_annot(rect, fill=(1, 1, 1))
            if chosen:
                page.apply_redactions()
                for rect in chosen:
                    if replacement:
                        page.insert_text(rect.tl, replacement, fontsize=11)
                    changed += 1
            if changed and scope != "all":
                break

    if changed == 0:
        raise RuntimeError("No matching edit position found")
    doc.save(output_path)
    doc.close()


if __name__ == "__main__":
    try:
        if len(sys.argv) < 4 or not os.path.isfile(sys.argv[1]):
            raise RuntimeError("Invalid input or parameters")
        process(sys.argv[1], sys.argv[2], load_params(sys.argv[3]))
        if not os.path.isfile(sys.argv[2]) or os.path.getsize(sys.argv[2]) == 0:
            raise RuntimeError("Output file generation failed")
    except Exception as error:
        print(f"[Execution Error]: {error}", file=sys.stderr)
        sys.exit(1)
