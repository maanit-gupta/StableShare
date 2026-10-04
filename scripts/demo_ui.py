#!/usr/bin/env python3
"""Find nodes in a fresh `uiautomator dump` of the device in ANDROID_SERIAL.

Patterns are regexes matched in full against a node's text or content description.

  demo_ui.py find <pat> [index]   centre "x y" of the index-th match (negative counts from the end)
  demo_ui.py near <anchor> <pat>  centre of the <pat> match closest in y to the first <anchor> match
  demo_ui.py below <anchor> <pat> text of the first <pat> match below the first <anchor> match
  demo_ui.py many <pat>[@index]...  one line per pattern from a single dump: "x y", or "-"
  demo_ui.py list                 every labelled node

Exit status 1 when nothing matches.
"""
import os, re, subprocess, sys, tempfile


def dump():
    subprocess.run(["adb", "shell", "uiautomator", "dump", "/sdcard/demo-ui.xml"],
                   check=True, capture_output=True)
    out = os.path.join(tempfile.gettempdir(), "demo-ui.xml")
    subprocess.run(["adb", "pull", "/sdcard/demo-ui.xml", out], check=True, capture_output=True)
    return open(out, encoding="utf-8").read()


def nodes(xml):
    for m in re.finditer(r"<node [^>]*>", xml):
        n = m.group(0)
        attr = lambda k: (re.search(k + r'="([^"]*)"', n) or [None, ""])[1]
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", attr("bounds")))
        yield attr(" text"), attr("content-desc"), (x1 + x2) // 2, (y1 + y2) // 2, attr("bounds")


def matches(xml, pat):
    p = re.compile(pat)
    return [(t or d, x, y) for t, d, x, y, _ in nodes(xml) if p.fullmatch(t) or p.fullmatch(d)]


def main():
    xml = dump()
    mode = sys.argv[1]
    if mode == "list":
        for t, d, x, y, b in nodes(xml):
            if t or d:
                print(repr(t), repr(d), b)
        return
    if mode == "find":
        hits = matches(xml, sys.argv[2])
        idx = int(sys.argv[3]) if len(sys.argv) > 3 else 0
        if not -len(hits) <= idx < len(hits):
            sys.exit(1)
        print(*hits[idx][1:])
        return
    if mode == "many":
        for arg in sys.argv[2:]:
            pat, _, idx = arg.partition("@")
            hits, idx = matches(xml, pat), int(idx or 0)
            print(" ".join(map(str, hits[idx][1:])) if -len(hits) <= idx < len(hits) else "-")
        return
    anchors, hits = matches(xml, sys.argv[2]), matches(xml, sys.argv[3])
    if not anchors or not hits:
        sys.exit(1)
    ay = anchors[0][2]
    if mode == "near":
        print(*min(hits, key=lambda h: abs(h[2] - ay))[1:])
    elif mode == "below":
        below = [h for h in hits if h[2] > ay]
        if not below:
            sys.exit(1)
        print(min(below, key=lambda h: h[2])[0])


main()
