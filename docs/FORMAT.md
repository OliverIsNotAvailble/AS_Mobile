# Formato .oliver V1

O `.oliver` é um arquivo **ZIP padrão** (compatível com ZIP64). Não é obrigatório usar o script: um ZIP normal também funciona.

```text
Drama.oliver
  manifest.json
  folders/
    drama01/
      Dragão Alado do Fogo Chamuscante/
        epi01/
          cena.mp4
          imagem.png
          animação.gif
        epi02/
          clipe.webm
```

O diretório `folders/` é apenas um envelope interno do formato. O aplicativo abre o container direto em `drama01`, e **nunca mistura os arquivos de níveis diferentes**.

```json
{
  "format": "AS_OLIVER_V1",
  "version": 1,
  "name": "Drama",
  "media_count": 4,
  "video_count": 2,
  "image_count": 2
}
```

O nome do arquivo vira a pasta física em `base/`, e o campo `name` é o título exibido. Se o nome já existir, o aplicativo adiciona `(2)`, `(3)` etc.

Formatos aceitos pela galeria: MP4/M4V/MOV/WebM/MKV/AVI/TS/3GP, PNG/JPEG/GIF/WebP/BMP/HEIC/HEIF/AVIF/SVG. Decodificação e animação dependem da versão do Android e dos codecs do aparelho.

O aplicativo extrai o pacote para `Android/data/com.as.olivergallery/files/base/`. Após importação, o arquivo ZIP original pode ser guardado como backup. Excluir pasta ou container dentro do aplicativo remove recursivamente o conteúdo extraído, mas não altera o ZIP original.

## Segurança

O importador rejeita caminhos `../`, caminhos absolutos, entradas que escapem da pasta de destino, mais de 100.000 entradas e mais de 20 GiB descompactados. Não importe arquivos de origem não confiável.
