"""
Oliver GL - Empacota pastas, imagens e vídeos em um arquivo .oliver (ZIP).
Edite os caminhos no topo e execute com Python 3.10+; sem CLI e sem GUI.
"""
from __future__ import annotations

import json
import zipfile
from pathlib import Path

# ============== EDITE AQUI ==============
INPUT_FOLDER = r"C:\Users\Alexsander Oliveira\Downloads\0\IN"
OUTPUT_DIR = r"C:\Users\Alexsander Oliveira\Downloads\0\OUT"
CONTAINER_NAME = ""  # vazio usa o nome da pasta INPUT_FOLDER
# ========================================

VIDEO_EXTENSIONS = {".mp4", ".m4v", ".mov", ".webm", ".mkv", ".avi", ".3gp", ".ts"}
IMAGE_EXTENSIONS = {".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp",
                    ".heic", ".heif", ".avif", ".svg"}
SUPPORTED = VIDEO_EXTENSIONS | IMAGE_EXTENSIONS


def main() -> None:
    root = Path(INPUT_FOLDER).expanduser().resolve()
    output = Path(OUTPUT_DIR).expanduser().resolve()
    if not root.is_dir():
        raise FileNotFoundError(f"Pasta não encontrada: {root}")
    output.mkdir(parents=True, exist_ok=True)

    name = (CONTAINER_NAME.strip() or root.name).strip()
    if not name or any(c in name for c in r'\/:*?"<>|'):
        raise ValueError("Nome do container inválido.")
    target = output / f"{name}.oliver"
    if target.exists():
        raise FileExistsError(f"Não vou sobrescrever: {target}")

    media = sorted(
        (p for p in root.rglob("*") if p.is_file() and p.suffix.lower() in SUPPORTED),
        key=lambda p: str(p.relative_to(root)).casefold(),
    )
    if not media:
        raise ValueError("Nenhuma imagem ou vídeo compatível na pasta de entrada.")

    manifest = {
        "format": "AS_OLIVER_V1",
        "version": 1,
        "name": name,
        "media_count": len(media),
        "video_count": sum(p.suffix.lower() in VIDEO_EXTENSIONS for p in media),
        "image_count": sum(p.suffix.lower() in IMAGE_EXTENSIONS for p in media),
    }
    # ZIP_STORED evita recompressão de MP4/JPEG/PNG e mantém ZIP64.
    with zipfile.ZipFile(target, "w", compression=zipfile.ZIP_STORED,
                         allowZip64=True) as archive:
        archive.writestr("manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2))
        # Preserva todos os níveis, inclusive diretórios vazios; nenhum layer é achatado.
        for directory in sorted(
                (p for p in root.rglob("*") if p.is_dir()),
                key=lambda p: str(p.relative_to(root)).casefold()):
            archive.writestr("folders/" + directory.relative_to(root).as_posix() + "/", b"")
        for index, path in enumerate(media, 1):
            relative = path.relative_to(root)
            print(f"[{index}/{len(media)}] {relative}")
            archive.write(path, arcname="folders/" + relative.as_posix())

    print(f"\nOK: {target}")
    print(f"Mídias: {len(media)} | Tamanho: {target.stat().st_size / 1024**2:.1f} MiB")
    print("Copie o .oliver para o Android e use IMPORTAR no Oliver GL.")


if __name__ == "__main__":
    main()
