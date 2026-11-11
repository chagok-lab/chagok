#!/usr/bin/env python3
"""uiautomator dump(XML)에서 글자를 찾아 누를 좌표를 계산합니다.
사용: ui.py <ui.xml> has <text> | tap <text> | tap_checkable | texts
"""
import re
import sys
import xml.etree.ElementTree as ET


def box(bounds):
    m = re.match(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", bounds or "")
    if not m:
        return None
    x1, y1, x2, y2 = map(int, m.groups())
    return x1, y1, x2, y2


def main():
    if len(sys.argv) < 3:
        return
    path, mode = sys.argv[1], sys.argv[2]
    arg = sys.argv[3] if len(sys.argv) > 3 else ""
    try:
        root = ET.parse(path).getroot()
    except Exception:
        if mode == "has":
            print("no")
        return
    parent = {child: p for p in root.iter() for child in p}
    nodes = [n for n in root.iter("node")]

    def label(n):
        return (n.get("text") or "") + " " + (n.get("content-desc") or "")

    if mode == "has":
        print("yes" if any(arg in label(n) for n in nodes) else "no")
    elif mode == "texts":
        for n in nodes:
            t = label(n).strip()
            if t:
                print(n.get("bounds"), "|", t)
    elif mode == "tap_checkable":
        for n in nodes:
            if n.get("checkable") == "true" and box(n.get("bounds")):
                x1, y1, x2, y2 = box(n.get("bounds"))
                print((x1 + x2) // 2, (y1 + y2) // 2)
                return
    elif mode == "tap":
        best = None
        for n in nodes:
            if arg not in label(n):
                continue
            target, clickable = n, n.get("clickable") == "true"
            p = n
            while not clickable and p in parent:
                p = parent[p]
                if p.tag == "node" and p.get("clickable") == "true":
                    target, clickable = p, True
            b = box(target.get("bounds"))
            if not b:
                continue
            x1, y1, x2, y2 = b
            own = box(n.get("bounds")) or b
            area = (own[2] - own[0]) * (own[3] - own[1])
            cand = (1 if clickable else 0, area, (x1 + x2) // 2, (y1 + y2) // 2)
            if best is None or cand > best:
                best = cand
        if best:
            print(best[2], best[3])


if __name__ == "__main__":
    main()
