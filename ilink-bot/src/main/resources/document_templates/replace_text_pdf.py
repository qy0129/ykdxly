import sys
import os
import fitz

if len(sys.argv) < 5:
    print("[Error] Usage: script.py input.pdf output.pdf old_text new_text", file=sys.stderr)
    sys.exit(1)

input_path = sys.argv[1]
output_path = sys.argv[2]
old_text = sys.argv[3]
new_text = sys.argv[4]

def replace_text(inp, outp, old, new):
    doc = fitz.open(inp)
    replaced = 0
    
    for page in doc:
        rects = page.search_for(old)
        for rect in rects:
            page.add_redact_annot(rect, fill=(1, 1, 1))
        page.apply_redactions()
        for rect in rects:
            page.insert_text(rect.tl, new, fontsize=11, fontname="china-s")
            replaced += 1

    if replaced == 0:
        raise RuntimeError(f"Text not found: {old}")
    
    doc.save(outp)

if __name__ == "__main__":
    try:
        if not os.path.exists(input_path):
            raise FileNotFoundError(f"Input file not found: {input_path}")
        replace_text(input_path, output_path, old_text, new_text)
        if not os.path.exists(output_path) or os.path.getsize(output_path) == 0:
            raise RuntimeError("Output file generation failed")
        sys.exit(0)
    except Exception as e:
        import traceback
        print(f"[Execution Error]: {e}", file=sys.stderr)
        traceback.print_exc(file=sys.stderr)
        sys.exit(1)
