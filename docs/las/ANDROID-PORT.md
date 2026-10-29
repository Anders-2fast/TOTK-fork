# Plano de porte: LAS Recomp para Android

Alvo inicial: **Snapdragon (Adreno 6xx / 7xx) com o driver Vulkan Turnip** (Mesa), carregado pelo app.
Outros SoCs (Mali, Xclipse, Adreno com o driver da Qualcomm) ficam para depois da Fase 3.

## Ponto de partida

O que já existe e ajuda:

- **O Switch é AArch64, e o Android também.** O código gerado usa o caminho `--simd asm` (FP/SIMD do jogo como inline
  asm nativo). É o mesmo caminho do macOS Apple Silicon, que já está provado. O macOS também reserva o `x18`, como o
  Android (ShadowCallStack), então o código gerado já não deve usá-lo.
- **O backend Vulkan** (`runtime/nvn/vulkan/`) e o tradutor de shaders Maxwell → GLSL → SPIR-V já existem.
  `docs/linux.md` cita o Turnip entre os drivers suportados no Linux.
- **A camada `runtime/platform/linux.c`** já suporta aarch64. Ela é a base do `platform/android.c`.
- **SDL3 e Dear ImGui** têm suporte oficial a Android.

O que bloqueia ou é diferente no Android:

| # | Problema | Onde | Gravidade |
|---|---|---|---|
| 1 | Janela de memória fixa `0x70_0000_0000`–`0x73_2000_0000` (guest = host). Num processo forkado do zygote, com VA de 39 bits, essa faixa fica onde ART e bibliotecas já estão mapeadas. Se houver colisão, não dá para fazer re-exec. | `rt.h`, `main.c` | **Alta** |
| 2 | O código gerado é compilado no aparelho (Python `gen.py` + Zig). No Android não há Python, e o SELinux bloqueia `exec()` de binários na pasta de dados do app (targetSdk ≥ 29). | `gamebuild.c` | Alta |
| 3 | O gerador de seeds é um helper em PyInstaller (oead, evfl, keystone) e não roda no Android. | `rando_tab.mm` | Média (o MVP pode ser só vanilla) |
| 4 | O `lasgame.so` resolve os símbolos do runtime no executável (`-rdynamic`). No Android, o runtime é `libmain.so`, carregado num namespace do linker e fora do escopo global. | `Makefile`, `gamelib.c` | Média (resolve no link) |
| 5 | Diferenças do Vulkan móvel: `VK_EXT_external_memory_host`, texturas BC, `shaderInt64`, `dualSrcBlend`, `MAILBOX` | `vulkan/device.c`, `exec.c`, `textures.c` | Média, a medir |
| 6 | Bionic: sem `execinfo.h`/`backtrace()` antes da API 33, `/proc/self/exe` aponta para `app_process64`, e o ART usa `SIGSEGV` (sigchain) | `platform/linux.c` | Baixa |
| 7 | Ciclo de vida: pause/resume, surface destruída e recriada, áudio em segundo plano. Hoje nada disso é tratado. | `main.c`, `vulkan/device.c`, `audio.c` | Média |
| 8 | Entrada por toque: hoje só há teclado e controle | `hle/input.c`, `ui/` | Média |
| 9 | Arquivos: seletor de pasta vira SAF (`content://`, sem caminho POSIX). O `curl` da checagem de update não existe. | `launcher.mm` | Baixa |

## Decisões de arquitetura (propostas)

- **App:** projeto Gradle a partir do `android-project` do SDL3 (`SDLActivity`), runtime em `libmain.so` (NDK, clang),
  ABI só `arm64-v8a`, `minSdk 30`, orientação paisagem. Linkar com `-Wl,-z,max-page-size=16384` (páginas de 16 KB,
  Android 15+). `PLAT_PAGE` já é 16K.
