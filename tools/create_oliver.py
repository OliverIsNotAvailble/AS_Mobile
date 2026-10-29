"""
AS Mobile - Cria um container .oliver (ZIP) contendo as subpastas e vídeos.
Sem interface/CLI: edite os caminhos aqui embaixo e execute com Python 3.10+.
"""
from __future__ import annotations

import json
import zipfile
from pathlib import Path

# ============ EDITE AQUI ============
INPUT_FOLDER = r"C:\Users\Alexsander Oliveira\Downloads\0\IN"
OUTPUT_DIR = r"C:\Users\Alexsander Oliveira\Downloads\0\OUT"
CONTAINER_NAME = ""  # vazio = nome da pasta INPUT_FOLDER
# ===================================

VIDEO_EXTENSIONS = {".mp4", ".m4v", ".mov", ".webm", ".mkv", ".avi", ".3gp", ".ts"}


def main() -> None:
    root = Path(INPUT_FOLDER).expanduser().resolve()
    output = Path(OUTPUT_DIR).expanduser().resolve()

    if not root.is_dir():
        raise FileNotFoundError(f"Pasta de entrada não encontrada: {root}")
    output.mkdir(parents=True, exist_ok=True)

    name = (CONTAINER_NAME.strip() or root.name).strip()
    if not name or any(c in name for c in r'\/:*?"<>|'):
        raise ValueError("Nome do container inválido.")
    target = output / f"{name}.oliver"

    videos = sorted(
        (p for p in root.rglob("*") if p.is_file() and p.suffix.lower() in VIDEO_EXTENSIONS),
        key=lambda p: str(p.relative_to(root)).lower(),
    )
    if not videos:
        raise ValueError("Nenhum vídeo reconhecido na pasta de entrada.")

    if target.exists():
        raise FileExistsError(f"O arquivo já existe, não vou sobrescrever: {target}")

    manifest = {
        "format": "AS_OLIVER_V1",
        "version": 1,
        "name": name,
        "video_count": len(videos),
    }

    # Vídeos já são comprimidos; ZIP_STORED evita tempo perdido recomprimindo.
    with zipfile.ZipFile(target, "w", compression=zipfile.ZIP_STORED,
                         allowZip64=True) as archive:
        archive.writestr("manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2))
        for number, path in enumerate(videos, 1):
            relative = path.relative_to(root)
            if len(relative.parts) == 1:
                inner_path = Path("folders") / "Vídeos" / relative
            else:
                inner_path = Path("folders") / relative
            print(f"[{number}/{len(videos)}] {relative}")
            archive.write(path, arcname=inner_path.as_posix())

    print(f"\nOK: {target}")
    print(f"Vídeos: {len(videos)} | Tamanho: {target.stat().st_size / 1024**2:.1f} MiB")
    print("Copie o .oliver para Downloads do Android e importe pelo app.")


if __name__ == "__main__":
    main()
