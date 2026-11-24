"""Gera vector drawables do icone adaptive a partir dos SVGs da marca.

Uso: python scripts/gen_android_icon.py <pasta brand/assets>
"""
import re
import sys
from pathlib import Path

assets = Path(sys.argv[1])
res = Path(__file__).resolve().parent.parent / "android/app/src/main/res"
(res / "drawable").mkdir(parents=True, exist_ok=True)
(res / "mipmap-anydpi").mkdir(parents=True, exist_ok=True)
(res / "values").mkdir(parents=True, exist_ok=True)


def vector(svg: str, mono: bool) -> str:
    vb = [float(v) for v in re.search(r'viewBox="([^"]+)"', svg).group(1).split()]
    paths = re.findall(r'<path d="([^"]+)" fill="(#[0-9A-Fa-f]{6})"', svg)
    body = "".join(
        f'        <path android:pathData="{d}" android:fillColor="{"#FF000000" if mono else "#FF" + c[1:]}"/>\n'
        for d, c in paths
    )
    return (
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        f'    android:width="108dp" android:height="108dp"\n'
        f'    android:viewportWidth="{vb[2]:g}" android:viewportHeight="{vb[3]:g}">\n'
        f'    <group android:translateX="{-vb[0]:g}" android:translateY="{-vb[1]:g}">\n{body}    </group>\n</vector>\n'
    )


for name, src, mono in [
    ("ic_launcher_foreground", "android-adaptive-primeiro-plano.svg", False),
    ("ic_launcher_monochrome", "android-adaptive-monocromatico.svg", True),
]:
    (res / "drawable" / f"{name}.xml").write_text(vector((assets / src).read_text(encoding="utf8"), mono), encoding="utf8")

(res / "values/ic_launcher_background.xml").write_text(
    '<resources>\n    <color name="ic_launcher_background">#0F392D</color>\n</resources>\n', encoding="utf8")
(res / "mipmap-anydpi/ic_launcher.xml").write_text(
    '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
    '    <background android:drawable="@color/ic_launcher_background"/>\n'
    '    <foreground android:drawable="@drawable/ic_launcher_foreground"/>\n'
    '    <monochrome android:drawable="@drawable/ic_launcher_monochrome"/>\n'
    '</adaptive-icon>\n', encoding="utf8")
