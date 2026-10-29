# TOTKRecompAndroid

[English](README.md) | **Português**

Uma recompilação estática de *The Legend of Zelda: Tears of the Kingdom* (Nintendo Switch, versão **1.4.3**) para
Android, nos primeiros passos. **Não é emulação**: o código ARM64 do jogo vira C compilado para o seu celular, e os
gráficos (NVN) são traduzidos para Vulkan.

Feito sobre o motor do **[LAS Recomp](https://github.com/peiikai/LASRecomp)**, de peiikai (uma recompilação de
*Link's Awakening*), do seu porte para Android **[LASRecompAndroid](https://github.com/mamcjr/LASRecompAndroid)** e do
**[BOTWRecompAndroid](https://github.com/mamcjr/BOTWRecompAndroid)**, de onde este repositório saiu.

> [!WARNING]
> **No começo.** No celular (com o driver Turnip) o jogo carrega um save na caverna do prólogo e dá para mover o Link, a
> cerca de 30 fps; pouco além disso foi jogado. O plano e o estado de cada fase estão em
> [docs/TOTK-PORT.md](docs/TOTK-PORT.md).

## Plataforma

Somente Android (arm64, Vulkan). Windows, Linux ou macOS servem como máquina de desenvolvimento, com o SDK/NDK do
Android.

## Estado

- O app (tela inicial, configurações, controles de toque, controles físicos, mods, escolha do driver Vulkan) vem do
  BOTWRecompAndroid e compila.
- A 1.4.3 roda sobre o SDK 20.5.22 da Nintendo, a mesma linha do 20.5.6 do BOTW 1.8.2 (NVN 55): o que o motor aprendeu
  com o BOTW 1.8.2 vale. Os patches do jogo e muito do que se sabe dele vêm do
  [MacNativeGames](https://github.com/ly05010419/MacNativeGames), que roda exatamente esta versão nativa em Macs e
  iPhones.
- O código do jogo é gerado e compila: 228.238 funções do `main` e 40.516 do SDK, num `totkgame.so` de 224 MB.
- No celular (POCO F7 Ultra, driver Turnip): a tela título e depois um save carregado na caverna do prólogo, com o
  Link controlável e o HUD e as dicas em português, a cerca de 30 fps andando (Turnip). A cena fica em 1600x896 (a resolução dinâmica
  do jogo dava quadros pretos a cada troca) e o mundo anda pelo tempo real. As vozes dos personagens, a música e o
  ambiente chegam à mixagem (o decodificador Opus e os splitters do renderizador de áudio; conferido gravando a saída
  na 1.4.2, a versão anterior do porte). Com o driver da própria Qualcomm também roda, a cerca de 15 fps.
- Falta: os vídeos (não conferidos), o widescreen, os 60 fps, o desempenho além da caverna.

> [!IMPORTANT]
> Como o LAS Recomp, este projeto é feito quase inteiramente com IA (Claude, da Anthropic, pelo Claude Code), dirigido,
> testado e revisado por uma pessoa.

> [!NOTE]
> **Nenhum arquivo do jogo vem incluído.** É preciso o seu próprio dump, obtido legalmente, do *Tears of the Kingdom*
> com a atualização 1.4.3 aplicada (`exefs` e o `romfs`). Não serão dadas instruções de como obtê-lo.

## Compilando (desenvolvedores)

Com o SDK / NDK do Android e o seu dump em `Dump/0100F2C0115B6000/` (`exefs/` e `romfs/`, ou a imagem do RomFS
`romfs.bin`; a pasta fica fora do git), com Python e os pacotes `lz4`, `capstone` e `unicorn` (o
`tools/nsp_extract.py` desempacota NSPs e as title keys deles para o hactoolnet):

```bash
cd tools && python totk_dump.py                              # confere o dump (módulos, versão, romfs)
cd android/totkrecompandroid && ./gradlew assembleDebug      # o app
android/build_gamelib.sh Dump/0100F2C0115B6000/exefs         # o código do jogo (~20 min num PC)
ROMFS_HOST=1 android/push_game.sh Dump/0100F2C0115B6000      # arquivos e código do jogo para o celular
android/run_boot.sh 120                                      # inicia o jogo, log em android/out/boot.log
```

## Créditos

- [peiikai](https://github.com/peiikai) pelo [LAS Recomp](https://github.com/peiikai/LASRecomp), o recompilador e o
  runtime de onde este projeto parte (MIT, ver [LICENSE](LICENSE)). O README original está em [docs/las/](docs/las/).
- [MacNativeGames](https://github.com/ly05010419/MacNativeGames) (MIT), que roda o Tears of the Kingdom 1.4.3 nativo em
  Macs e iPhones: os sítios de patch da 1.4.3 verificados por ele (a resolução fixa, o passo de quadro em tempo real, o
  relógio de pano e cabelo, o tamanho de renderização) e o que ele descobriu (o flag de early-Z e o arredondamento da
  altura de bloco do NVN, as leituras de profundidade e a comparação de sombra D16, o contador de visibilidade).
- [LASRecompAndroid](https://github.com/mamcjr/LASRecompAndroid), o seu porte para Android, e
  [BOTWRecompAndroid](https://github.com/mamcjr/BOTWRecompAndroid) (o README dele em [docs/botw/](docs/botw/)): o app,
  a compilação do código do jogo no celular, os controles de toque, os mods, a compatibilidade de drivers, os menus em
  português, o visual Sheikah e tudo o que o motor aprendeu com o SDK do BOTW 1.8.2.
- PKMSVRecompAndroid (um porte irmão, do *Pokémon Scarlet*, não publicado): as versões para um jogo de uma versão só, o
  program id lido do NPDM e os pares exclusivos de 16 bytes no recompilador.
- [Revivedx](https://github.com/Revivedx) pelo [SavageWindRecomp](https://github.com/Revivedx/SavageWindRecomp) (MIT):
  as correções de kernel do SDK 20, o setjmp, o NVN 55.
- [Nycolazs](https://github.com/Nycolazs) pelo LASRecomp-Android-Plus (MIT): compatibilidade de drivers Vulkan.
- A decompilação [zeldaret/botw](https://github.com/zeldaret/botw) e o [open-ead/sead](https://github.com/open-ead/sead),
  pelo que se sabe do motor de onde o TOTK veio.
- Upscaling e anti-aliasing: [AMD FidelityFX FSR 1](https://github.com/GPUOpen-Effects/FidelityFX-FSR) (MIT),
  [Qualcomm Snapdragon GSR](https://github.com/SnapdragonStudios/snapdragon-gsr) (BSD-3-Clause),
  [SMAA](https://github.com/iryoku/smaa) (MIT).
- Bibliotecas: [SDL](https://libsdl.org) (zlib), [Dear ImGui](https://github.com/ocornut/imgui) (MIT),
  [libadrenotools](https://github.com/bylaws/libadrenotools) (BSD-2-Clause), [volk](https://github.com/zeux/volk) (MIT),
  [Vulkan Memory Allocator](https://github.com/GPUOpen-LibrariesAndSDKs/VulkanMemoryAllocator) (MIT),
  [shaderc](https://github.com/google/shaderc) (Apache 2.0), [FFmpeg](https://ffmpeg.org) (LGPL),
  [LZ4](https://github.com/lz4/lz4) (BSD-2-Clause), [Capstone](https://www.capstone-engine.org) (BSD-3-Clause),
  [Unicorn](https://www.unicorn-engine.org) (GPLv2, compilado do código-fonte por `android/deps/build_unicorn.sh`),
  [Python](https://www.python.org) (PSF) e [Zig](https://ziglang.org) (MIT) para a compilação no celular.
- O driver Turnip do [Mesa](https://www.mesa3d.org) (MIT), que os jogadores carregam por conta própria.
- Fontes: [Baloo 2](https://github.com/EkType/Baloo2) (OFL), DejaVu Sans (licença Bitstream Vera). O robô do Android
  é reproduzido ou modificado a partir de trabalho criado e compartilhado pelo Google (CC BY 3.0).
- Feito com o [Claude Code](https://claude.com/claude-code) (Anthropic).

*The Legend of Zelda* e *Tears of the Kingdom* são marcas registradas da Nintendo. Este projeto não é afiliado à
Nintendo nem endossado por ela.
