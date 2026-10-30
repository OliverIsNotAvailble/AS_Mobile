# Oliver GL — Galeria offline de vídeos

App Android nativo (Kotlin) para organizar vídeos em **containers → pastas → grade de prévias → player vertical**.

## Funcionalidades da V1
- O aplicativo aparece como **Oliver GL**, usando o ícone original de `ICON.png`.
- Tema preto, cartões escuros com bordas vermelhas.
- Tela inicial com containers importados e botões **IMPORTAR** / **ATUALIZAR**.
- Pastas por container, grade de prévias geradas a partir dos vídeos e player fullscreen.
- Player sem ícones sobre o vídeo: **toque para pausar/continuar**, **deslize para cima = próximo**, **para baixo = anterior**.
- Barra de progresso permanente na parte inferior (arraste para avançar/retroceder).
- Na grade de vídeos: **DEL → selecionar → EXCLUIR → confirmar** remove os arquivos extraídos da base do app, sem apagar os pacotes `.oliver` originais.
- Botão/gesto **Voltar do Android** retorna à grade. As telas de containers/pastas mantêm os botões de navegação.
- Importar arquivos `.oliver` (ZIP com manifest) ou `.zip` pela seleção de arquivos do Android.
- Armazenamento local do app: `Android/data/com.as.olivergallery/files/base/` (pode variar conforme o armazenamento do aparelho).
- Totalmente **offline**: não pede permissão ampla para acessar a galeria do telefone.

## Criar um container no Windows
Abra `tools/create_oliver.py`, altere `INPUT_FOLDER` e `OUTPUT_DIR` no topo e execute com Python 3.10+.

Exemplo:
```text
MinhaColecao/
  Personagem A/
    01.mp4
    02.webm
  Personagem B/
    01.mp4
```

O script gera `MinhaColecao.oliver` com `manifest.json` e `folders/Personagem A/...` etc. Copie para Downloads no celular e toque em **IMPORTAR**. O app extrai o pacote para sua base (precisa de espaço suficiente).

## Gerar o APK sem instalar Android Studio
No GitHub, abra **Actions → Build Android APK → execução → Artifacts → AS-Mobile-debug**. Dentro do ZIP baixado há `app-debug.apk`, pronto para instalar manualmente (habilite instalação de fontes externas para seu navegador/gerenciador).

O workflow roda automaticamente a cada push na branch `main`; também aceita execução manual. É uma build *debug* de teste, não um APK assinado para a Play Store.

## Compilar localmente
Requer JDK 17, Android SDK API 35 e Gradle 8.11.1. Abra no Android Studio ou rode `gradle assembleDebug`. O projeto usa Android Gradle Plugin 8.9.2 e Media3 1.7.1.

## Observações
- Compatibilidade real de vídeo depende dos codecs suportados pelo Android; MP4/H.264 costuma ser a opção mais confiável.
- Pacotes importados são **extraídos**; não há reprodução diretamente de dentro do ZIP.
- Android 11+ limita o acesso direto a `Android/data` por gerenciadores de arquivo; a importação pelo seletor do app evita isso.
- Versão inicial focada no fluxo básico e em testes em aparelho real.
