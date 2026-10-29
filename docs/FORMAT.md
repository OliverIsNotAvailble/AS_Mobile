# Formato .oliver V1

Um pacote \`.oliver\` é um **arquivo ZIP padrão** (compatível com ZIP64) sem criptografia.

Estrutura:

\`\`\`text
Galeria.oliver
  manifest.json
  folders/
    Nome da Pasta/
      01.mp4
      02.webm
    Outra Pasta/
      video.mp4
\`\`\`

Manifest de exemplo:

\`\`\`json
{
  "format": "AS_OLIVER_V1",
  "version": 1,
  "name": "Minha Galeria",
  "video_count": 3
}
\`\`\`

O app usa o nome do arquivo como destino físico (ex.: \`Galeria.oliver\` vira \`base/Galeria/\`) e o campo \`name\` para exibição. Nome repetido cria sufixo \`(2)\`, \`(3)\` etc.

ZIP comum também é aceito, mas a forma recomendada é o formato com \`folders/\`. Um pacote é **extraído** para a pasta privada externa do app; é necessário ter espaço livre para os vídeos extraídos. O pacote de origem não é apagado.

## Segurança

O importador rejeita \`../\`, caminhos absolutos, entradas que escapem do destino e mais de 100 mil entradas; interrompe a extração quando a soma ultrapassa 20 GiB. Não execute arquivos do pacote. O formato é feito para **vídeos de confiança** gerados pelo seu próprio script.