- **Driver:** [libadrenotools](https://github.com/bylaws/libadrenotools) carrega um `libvulkan_freedreno.so`
  (Turnip) a partir da pasta do app, como fazem Skyline, Yuzu e derivados. Fallback: driver do sistema (Qualcomm).
  O usuário pode trocar a versão do Turnip, porque isso vai ser necessário.
- **Código do jogo (MVP):** gerado e compilado **no PC** (`make gen` com `--simd asm` + NDK clang → `lasgame.so`) e
  copiado para o aparelho (adb ou import). Nada do jogo vai no APK. Gerar no próprio aparelho fica para a Fase 6.
- **`lasgame.so`:** linkado com `DT_NEEDED libmain.so` (o linker do Android resolve os símbolos do runtime pelas
  dependências da própria lib) e carregado com `dlopen` da pasta interna do app. `dlopen` de app data é permitido,
  só `exec()` é bloqueado.
- **Randomizer (MVP):** importar pastas de seed geradas no desktop. Gerar seeds no aparelho fica para a Fase 7.
- **Plataforma:** `runtime/platform/android.c`, partindo do `linux.c`. `PLATFORM=android` no Makefile para os
  objetos e o CMake do Gradle chamando a mesma lista de fontes (ou um `Android.mk`/CMake próprio, ver Fase 1).
- **Escopo:** este fork é só para Android. As outras plataformas não mudam de comportamento: toda mudança em código
  compartilhado fica atrás de `__ANDROID__` ou de uma flag que só o build Android passa, e o desktop compila
  exatamente como antes. Isso também reduz o diff em arquivos compartilhados e facilita trazer updates do upstream
  (peiikai/LASRecomp).

## Janela de memória dinâmica (só Android)

**Estado (2026-10-15): implementada.**
- Validações do gerador:
  - sem a flag, a saída é idêntica à anterior (716 arquivos);
  - com `--dyn-base`, nenhum endereço absoluto restante (só 6 máscaras de bitfield `BFM`, que estão corretas);
  - o C gerado compila com o NDK e acessa `lasgame_gb` por `ADRP`+`LDR`, sem GOT.
- Runtime: a base é escolhida pelo `plat_reserve_guest` do Android. O que o boot do jogo usa (deslocamento das
  tabelas no `dispatch_init`, aviso da base pelo `gamelib.c`) só será testado na Fase 2.

No desktop, a janela guest continua fixa em `0x70_0000_0000`. No Android, a base é escolhida em tempo de execução.

O jogo é PIC: o Switch carrega os executáveis com ASLR, e o `loader.c` já aplica as relocações do NSO para qualquer
base. Quem fixa os endereços é o recompilador, ao gravá-los como constantes no C gerado. O modelo "guest = host"
continua: loads e stores não mudam, só onde a janela começa.

- **Escolha da base** (`platform/android.c`): ler `/proc/self/maps`, achar um buraco de `GUEST_HI - GUEST_LO`
  (12,5 GB) **abaixo de 2^39** e acima de 4 GB (o jogo foi feito para um espaço de 39 bits; o ART usa os 4 GB
  baixos), reservar com `MAP_FIXED_NOREPLACE` e gravar em `g_guest_lo`. Alinhar a base a 4 GB, para os offsets do
  ADRP continuarem triviais.
- **`gen.py --dyn-base`** (só o build Android passa). Sem a flag, a saída é idêntica à de hoje. Com ela:
  - `ADR`/`ADRP` e loads literais (`lifter.py`) emitem `B + offset`, com `const uint64_t B = g_main_base;` (ou
    `g_sdk_base`) uma vez por função;
  - endereço de retorno e `c->pc` nas chamadas (`gen.py:345-350`, `437-441`) viram `B + offset`;
  - os `switch` de jump table e `L_dispatch` (`gen.py:448-462`) passam a usar `switch (alvo - B)` com cases em offset;
  - `rando_word()`, `rt_udf`, `rt_svc` e `FN_ENTER` recebem `B + offset`;
  - a tabela de funções (`gen.py:619`) guarda offsets, e `dispatch.c` soma a base ao montar a busca;
  - `patches.py`: as chaves continuam no endereço canônico `0x70…`, e o gerador converte para offset com
    `a - 0x7000000000` (sem reescrever as ~60 entradas).
- **Runtime:** em `rt.h` e `kernel.h`, `#ifdef __ANDROID__` define `GUEST_LO` como variável (`g_guest_lo`) e
  `MAIN_BASE`, `SDK_BASE`, `HLE_BASE`, `TLS_BASE`, `HLE_HEAP`, `ALIAS_BASE`, `HEAP_BASE` e `GUEST_HI` como
  `GUEST_LO + offset`. No desktop continuam `#define` constantes.
  - Os dois inicializadores estáticos (`dispatch.c:93` `hle_next`, `kernel.c:114` `tls_next`) passam a ser
    inicializados em tempo de execução.
  - Os ~15 literais soltos (`LIVE_SAVE 0x7001CBB220`, `qol.c`, `chamber.c`, `warp_tab.mm`, `save_editor.mm`,
    `fnprof.c`, `cmdbuf.c`, `gamebuild.c`, …) viram `MAIN_BASE + offset` ou checagens `GUEST_LO..GUEST_HI`. No
    desktop isso compila para a mesma constante.
  - A memória global dos shaders já usa uma variável (`maxwell_gmem_base = HEAP_BASE` em `vulkan/exec.c:1204`).
    Os shaders não têm o endereço embutido.
- **Verificação:**
  - um script que falha se o C gerado com `--dyn-base` contiver algum literal na faixa `0x70_0000_0000`–
    `0x73_2000_0000`;
  - no aparelho, um modo de teste que força uma base alternativa (por variável de ambiente ou extra da Intent), para
    exercitar duas bases diferentes;
  - medir o custo de `B + offset` com o `simpleperf` (deve ser pequeno: um add por endereço materializado).

## Fases

### Fase 0: provas de viabilidade (antes de portar qualquer coisa)

Objetivo: responder às perguntas que podem mudar o plano. Um APK mínimo com SDL3, sem código do jogo:

1. **Janela de memória.** Logar `/proc/self/maps` dentro do app várias vezes (ASLR) e o tamanho do VA (inferido do
   endereço mais alto em maps). Rodar o algoritmo de escolha de base da seção "Janela de memória dinâmica" e
   confirmar que sempre encontra um buraco de 12,5 GB abaixo de 2^39. Aparelho de teste: POCO F7 Ultra
   (Snapdragon 8 Elite, Adreno 830).
2. **Turnip via adrenotools.** Carregar o Turnip, criar uma instância Vulkan 1.3 e logar extensões e features
   relevantes: `textureCompressionBC`, `shaderInt64`, `dualSrcBlend`, `depthClamp`, `wideLines`, `fillModeNonSolid`,
   `VK_EXT_external_memory_host`, `VK_EXT_custom_border_color`, `VK_EXT_vertex_attribute_divisor`, os present modes e
   as features 1.1/1.2/1.3 que `device.c` exige (`shaderInt8`, `scalarBlockLayout`, `storageBuffer8/16BitAccess`,
   `shaderDemoteToHelperInvocation`, `maintenance4`, …). Repetir com o driver da Qualcomm, para comparar.
3. **Opcional, mas barato:** rodar o build **Linux aarch64** já existente (`make ARCH=aarch64`) num Ubuntu em proot
   no Termux, com Termux:X11 e Turnip, no mesmo celular. Isso valida o código gerado e o backend Vulkan no Adreno
   antes de escrever qualquer código Android (o Linux aarch64 hoje está "não testado em hardware real").

Saída: uma tabela de capacidades por aparelho e a decisão sobre a janela de memória.

#### Resultados (2026-10-15, POCO F7 Ultra: SM8750, Adreno 830, Android 16, kernel 6.6, páginas de 4 KB, 16 GB)

A sonda está em `android/probe/` (relatório em `/sdcard/Android/data/com.lasrecomp.probe/files/report-latest.txt`).

**Memória.** Os resultados abaixo se repetiram em 5 execuções.
- VA de 39 bits. Os espaços livres de 12,5 GB ou mais entre 4 GB e 2^39 são três: `0x1_0000_0000`–`0x62_8dcb_8000`
  (390 GB), `0x62_8dcc_5000`–`0x75_0000_0000` (74 GB) e `0x79_36f1_7000`–`0x7f_c560_5000` (26 GB).
- A base dinâmica cai em `0x1_0000_0000`, em cerca de 3 a 5 ms, e a leitura e escrita no início, no heap e no fim
  funcionam.
- A janela fixa do desktop também estava livre. Mas o layout vem do zygote e só muda quando o aparelho reinicia, então
  isso não garante nada. **Decisão: base dinâmica**, como planejado.

**Vulkan.**

| | Qualcomm (sistema) | Turnip Gen8 V37 (adrenotools) |
|---|---|---|
| Driver | Vulkan 1.3.284, build 02/06/26 | Mesa 26.3.0-devel, Vulkan 1.4.363 |
| `vkCreateDevice` igual ao `device.c` | **falha**: falta `uniformAndStorageBuffer8BitAccess` | **falha**: mesmo motivo |
| Com as features de 8 bits opcionais | OK | OK |
| `VK_EXT_external_memory_host` | não | não |
| `VK_EXT_device_fault` | sim | não |
| BC1–BC7, ASTC | nativos | nativos |
| `shaderInt64`, `dualSrcBlend`, `depthClamp`, `wideLines`, geometry/tessellation | sim | sim |
| `shaderFloat64` | não | não |
| Subgroup | 64, só fragment e compute | 64–128, todos os estágios |
| Formatos de vértice de 3 componentes, `D24_UNORM_S8_UINT` | sim | sim |

O que isso decide:
- **8 bits:** nenhum shader usa 8 bits em uniform buffers (a única referência é a ativação em `device.c`). No
  Android, `shaderInt8`, `uniformAndStorageBuffer8BitAccess` e `storageBuffer8BitAccess` passam a ser pedidas só
  quando o driver tem.
- **Texturas BC:** são nativas nos dois drivers. O decodificador de BC na CPU sai do caminho crítico; fica só para
  GPUs futuras sem BC.
- **Subgroups:** o `SHFL` em vertex shader já é traduzido como valor local, sem subgroup (`translate.c`), então o
  limite do driver da Qualcomm não afeta.
- **Memória global dos shaders:** nenhum dos dois drivers tem host import. Alternativas: `adrenotools_import_user_mem`
  (só no driver da Qualcomm, via KGSL) ou o fallback por cópia da Fase 3.
- **Ainda não medido:** present modes e `preTransform`, porque precisam de uma surface. Fica para a Fase 1.

### Fase 1: build

- **Dependências para Android arm64** (scripts com versões e checksums fixos, no estilo de
  `tools/linux/build_deps.sh`):
  - SDL3 (via `android-project`)
  - shaderc (as fontes vêm no NDK, em `sources/third_party/shaderc`)
  - Vulkan Memory Allocator (só headers)
  - FFmpeg LGPL só com os codecs dos vídeos, como no desktop; alternativa: implementar `hle/movie.c` com
    `AMediaCodec` do NDK
  - libadrenotools
- **`runtime/platform/android.c`:**
  - `plat_reserve_guest` com a base dinâmica (`android/common/guest_window.c`, validado na Fase 0), sem re-exec
  - `plat_exec` e `plat_spawn` limitados ou ausentes
  - backtrace via `_Unwind_Backtrace`
  - crash handler instalado de forma compatível com o sigchain do ART
  - `plat_exe_path` → `nativeLibraryDir`
- **Logs:** `LOG`/`FATAL` → logcat (`__android_log_print`), ou um redirecionamento de `stderr` para o logcat no início.
- **Caminhos** (`paths.c`): data root = `SDL_GetAndroidExternalStoragePath()` (`/sdcard/Android/data/<pkg>/files`,
  acessível pelo adb e pelo gerenciador de arquivos). `lasgame.so` fica obrigatoriamente no armazenamento **interno**
  (`SDL_GetAndroidInternalStoragePath()`), porque o externo é montado `noexec`.
- **Projeto Gradle** em `android/` (o CMake compila `runtime/`, `third_party/imgui` e o backend Vulkan com
  `UIFLAGS=-x c++`, como no Linux) e o alvo `make android-gamelib`, que compila `generated/` com o NDK e linka
  contra `libmain.so`.

Pronto quando: o APK instala, abre a tela inicial em ImGui com Vulkan (Turnip) e loga no logcat.

**Estado (2026-10-15): concluída.** Validada no POCO F7 Ultra com o driver da Qualcomm e com o Turnip Gen8 V37 via
adrenotools: tela inicial em tela cheia e paisagem, logs no logcat (tag `LASRecomp`), janela do guest em
`0x1_0000_0000`.
- Dependências: `android/deps/build_deps.sh` (SDL3 3.4.16, shaderc 2026.3, Vulkan-Headers 1.4.350.1, VMA 3.3.0, volk).
- App: `android/lasrecomp/` (Gradle + CMake). O runtime vira `libmain.so`, carregado pelo `LASRecompActivity`.
  Drivers customizados vão em `Android/data/com.lasrecomp.android/files/drivers/` (o primeiro `.so` ou `.zip`).
- Runtime: tudo atrás de `__ANDROID__` (`platform/android.c`, `rt.h`, `vulkan/android_driver.c`, volk, surface via
  `ANativeWindow`, features de 8 bits opcionais, swapchain sem pré-rotação, tela cheia sempre) ou `LARECOMP_NO_FFMPEG`.
- Pendências:
  - FFmpeg: o `movie.c` está fora do build, e o fundo da tela inicial também (Fase 2);
  - pré-rotação do swapchain (Fase 3);
  - seletor de pasta via SAF e toque (Fases 4 e 5);
  - revisar os ~860 avisos de compilação.

### Fase 2: chegar ao primeiro frame do jogo

- Copiar `extracted/` e `lasgame.so` pelo adb (temporário). O runtime copia o `lasgame.so` do armazenamento externo
  para o interno antes do `dlopen`.
- Entrar direto no jogo (o equivalente a `--play` / `LARECOMP_NO_LAUNCHER`, via extra da Intent ou um arquivo de flag).
- Depurar o boot: kernel, threads, `nnMain`, serviços. Ferramentas: `LARECOMP_FNPROF`, `rt_fatal_trace`, logcat e
  `simpleperf` para performance.

Pronto quando: a intro e a tela de título aparecem com som.

**Estado (2026-10-15): o jogo inicializa.** No POCO F7 Ultra com Turnip:
- módulos em `0x1_0000_0000` (`--dyn-base`), kernel, threads e serviços no ar, save montado;
- os vídeos de abertura tocam pelo FFmpeg (`android/deps/build_ffmpeg.sh`, via WSL), com a saída de áudio aberta;
- **a cerca de 12 fps**, com ~1,7 s de espera da GPU a cada 2 s e ~190 MB de upload de texturas a cada 2 s
  (Fase 3).

Fluxo de desenvolvimento (sem setup no aparelho ainda):
1. `android/build_gamelib.sh <exefs>` gera e compila a `lasgame.so`.
2. `android/push_game.sh <pasta com exefs/ e romfs/>` copia os arquivos e gera o `romfs.bin` no aparelho.
3. Para entrar direto no jogo:
   `adb shell am start -n com.lasrecomp.android/com.lasrecomp.LASRecompActivity --es env "LARECOMP_NO_LAUNCHER=1"`.

A fonte do sistema do Switch é substituída pela DejaVu Sans que o app traz: a Roboto não tem o U+25A1, e o código de
texto do jogo não trata glifo nulo.

Falta para concluir a fase: confirmar o som no aparelho e passar da tela de título (entrada: controle físico via
SDL, ou toque, da Fase 4).

### Fase 3: Vulkan no Adreno/Turnip

Ordem definida pelos resultados da Fase 0:

- **Features de 8 bits opcionais** no `vkCreateDevice` (pré-requisito, já validado na sonda).
- **Sem `VK_EXT_external_memory_host`** (nos dois drivers): hoje `exec.c` desliga a memória global dos shaders
  (`LDG`/`STG` leem zero). Primeiro medir **quais** shaders do jogo usam memória global; pode ser pouca coisa. Depois,
  um fallback por cópia (um buffer GPU espelhando as faixas do heap usadas, com escrita de volta após compute), ou
  `adrenotools_import_user_mem` no driver da Qualcomm.
- **Texturas BC:** nativas no Adreno 830 (nos dois drivers). Decodificar na CPU só se aparecer uma GPU sem BC.
- **Features opcionais:** no Adreno 830 só falta `shaderFloat64`. O tradutor não deve gerar `double`.
- **Present:** `MAILBOX` geralmente não existe no Android. Usar `FIFO` como padrão e respeitar `preTransform`
  (rotação da surface).
- **Performance:** resolução interna padrão menor (o código de escala dinâmica e FSR1 já existe), cache de pipelines
  em disco, `vkQueueSubmit` agrupado e cuidado com load/store ops (GPU tiler: evitar `LOAD` e `STORE` desnecessários
  em attachments). Medir com o Snapdragon Profiler.
- **Regras gerais:** o desktop não muda de comportamento (Windows e Linux usam o mesmo backend). Toda mudança em
  `vulkan/` fica atrás de `__ANDROID__`, ou condicionada a uma capacidade que o desktop sempre tem, o que dá o mesmo
  resultado lá.

Pronto quando: overworld, uma casa e uma dungeon renderizam corretamente a 30 ou 60 fps num Snapdragon 8 Gen 2/3.

**Estado (2026-10-15): o jogo roda a 30 fps estáveis no mundo aberto**, e a 60 em áreas leves (POCO F7 Ultra, Turnip
Gen8 V37, resolução 50%). O que mudou desde os ~12 fps da Fase 2:
- **Memória global:** com o Turnip, o heap do guest é memória da GPU mapeada no próprio endereço
  (`VK_EXT_map_memory_placed`, `vk_heap_place`), sem cópias. No driver da Qualcomm, cópias pela CPU (`gcopy`). Os
  dispatches de compute leem o heap por uma janela do descriptor (limite de 2 GB do Adreno) em vez de copiar faixas.
- **Shaders:** a janela do `BRX` só rotula o próprio switch (SSAO de ~20 ms para menos de 1 ms). As pilhas
  SSY/PBK/PCNT agora são resolvidas na tradução (`maxwell_static_stacks`): a GPU no mundo aberto caiu de ~18,5 para
  ~14 ms por frame.
- **CPU:** o runtime passou de `-O0` para `-O2` (o build Debug do Gradle não tinha otimização). Despacho de funções
  sem stack frame e com cache maior (de 13% para 5% da thread do jogo). Hash do fragment shader e da chave de pipeline
  memoizados. Laço da UI sem espera ativa.
- **Sincronização:** as fences do jogo cobrem o trabalho dele, e não o present, que espera o compositor. O `SyncWait`
  caiu de ~10 para ~1,4 ms por frame.
- **Android:** cache de pipelines salvo durante o jogo, superfície recriada ao voltar do segundo plano, overlay de
  desempenho, IME desligado com controle conectado.
- **Diagnóstico:** `LARECOMP_GPUPROF_AT`, `LARECOMP_VK_PROF`, `LARECOMP_LOG_PACE`, `LARECOMP_LOG_SYNCSEQ`,
  `LARECOMP_LOG_COUNTER`, `LARECOMP_SHADER_STATS`, `LARECOMP_SHADER_SUBST` e `LARECOMP_SAVE_MSL`, além do simpleperf.

Para 60 fps no mundo aberto, faltam:
- **GPU:** ~14 ms por frame, enquanto o jogo só escolhe o modo 60 abaixo do limite dele. Próximo passo: trocar a
  máquina de estados dos shaders com desvios por `if`/laços (os vertex shaders pesados rodam com 48 registradores e
  só 4 waves).
- **CPU:** ~18 ms por frame na thread do jogo no modo 30, sendo ~10 de código do jogo e ~8 de gravação Vulkan e
  driver. Opção: gravar os comandos numa thread própria.

Problemas conhecidos: trocar a resolução com o jogo rodando pode fechar o app; sem pré-rotação do swapchain; heap
acima de 2 GB contra o limite de descriptor fora dos dispatches de compute.

### Fase 4: Android de verdade (ciclo de vida e entrada)

- **Ciclo de vida:**
  - `SDL_EVENT_WILL_ENTER_BACKGROUND`: pausar threads guest, pausar áudio, esperar a GPU ociosa
  - `SDL_EVENT_DID_ENTER_FOREGROUND`: recriar a surface e o swapchain (hoje são criados uma vez, em `device.c`)
  - Não perder o save se o sistema matar o app em background.
- **Toque:** um controle virtual (overlay ImGui ou desenhado pelo presenter) que alimenta o mesmo caminho de
  `hle/input.c` e `bindings.c` que o controle físico usa, com layout configurável e opacidade. Os botões na tela
  seguem os ícones de botão que já existem.
- **Controles físicos:** já funcionam via SDL3. Verificar que o botão Voltar abre o menu (o equivalente ao F1) em vez
  de fechar o app.
- **ImGui:** escala por DPI, alvos de toque maiores e teclado virtual (`osk.mm` já existe).
- **Tela:** imersivo (sem barras), notch/cutout, aspecto largo (o widescreen do projeto encaixa bem nas telas
  20:9 de celular).

### Fase 5: instalação pelo usuário (sem adb)

- **Escolher a pasta do jogo:** `ACTION_OPEN_DOCUMENT_TREE` (SAF) via JNI e cópia para o data root. O launcher já
  copia os arquivos para `extracted/`; só a origem muda (`content://`).
- Importar `lasgame.so` gerado no PC (MVP) e pastas de seed.
- Importar fontes (`SDL_ShowOpenFileDialog` funciona no Android e devolve URIs).
- Checagem de update: trocar o `curl` por HTTP via JNI (`HttpURLConnection`), ou desligar no Android.

### Fase 6: gerar o código do jogo no próprio aparelho

Só depois do jogo rodar bem. Opções:

| Opção | Gerador | Compilador | Comentário |
|---|---|---|---|
| A | Python embutido (Chaquopy ou CPython do NDK) + capstone, unicorn e lz4 compilados para Android | Zig empacotado como `jniLibs/arm64-v8a/libzig.so` (o `nativeLibraryDir` permite `exec`) | Menos reescrita, APK grande |
| B | Reescrever as partes do `gen.py` que precisam rodar no aparelho em C (capstone e unicorn têm API C) | Igual à A | Mais trabalho, mais robusto |
| C | Continuar gerando no PC | (nenhum) | Zero esforço, pior para o usuário |

Considerar o tempo de compilação e o aquecimento do aparelho: compilar em background com progresso, com `-O1` como hoje.

### Fase 7: randomizer no aparelho

O gerador de seeds depende de oead e evfl (C++/pybind11) e keystone. As opções espelham a Fase 6: Python embutido com
essas libs compiladas para Android, ou continuar importando seeds geradas no desktop (o seed ID já reproduz uma seed
exatamente igual).

### Fase 8: distribuição

- APK pelo GitHub Releases (sideload). A Play Store é improvável, por política de conteúdo de terceiros.
- Nada do jogo no APK. Revisar a tabela de licenças do README (adrenotools é BSD-2, Turnip é MIT, e a parte GPL
  entra só se a Fase 6/7 empacotar unicorn, oead e keystone).
- Página `docs/android.md` no estilo de `docs/linux.md`: aparelhos testados, versão do Turnip, como reportar bugs.

## Riscos principais

1. **Base dinâmica:** um literal absoluto esquecido no gerador ou no runtime causa bugs sutis. O script de
   verificação e o teste com duas bases existem para pegar isso.
2. **Adreno 830 (8 Elite) no Turnip:** o Turnip Gen8 carrega e cria o device, mas ainda é um build experimental
   (Mesa main com patches). Manter o driver da Qualcomm como alternativa.
3. **Memória global de shader sem host import (confirmado nos dois drivers):** pode quebrar efeitos de compute até
   existir o fallback.
4. **RAM:** o heap do guest pode chegar a 3,25 GB (`HEAP_MAX`), mais texturas e o processo. Aparelhos com 8 GB ou
   mais são o alvo realista. Observar o low memory killer.
5. **Térmico e performance:** o código gerado com `-O1` é bom no Apple M1; num Snapdragon 8 Gen 2+ deve bastar, mas
   aparelhos intermediários podem exigir resolução interna baixa.
6. **Turnip × versão do Mesa:** comportamento varia por versão. Fixar uma versão testada e deixar o usuário trocar.

## Próximos passos

Feito (2026-10-16): a release do APK com tudo no celular (Fases 5 e 6), publicada como v0.1.0 e v0.1.1 no GitHub:
o app escolhe a pasta do jogo pelo seletor do Android (SAF), copia `exefs/` e `romfs/`, gera o `romfs.bin` e compila
o código do jogo no próprio aparelho (`runtime/gamebuild.c`, ~20 min no Snapdragon 8 Elite); o driver Turnip também é
escolhido pelo app. Os botões de toque somem sempre que há um controle conectado (item 1 da lista anterior).

Depois, nesta ordem:

1. **60 fps sustentados (feito, 2026-10-16, v0.2.0):** no mapa aberto, 98% do tempo a 60 e nenhum quadro acima de
   18 ms (antes ~55-70%). O que mudou: uma thread de renderização traduz as listas de comandos enquanto o jogo faz o
   quadro seguinte, e entrega à GPU em partes nos fins de pass (`runtime/nvn/vulkan/async.c`; LARECOMP_VK_ASYNC=0
   desliga); o núcleo 0 do Switch emulado (as threads desse núcleo se revezam, como no console: o sistema de tarefas
   do jogo tinha uma condição de corrida que uma thread principal mais rápida expunha - `runtime/kernel.c`); o pacer
   distingue os quadros de 2 passos do próprio jogo de um quadro de 60 que atrasou; ADPF mirando 60 fps nas duas
   threads; três correções de sincronização no kernel (condvar, árbitro, timeout). Fixar a thread principal nos
   núcleos mais rápidos (LARECOMP_PIN) ficou desligado: expunha a mesma condição de corrida.
2. **Geração de quadros (adiada):** o lsfg-vk mudou de licença (MIT na v1, GPLv3 na v2, agora CC BY-NC-ND 4.0, que
   proíbe obras derivadas) por causa de forks gerados por IA; só a v1 continua MIT, e usá-la iria contra o pedido
   explícito do autor. Decisão (2026-10-16): primeiro os 60 fps sustentados; depois, se ainda fizer sentido, geração
   de quadros própria com o AMD FidelityFX FSR 3 (MIT), sem Lossless.dll (o jogo não dá vetores de movimento: a
   qualidade é a dúvida).
3. **Além do Turnip (iluminação feita, 2026-10-16, v0.3.0):** no driver da própria Qualcomm o jogo agora roda com a
   iluminação, a 30 fps. Sem `VK_EXT_map_memory_placed`, a memória global dos shaders passa por uma arena na GPU
   (`exec.c`, gres_*): as regiões que um shader escreve ficam residentes, as escritas da CPU são detectadas por
   comparação e copiadas pela GPU, os resultados da GPU voltam bloco a bloco; uma margem de 4 KB antes de cada região
   (o contador de luzes fica antes da lista); e os shaders registram os acessos fora das tabelas, que viram regiões
   aprendidas. O compilador da Qualcomm trava com o structurizer: desligado nesse driver. Falta: desempenho nesse modo
   (~640 MB de cópias a cada 2 s) e testar Mali / Xclipse (devem usar o mesmo caminho).