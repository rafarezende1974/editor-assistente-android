# Editor Assistente Android v0.3.0

## O que mudou na v0.3.0 (só visual — nenhuma função mudou)

Reformulação visual inspirada na linguagem do Descarrega (tela
limpa, cartões, progresso grande, cor por pessoa), sem copiar
marca, nome, logo ou textos dele. Estrutura 01–06, leva
`Cartao_N` só na 01, checklist, relatório, manifest, retomada,
alertas, chave de cópias e pacote continuam exatamente iguais.

- **Cabeçalho do evento:** um cartão no topo com o nome do
  evento, a data e o profissional, e um selo colorido com o nome
  do profissional. A cor é determinística pelo nome, de uma
  paleta fixa de 6 cores — o mesmo profissional sempre com a
  mesma cor. O cabeçalho é atualizado enquanto se digita.
- **Cartões de destino:** origem, "Primário (SD)" e "Cópias
  (unidade externa)" viraram cartões, cada um com o seu botão e,
  no caso dos destinos, com a sua barra de espaço e o texto
  livre/total dentro do cartão (mesma medição da v0.2.0).
- **Progresso grande:** barra de descarga maior, com o
  percentual em destaque ao lado da linha de status
  ("Analisando…", "Copiando…", "Concluído").
- **Tema claro/escuro:** os cartões usam as cores do tema
  Material3 `DayNight`, então seguem o tema do sistema e ficam
  legíveis nos dois — nada de cor fixa clara ou escura.

# Editor Assistente Android v0.2.1

## O que mudou na v0.2.1 (chave mestra das cópias)

- **Cópias em outra unidade passam a ser uma opção selecionável.**
  Nova chave na tela, acima das opções por tipo: "Fazer cópias em
  outra unidade (02, 04, 06) — desmarque para descarregar mais
  rápido e fazer o backup depois".
- **Ligada (padrão):** tudo como antes — cópias 02/04/06 conforme
  as opções por tipo.
- **Desligada:** nenhuma cópia secundária é feita (nem JPG, nem
  vídeo, nem RAW); a descarga vai só para o primário (01, 03, 05)
  e fica mais rápida. As opções por tipo ficam acinzentadas na
  tela. A análise mostra "cópias desligadas — só primário" e o
  cálculo de espaço considera só o primário. A retomada continua
  funcionando nesse modo (um arquivo conta como conferido se o
  primário existir com o mesmo tamanho) e o relatório registra
  que as cópias estavam desligadas nessa descarga.
- A escolha é salva nas preferências e lembrada ao reabrir o app.

# Editor Assistente Android v0.2.0

Versão Android do Editor Assistente, para a Galaxy Tab S9+ (e outros
tablets Android). Mesma lógica da versão Windows v1.5.2, adaptada para o
Android: a origem é o cartão do fotógrafo ligado por leitor USB no hub,
o primário (01, 03, 05) fica no cartão SD do tablet e as cópias
(02, 04, 06) ficam numa unidade externa ligada ao hub.

## O que mudou na v0.2.0 (ideias do Descarrega, adaptadas)

1. **Retomada persistente.** O arquivo `manifest-descarga.json`
   fica na pasta do evento, no primário, com uma entrada por arquivo
   já conferido: profissional, tipo, nome, tamanho e hash SHA-256
   quando o modo hash está ligado (senão, hash nulo). Na análise, um
   arquivo só conta como "já conferido" se a entrada existir no
   manifest E os arquivos de destino (primário e cópia, quando a
   cópia estiver ligada) existirem com o mesmo tamanho — quem decide
   é o HD, nunca só o manifest. Arquivos já conferidos são pulados
   sem recópia e a análise mostra quantos já estão conferidos e
   quantos bytes faltam copiar. Cada arquivo é gravado no manifest
   ao concluir (primário + cópia).
2. **Alerta de arquivo possivelmente corrompido.** Na análise e no
   relatório, com a mensagem "pode estar interrompido — não formate
   o cartão antes de ver". Sinais: tamanho 0; vídeo MP4/MOV sem o
   átomo 'moov' no primeiro nem no último 1 MB; vídeo com menos de
   1 KB; extensão .rsv/.tmp/.part. O arquivo é copiado mesmo assim
   (o alerta nunca pula arquivo) — só é pulado se já estava
   conferido numa descarga anterior. É um alerta, NÃO um laudo:
   só abrir o arquivo diz se ele está bom de verdade. Os arquivos
   .rsv/.tmp/.part são copiados junto com os vídeos.
3. **Contador zerado (C9999 -> C0001).** Na análise, os arquivos são
   agrupados por prefixo e tipo, ordenados por data de modificação,
   e a virada é detectada quando o número cai de >= 9900 para
   <= 100. Os arquivos pós-virada vão para a subpasta
   `ZEROU - A PARTIR DE <prefixo><número>` dentro da pasta de
   destino deles (01 leva, 02, 03, 04, 05 ou 06), sem renomear nada.
   O aviso vai para a análise e para o relatório.
4. **Barra de espaço.** Na análise, a tela mostra espaço livre e
   total e uma barra de progresso do primário (SD) e, quando houver
   pasta de cópias, também da unidade externa, junto com o total a
   copiar. O volume é medido com StorageManager/StatFs pelo
   diretório do volume da pasta escolhida. A falta de medição nunca
   bloqueia a descarga.

## Limitações honestas da v0.2.0

- **Espaço pode não ser mensurável.** Via SAF não há caminho de
  arquivo direto; se o volume não expuser diretório (alguns
  pendrives/OTG, ou Android abaixo do 11), a tela mostra "espaço
  livre: não foi possível medir" em vez de inventar número.
- **Retomada valida existência + tamanho no HD.** O hash do
  manifest, quando existe, fica registrado para auditoria; a
  conferência da retomada não re-hash o cartão inteiro a cada
  análise (seria ler o cartão todo de novo). O manifest é
  reescrito a cada arquivo conferido — em cartões com milhares de
  arquivos isso custa tempo de escrita no SD.
- **Alerta de corrupção não é laudo.** O teste do 'moov' lê só o
  primeiro e o último 1 MB de cada MP4/MOV (a análise fica mais
  lenta com muitos vídeos), e um arquivo pode estar ruim mesmo
  com 'moov' presente — e vice-versa.
- **Contador zerado depende da data de modificação.** Se o
  cartão/SAF não entregar datas (0 para todos), a ordem é a da
  varredura e a virada pode não ser detectada.
- **JPG de leva anterior.** Além do manifest, a descarga também
  não duplica na 01 um JPG já presente numa leva anterior (regra
  da v1.5 do Windows) — a cópia plana em 02 continua garantida.

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
- **Espaço livre no destino (v0.2.0).** A análise tenta medir o
  espaço do SD e da unidade externa; quando não dá para medir, a
  tela avisa em vez de inventar número, e a descarga não é
  bloqueada por isso.
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

Repositório: `rafarezende1974/editor-assistente-android` (criado em
2026-10-29; workflow `.github/workflows/build-apk.yml` criado pela
web colando `BUILD-WORKFLOW.yml.txt`). O artefato é
`editor-assistente-apk`, com `app-debug.apk` dentro. A v0.1.0 foi
validada por ele na Tab S9+ em 2026-10-29 ("deu tudo certo").
APK de debug, sem assinatura de release nesta versão (mesmo ritual
do DX Studio: depois, se quiser, assina-se com a chave DX Labs).
A v0.2.0 ainda não foi subida nem testada: os arquivos locais
estão prontos e aguardam push + build.

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
