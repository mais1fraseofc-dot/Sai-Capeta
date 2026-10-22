#!/usr/bin/env python3
"""Prepara uma versão estável a partir do projeto Android aprovado."""
from pathlib import Path
import json
import re
import sys

project = Path(sys.argv[1])
version = json.loads(Path("release/version.json").read_text(encoding="utf-8"))
code = int(version["versionCode"])
name = version["versionName"]
if code <= 5 or not re.fullmatch(r"\d+\.\d+\.\d+", name):
    raise SystemExit("Versao estavel invalida.")

gradle = project / "app/build.gradle"
src = gradle.read_text(encoding="utf-8")
old_code = "versionCode 5"
old_name = "versionName '1.3-beta'"
if src.count(old_code) != 1 or src.count(old_name) != 1:
    raise SystemExit("Fonte mudou: verifique a versao antes de assinar.")
src = src.replace(old_code, f"versionCode {code}", 1)
src = src.replace(old_name, f"versionName '{name}'", 1)
gradle.write_text(src, encoding="utf-8")

main = project / "app/src/main/java/br/com/saicapeta/android/MainActivity.java"
ui = main.read_text(encoding="utf-8")
ui = ui.replace("Sai, Capeta! • versão 1.3 beta", f"Sai, Capeta! • versão {name}", 1)
main.write_text(ui, encoding="utf-8")
print(f"Projeto preparado: {name}, codigo {code}.")
