"""Falha se as cores do Kotlin divergirem de brand/tokens.json.

Uso: python scripts/check_tokens.py <tokens.json> <MalhaVivaColors.kt>
"""
import json
import re
import sys

tokens = json.load(open(sys.argv[1], encoding="utf8"))["color"]
kt = open(sys.argv[2], encoding="utf8").read()
blocks = {"light": kt.split("DarkMalhaVivaColors")[0], "dark": kt.split("DarkMalhaVivaColors")[1]}
bad = []
for theme, block in blocks.items():
    for key, val in tokens[theme].items():
        pairs = val.items() if isinstance(val, dict) else [(key, val)]
        for k, hexv in pairs:
            m = re.search(rf"\b{k}\s*=\s*Color\(0xFF([0-9A-Fa-f]{{6}})\)", block)
            if not m or m.group(1).upper() != hexv[1:].upper():
                bad.append(f"{theme}.{k}: json {hexv} kotlin {m.group(1) if m else 'ausente'}")
print("\n".join(bad) or "tokens ok")
sys.exit(1 if bad else 0)
