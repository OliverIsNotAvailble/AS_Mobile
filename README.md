# Oliver GL — Galeria offline de vídeos e imagens

App Android nativo (Kotlin), tema preto com bordas vermelhas. **Sem acesso à internet e sem permissão ampla para a galeria**.

## Recursos
- Containers importados (`.zip` e `.oliver`) na tela inicial; pastas e subpastas navegáveis **nível a nível**.
- A grade de cada pasta mostra **somente seus arquivos diretos** (não mistura conteúdo de subpastas) e apresenta subpastas separadamente.
- Visualizador fullscreen com swipe para cima/baixo entre as mídias da **mesma pasta**; botão girar 90° no canto inferior direito.
- Vídeos: MP4, WebM, MKV, MOV, M4V, AVI, 3GP, TS (reprodução depende de codecs disponíveis no Android).
- Imagens: PNG, JPG/JPEG, GIF (animado), WebP (inclusive animado no Android 9+), BMP, SVG, HEIF/HEIC e AVIF (quando o Android suportar o formato). **Nem todo codec funciona em todo aparelho**.
- Em vídeo: toque pausa/continua, barra inferior permite buscar uma posição; imagem abre sem barra de vídeo.
- **DEL na tela inicial e em todas as pastas**: selecionar múltiplos containers, pastas e/ou arquivos; confirmar remoção. Pastas são eliminadas recursivamente com o conteúdo, liberando espaço. Não há lixeira. **Mantenha backup dos arquivos importantes.**
- Pacotes de origem na pasta Downloads não são apagados pelo aplicativo, apenas suas cópias extraídas na base.
- `ICON.png` fornece o ícone do aplicativo.

## Importação
Selecione um ZIP comum na pasta Downloads e toque em **IMPORTAR**. Também é possível gerar pacotes .oliver com `tools/create_oliver.py`. O app guarda os arquivos extraídos em `Android/data/com.as.olivergallery/files/base/` (caminho externo privado do aplicativo).

**Exemplo de navegação sem achatar pastas:**
```text
Drama.zip
  drama01/
    Dragão Alado do Fogo Chamuscante/
      epi01/
        cena.mp4
        capa.png
        animação.gif
      epi02/
        cena.webm
```
Tela inicial `Drama` → `drama01` → `Dragão Alado do Fogo Chamuscante` → `epi01` → grade com três arquivos.

## Compilar APK
No GitHub: **Actions → Build Android APK → Artifacts → AS-Mobile-debug**. Extraia `app-debug.apk` do arquivo ZIP. É uma versão de teste (debug) compilada com JDK 17, Android API 35 e Gradle 8.11.1.

## Notas
- GIFs e WebP animados podem consumir memória em coleções enormes; o decoder limita resolução conforme a tela.
- ZIP e .oliver são extraídos; não há reprodução diretamente do arquivo compactado. É necessário espaço livre suficiente.
- Excluir uma pasta do aplicativo remove também mídias ocultas ou arquivos diversos dentro dela.
- Android 11+ restringe acesso externo a Android/data; use o seletor de arquivos do aplicativo.
