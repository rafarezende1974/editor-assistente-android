# Editor Assistente Android v0.1.0

Versão Android do Editor Assistente, para a Galaxy Tab S9+ (e outros
tablets Android). Mesma lógica da versão Windows v1.5.2, adaptada para o
Android: a origem é o cartão do fotógrafo ligado por leitor USB no hub,
o primário (01, 03, 05) fica no cartão SD do tablet e as cópias
(02, 04, 06) ficam numa unidade externa ligada ao hub.

Pacote: `br.com.dxlabs.editorassistente`
Kotlin + Views/XML, AGP 8.5.2, Kotlin 2.0.20, minSdk 29, targetSdk 34.
Sem Gradle wrapper (o CI usa o Gradle do sistema, como no DX Studio).

## O que a v0.1 faz

- Escolha de pastas pelo Storage Access Framework (SAF), com permissão
  persistente: origem, primário (SD) e cópias (unidade externa).
  Sem a pasta de cópias, as cópias ficam no SD, nos mesmos números
  02, 04 e 06.
- Mesma estrutura do Windows: 01 JPG com leva `Cartao_N` só aqui,
  02 cópia JPG, 03 vídeo, 04 cópia vídeo, 05 RAW, 06 cópia RAW,
  todas com subpasta por profissional; 02 a 06 planas.
- Análise (contagens, total, alerta de par RAW/JPG, próxima leva),
  descarga, relatório `relatorio-descarga.txt` e checklist
  `checklist-descargas.csv` dentro da pasta do evento, no primário.
- Duplicata: mesmo tamanho pula; nome repetido com conteúdo
  diferente ganha sufixo `_1`, `_2`. JPG já presente numa leva
  anterior não entra de novo na 01, mas a cópia plana em 02 é garantida.
- Verificação por tamanho (padrão) ou SHA-256 (opcional, mais lenta).
- Staging `.staging` dentro da pasta do evento, no primário.
- Copia, nunca move. Nunca apaga nem formata o cartão.
  Nunca sobe nada para o Drive.

## Diferenças honestas em relação ao Windows (v0.1)

- **Staging é uma cópia a mais, não um rename.** O SAF não oferece
  mover confiável entre pastas, então o fluxo é origem -> staging ->
  final -> cópia. O editor nunca vê arquivo parcial, ao custo de uma
  cópia extra dentro do primário. Em cartões grandes, isso custa tempo.
- **Sem medição de espaço livre no destino.** A análise mostra o
  total a copiar; o espaço livre no SD e na unidade externa tem de
  ser conferido à mão antes de descarregar.
- **Sem serviço em primeiro plano.** A descarga roda com a tela
  acesa (WakeLock + FLAG_KEEP_SCREEN_ON). Mantenha o app aberto
  durante a descarga; minimizar ou bloquear a tela pode interrompê-la.
  Serviço em primeiro plano fica para uma versão seguinte, se preciso.
- **Sem botão de ejetar do Windows.** O app orienta a ejetar pela
  notificação do sistema ou em Ajustes > Armazenamento.
- **Sem fila de cartões nesta versão.** Um cartão por vez; a fila
  do Windows não foi portada ainda.
- **Sem verificação de espaço por destino e sem "Abrir pasta" nativo:**
  abrir a pasta do evento depende do app Arquivos do tablet.

## Build

Repositório: `rafarezende1974/editor-assistente-android` (a criar).
A integração do Muse não cria repositórios nem arquivos de workflow:
o repositório é criado vazio pela web, o código é subido via push e
o workflow é criado pela web colando `BUILD-WORKFLOW.yml.txt` em
`.github/workflows/build-apk.yml`. O artefato é
`editor-assistente-apk`, com `app-debug.apk` dentro. APK de debug,
sem assinatura de release nesta versão (mesmo ritual do DX Studio:
depois, se quiser, assina-se com a chave DX Labs).

## Teste recomendado na Tab S9+

1. Instale o APK e escolha, nesta ordem: origem (cartão de teste),
   primário (pasta no SD) e cópias (pasta na unidade externa).
2. Teste primeiro com uma pasta-cópia pequena (poucos JPG + 1 RAW +
   1 vídeo), não direto num cartão real cheio.
3. Confira 01 a 06, a leva em 01, o relatório e o checklist, rode
   de novo para ver que só pula, e só então use num evento real.
4. Confira também: permissão persistente depois de fechar e reabrir
   o app, e comportamento com a unidade externa desplugada (o app
   deve avisar que falta a pasta de cópias, não perder a cópia em
   silêncio — nesta v0.1, sem pasta de cópias escolhida, as cópias
   vão para o SD).
