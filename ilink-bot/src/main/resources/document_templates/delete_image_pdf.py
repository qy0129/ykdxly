import sys
import os
import fitz

if len(sys.argv) < 3:
    print("[Error] Usage: script.py input.pdf output.pdf [page_or_all]", file=sys.stderr)
    sys.exit(1)

input_path = sys.argv[1]
output_path = sys.argv[2]
target = sys.argv[3] if len(sys.argv) > 3 else "all"  # all|1|2|3...

def delete_images(inp, outp, target_scope):
    doc = fitz.open(inp)
    removed = 0
    
    if target_scope == "all":
        for page in doc:
            images = page.get_images()
            for img in images:
                xref = img[0]
                page.delete_image(xref)
                removed += 1
    elif target_scope.isdigit():
        page_idx = int(target_scope) - 1
        if not 0 <= page_idx < len(doc):
            raise RuntimeError(f"指定页不存在：第 {target_scope} 页")
        page = doc[page_idx]
        images = page.get_images()
        for img in images:
            xref = img[0]
            page.delete_image(xref)
            removed += 1

    if removed == 0:
        raise RuntimeError("指定范围内没有找到可删除的图片")
    
    doc.save(outp)

if __name__ == "__main__":
    try:
        if not os.path.exists(input_path):
            raise FileNotFoundError(f"Input file not found: {input_path}")
        delete_images(input_path, output_path, target)
        if not os.path.exists(output_path) or os.path.getsize(output_path) == 0:
            raise RuntimeError("Output file generation failed")
        sys.exit(0)
    except Exception as e:
        import traceback
        print(f"[Execution Error]: {e}", file=sys.stderr)
        traceback.print_exc(file=sys.stderr)
        sys.exit(1)
