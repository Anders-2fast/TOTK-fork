# Levantamento Completo de Mods de Desempenho de Breath of the Wild e sua Aplicação no BOTWRecompAndroid

Este documento consolida a análise técnica e o levantamento aprofundado de todas as principais modificações de desempenho desenvolvidas para *The Legend of Zelda: Breath of the Wild* ao longo dos anos nas plataformas de emulação e modding (**Wii U / Cemu**, **Nintendo Switch / Yuzu / Ryujinx**, **Skyline**, **EggNS** e **UltraCam / NX-Optimizer**), detalhando seus princípios matemáticos/arquiteturais e **como cada técnica pode ser aproveitada diretamente na recompilação estática (`BOTWRecompAndroid`)**.

---

> **Verificação no BOTWRecompAndroid (21/10/2026, POCO F7 Ultra, Turnip, cena do platô).** Este estudo junta fontes externas e inferências. Abaixo, o que foi conferido no projeto. O restante do texto vale como mapa de ideias, não como medição.
>
> - **Confirmado:**
>   - A queda para 20 fps pelo `sead` (seção 1.2), já corrigida com `rt_frame_ticks`.
>   - O custo da GPU ser por draw e não por pixel.
>   - O SMAA e o FSR 1 do runtime existem (abas Anti-aliasing e Upscaler).
>   - `LARECOMP_SKIP_FSHASH` existe.
> - **Não se sustenta:**
>   - "40 a 47 fps no Santuário" e "8,4 fps / 14.573 cópias" não aparecem em nenhuma medição do projeto. Sem patch, o BOTW 1.0.0 só roda a 30 ou 20 (o `sead` nunca escolhe menos de 2 vsyncs).
>   - O G-buffer não usa "6 alvos RGBA16F": são RG8, RGBA8 sRGB, RGBA8, R32F e R11G11B10, com albedo e normais ligados em dois slots cada.
>   - Resolução menor não reduz o tempo de GPU aqui: `LARECOMP_HANDHELD=1` (720p) manteve ~23 ms. Ela ajuda em temperatura e bateria, não em fps.
>   - O FXAA do jogo custa **~0,6 ms** (luma 0,19 + FXAA/upscale 0,40 ms, `[gpuprof]`) e é **também o upscale de 1600x900 para 1080p**. Não dá para simplesmente pulá-lo; trocá-lo por um upscale simples economizaria ~0,3 ms, não 1,5–2,5 ms.
>   - Os códigos `.pchtxt` e RRS da seção 2.B não têm fonte. A anotação de `5287F008` está errada: carrega 0x3F80, não 0x3F800000. São todos da 1.6.0.
> - **Render distance:** segundo o próprio nx-optimizer, ajusta o plano distante da câmera ("far camera plane") e foi feito principalmente para o TOTK. No BOTW 1.0.0 o endereço é desconhecido, e reduzir o plano distante cortaria o horizonte, não os draws próximos.
>   - Os ~3.600 draws do G-buffer nesta cena estão espalhados por 144 programas, sem instancing e com no máximo ~270 draws por shader. Não há uma categoria dominante para cortar.
>   - O custo de ~2 µs de GPU por draw aponta para a troca de estado por draw (pipelines e descriptor sets) no nosso renderer.
> - **Dynamic FPS / 60 fps:** exige dezenas de patches específicos (stamina, flechas, ragdoll, água, menus), a serem redescobertos na versão alvo. Não é uma função de delta-time única. Fica para depois da 1.6.0, onde há patches públicos para estudar.

---

## 1. Fundamentos da Engine de BOTW e os Gargalos Originais

O motor de *Breath of the Wild* foi desenvolvido sobre a framework proprietária **`sead`** da Nintendo, utilizando a biblioteca de física **Havok** e o subsistema de jogo **`ksys`** (*KingSystem*). Ele foi projetado exclusivamente para as limitações fixas de hardware do Wii U e do Nintendo Switch (Tegra X1):

1. **Lógica de Simulação Frame-Based (30 FPS Fixo):**
   - O loop de simulação avança em intervalos discretos baseados na suposição de que 1 frame equivale a exatamente $\frac{1}{30}\text{ s} \approx 33,33\text{ ms}$.
   - As integrações de física, velocidades de projéteis, arrasto aerodinâmico, taxas de drenagem/recarga de stamina, transições de animação e eventos de script contêm valores escalados fixos para 30 Hz.
   - **Desbloqueio ingênuo para 60 FPS:** Se o jogo simplesmente roda a 60 FPS sem patches, todas as velocidades dobram ($2\times$). Se configurado para 60 FPS estático e o hardware atinge 45 FPS, o jogo roda em câmera lenta ($0,75\times$).

2. **A Armadilha dos 20 FPS (*20 FPS Lock / VSync Drop*):**
   - No Switch, o módulo `sead::Framework` mede o tempo gasto no quadro anterior (`ticks` do hardware).
   - Se o frame demorar mais do que $\approx 1,85 \text{ a } 1,9$ vsyncs ($> 31\text{ ms}$), o motor altera o intervalo de apresentação do NVN (`nvnWindowSetPresentInterval`) de **2** (30 FPS em tela de 60 Hz) para **3** (20 FPS).
   - Em dispositivos móveis (Android com GPUs Adreno/Mali), quando o jogo cai para 20 FPS, o governador de frequência do kernel do Android observa que o hardware está trabalhando apenas $\approx 25\text{ ms}$ a cada $50\text{ ms}$. O governador reduz os clocks da CPU e GPU para poupar energia, impedindo que o jogo consiga voltar aos 30 FPS (*death spiral* de throttling).

3. **Resolução Dinâmica Agressiva (*Dynamic Resolution Scaling - DRS*):**
   - O motor ajusta o viewport de renderização entre 75% e 100% (ou 80% a 100%) em tempo real conforme a carga. Em emuladores e na recompilação, essas mudanças constantes forçam alterações dinâmicas de scissor, quebra de render passes no Vulkan e blits de redimensionamento desnecessários.

---

## 2. Levantamento Histórico e Técnico das Mods por Plataforma

```
                                  Evolução das Mods de Desempenho de BOTW
                                  
   [ Wii U / Cemu ]                [ Switch / Yuzu / Ryujinx ]            [ Android & Moderno ]
      (2017-2019)                         (2019-2023)                          (2023-2026)
  +------------------+                +------------------+                 +------------------+
  |      FPS++       |                | Static 60 FPS    |                 | Skyline / EggNS  |
  |  (Xalphenos/     |===============>| (.pchtxt / IPS)  |                 | (GMEM 540p hacks,|
  |   Crementif/     |                +------------------+                 |  fence bypass)   |
  |   rajkosto)      |                         |                           +------------------+
  +------------------+                         v                                    |
          |                           +------------------+                          v
          |                           | Dynamic FPS      |                 +------------------+
          +==========================>| (somerando/      |================>| UltraCam /       |
                                      |  ChuckLiddell)   |                 | NX-Optimizer     |
                                      +------------------+                 | (MaxLastBreath)  |
                                                                           +------------------+
```

---

### A. Cemu / Wii U: FPS++ (Xalphenos, Crementif, epigramx, rajkosto)

O **FPS++** foi a primeira implementação da história a desacoplar com precisão a taxa de quadros da lógica interna do jogo. A modificação é composta por módulos Assembly PowerPC injetados em codecaves:

#### 1. Cálculo de Velocidade de Jogo (`patch_GameSpeed.asm`)
Intercepta a função principal de contagem temporal do motor (`0x031FA97C` no executável v208 do Wii U).
- **Medição de Ticks:** Lê a diferença de clocks de barreira (`timerTickSpeed = 62156250 Hz`).
- **Buffer Circular de Média Móvel:** Mantém um histórico de 4 a 32 quadros para amortecer picos instantâneos (*frame pacing spikes*).
- **Multiplicadores Dinâmicos Inversos Gerados:**
  $$\text{averageFPS1} = \frac{\text{FPS}}{30.0} \quad\quad \text{averageFPS1Inv} = \frac{30.0}{\text{FPS}}$$
  $$\text{averageFPS30} = \text{FPS} \quad\quad \text{averageFPS30Inv} = \frac{900.0}{\text{FPS}}$$
  $$\text{averageFPS0.5} = \frac{\text{FPS}}{60.0} \quad\quad \text{averageFPS0.5Inv} = \frac{30.0}{2 \cdot \text{FPS}}$$
  $$\text{averageFPS1.5} = \frac{1.5 \cdot \text{FPS}}{30.0} \quad\quad \text{averageFPS1.5Inv} = \frac{45.0}{\text{FPS}}$$

#### 2. Patches em Mecânicas Específicas:
- **Flurry Rush (`_changeFlurryRush`):** Modifica o registrador de distância do avanço do Link aplicando `averageFPS1` para manter o alcance idêntico a 30 FPS.
- **Bullet Time / Arrow Time Drain (`0x02D5F760`):** Aplica `averageFPS1Inv` na taxa de consumo de stamina quando o arco é puxado no ar, evitando que a stamina evapore em 1 segundo a 60 FPS.
- **Balística de Flechas (`0x020E5510`, `0x020E3EB0`):** Substitui constantes estáticas de gravidade e impulso por `averageFPS30Inv` e `averageFPS0.5` para preservar a trajetória e a distância do tiro reto.
- **Regeneração de Stamina (`0x02D90D88`):** Carrega a variável dinâmica `averageFPS30` no lugar do float fixo `30.0f`.
- **Física de Ragdolls (`patch_Ragdolls.asm`):** No jogo original, a função `RagdollApplyForce` aplicava forças multiplicadas pelo delta do quadro. Em taxas altas, um único golpe arremessava inimigos para fora do mapa. O patch trava o timestep do ragdoll no float constante `0.03333334f` ($1/30$).
- **Física da Água (`patch_WaterPhysics.asm`):** O escalonamento quadrático do arraste na água fazia objetos flutuantes não desacelerarem. O patch introduziu uma fórmula com fator de amortecimento linear (`0.45f`):
  $$\text{timestep}_{\text{final}} = \left((\text{timestep}_{30} - 1.0) \times 0.45 + 1.0\right) \times \text{timescale}$$
- **Perseguição de Koroks (`patch_KorokChasing.asm`):** Trava a velocidade dos brilhos de Korok no chão para não andarem no dobro da velocidade.
- **Navegação de Menus e Mapas (`0x03415C1C`, `0x02F62B3C`):** Aplica `averageFPS1Inv` na velocidade do cursor do mapa e GUI.

#### 3. Otimização de Sincronização e Fences (`patch_FenceMethod.asm` e `patch_VSync.asm`):
- **SkipFence / PerformanceFence:** O BOTW emite fences frequentes para verificar conclusão de comandos da GPU. O patch implementou a opção de pular fences não essenciais da CPU, liberando tempo de thread para o emulador.
- **Controle de SwapInterval:** Permite forçar o swap interval do subsistema de vídeo para 0 (sem trava/desbloqueado), 1 (60 FPS) ou 2 (30 FPS).

---

### B. Nintendo Switch / Yuzu / Ryujinx

No Switch, o jogo é executado nativamente em arquitetura ARM64 (AArch64). As mods evoluíram de patches simples em arquivos `.pchtxt` para módulos de código dinâmico.

#### 1. Patches Estáticos de 60 FPS (`.pchtxt` / Atmosphere Cheats)
Para a versão **v1.6.0** (TitleID `01007EF00011E000`, Build ID `8E9978D50BDD20B4`):
```text
[v1.6.0 [60 FPS]]
04000000 01614954 52800008   ; mov w8, #0          (Desativa verificação de trava)
04000000 01614D84 5287F008   ; mov w8, #0x3F800000 (Float 1.0f vsync step)
04000000 01614DB0 52800020   ; mov w0, #1          (Força PresentInterval = 1)
04000000 01614DA4 12000021   ; and w1, w1, #1      (Ignora clamp mínimo de 2 vsyncs)
```
- **Como funciona:** Obriga o loop do `sead::Framework` a submeter o frame a cada 1 vsync (60 Hz) e fixa o multiplicador de passo em 1.0.
- **Problema:** Quando a taxa cai abaixo de 60 FPS (por exemplo, 40 FPS), o jogo sofre o efeito de câmera lenta proporcional.

#### 2. Dynamic FPS (BOTW-DFPS / TOTK-DFPS por somerando e ChuckLiddell)
Criado para sanar a limitação dos 60 FPS estáticos:
- Substitui a instrução de leitura de ticks do `sead::Framework` por uma chamada que consulta o registrador de alta resolução `CNTVCT_EL0` do processador ARM64.
- Converte a contagem de ciclos em tempo transcorrido real.
- Modifica as chamadas de avanço temporal do subsistema de atores (`ksys::act`) para multiplicar dinamicamente o vetor de translação e animação pela fração $\Delta t / (\frac{1}{30})$.
- **Resultado:** O jogo pode oscilar livremente entre 20, 35, 45 ou 60 FPS sem acelerar nem desacelerar a física.

#### 3. Disable Dynamic Resolution (DRS Off) & Render Resolution Scaling (RRS)
- **Disable DRS:** Trava a rotina de escalonamento dinâmico em 1.0f, impedindo que o motor reduza o viewport em áreas densas.
- **RRS Cheats (Presets de Resolução Customizada):** Modificam a tabela de alvos do renderizador no executável:
  ```text
  [Handheld RRS 75% (540p)]
  580F0000 02CC4100
  780F0000 000004C0
  680F0000 000002D0 00000500   ; 720, 1280 (Height 0x2D0, Width 0x500)
  580F0000 02CCEAE8
  780F0000 00001098
  640F0000 00000000 FFFFFFFF
  780F0000 00000118
  680F0000 3F400000 3F400000   ; Float 0.75f, 0.75f
  ```
  Permite selecionar resoluções internas fixas: **360p (50%)**, **480p (67%)**, **540p (75%)**, **720p (80% docked)**, **900p (100% docked)** e **1080p (120% docked)**.

---

### C. Emuladores Android (Skyline, Strato, EggNS)

Nos emuladores Android executando sobre processadores Snapdragon (Adreno 6xx, 7xx, 8xx):

1. **Ajuste para a Memória Tile da GPU (GMEM):**
   - As GPUs Adreno utilizam arquitetura *Tile-Based Deferred Rendering (TBDR)* com uma memória ultrarrápida no die chamada **GMEM** (4 a 12 MB).
   - O G-Buffer original de BOTW (1600x900 com 6 attachments RGBA16F/R16F) excede o limite da GMEM em resolução nativa, forçando *sysmem bypass* (leitura e escrita contínua na memória RAM LPDDR5X).
   - O uso de patches de **540p ou 720p** em dispositivos móveis permitia que os tiles coubessem inteiramente na GMEM, eliminando estrangulamento de largura de banda de memória e reduzindo o aquecimento térmico.
2. **Eliminação de Sincronizações de Fences Inúteis:**
   - O BOTW grava relatórios de tempo em buffers de comando (`CMD_REPORT_COUNTER`). No Android, o custo de sincronizar comandos com a timeline semaphore ou com fences de CPU causava gargalos severos na render thread.

---

### D. UltraCam / NX-Optimizer (MaxLastBreath)

O **UltraCam** representa o pacote mais sofisticado e moderno de otimização de BOTW e TOTK para Switch e emuladores, injetado via `subsdk3` (módulo ELF com ExLaunch) e configurado por `maxlastbreath.ini`:

| Recurso do UltraCam | Mecanismo Interno | Benefício de Desempenho |
|---|---|---|
| **MaxFramerate Dinâmico (20–240 FPS)** | Substitui o acumulador de delta-time do `sead` com interpolação contínua e desacoplamento do motor. | Permite taxas de quadros arbitrárias sem quebra de física. |
| **MenuFPS Separado (30 ou 60 FPS)** | Trava dedicada apenas quando telas de GUI (`UI::Menu`) estão ativas. | Evita menus acelerados ou ponteiros incontroláveis em taxas altas. |
| **Quality Improvements** | Desativa em nível de instrução: FXAA do motor, filtro FSR interno e Dynamic Resolution. | Elimina passes de shaders de pós-processamento pesados de tela inteira. |
| **Render Distance Scaling** | Modifica a constante global de distância máxima de visão (`1000` Very Low, `5000` Low, `12500` Medium, `25000` Default, `35000` High). | Reduz a contagem de draw calls do G-buffer de ~3.250 para ~1.800–2.000 (**ganho de até 25% a 30% de FPS**). |
| **FOV Customizável** | Ajusta a constante de campo de visão da câmera (`50.0` padrão) recalculando a matriz de projeção sem esticar a interface 16:9. | Permite visão mais ampla ou mais estreita conforme a preferência. |

---

## 3. Diagnóstico Atual do BOTWRecompAndroid no Hardware Alvo

Nos testes realizados no **Xiaomi POCO F7 Ultra (Snapdragon 8 Elite / Adreno 830v1)**:

- **Evolução de Desempenho no Santuário da Ressurreição:**
  - *Antes das otimizações:* **8.4 FPS**, 14.573 feedback copies e 8.767 passes a cada 2s.
  - *Com o suporte a `LDS`/`STS`, invalidação direcionada de UBOs (`guest_copy_invalidate`) e eliminação de snapshots redundantes:* **40 a 47 FPS estáveis** (tempo de frame médio de $\approx 25\text{ ms}$, redução de $57\times$ nas cópias por frame).
- **Gargalo Atual da GPU:**
  - A GPU trabalha $\approx 23\text{ ms}$ na cena 3D interna.
  - O perfilamento demonstra que o custo **não é preenchimento de pixels (fillrate)**, mas sim **número de passes e quantidade de draw calls**:
    - O G-buffer de 6 alvos executa sozinho **$\approx 3.250$ draw calls** ($\approx 7\text{ ms}$).
    - Há cerca de **220 a 230 pequenos passes** adicionais com barreiras completas.
- **Comportamento do Pacer:**
  - Em [`tools/patches.py`](file:///d:/Projetos/Jogos/Android/BOTWRecompAndroid/tools/patches.py#L38-L55), já substituímos a leitura de ticks em `0x7000c4f4d4` (`sead` `f_7000c4f430`) por `rt_frame_ticks` (em [`runtime/nvn/objects.c`](file:///d:/Projetos/Jogos/Android/BOTWRecompAndroid/runtime/nvn/objects.c#L1102-L1120)), travando a duração máxima em 1.85 vsyncs para impedir a queda para o intervalo 3 (20 FPS).

---

## 4. Guia de Aplicação Direta no BOTWRecompAndroid

Ao contrário de emuladores que dependem de injeção em memória em tempo de execução via JIT, o **BOTWRecompAndroid recompila o código AArch64 para C antecipadamente (`botwgame.so`)**. Isso nos dá controle absoluto para aplicar qualquer uma dessas correções diretamente em C puro.

### 1. Implementação de Dynamic FPS Nativo em C
Em vez de estarmos limitados a travas fixas de 30 FPS ou 60 FPS estáticos (onde 45 FPS causaria lentidão):
- No lifter ([`tools/patches.py`](file:///d:/Projetos/Jogos/Android/BOTWRecompAndroid/tools/patches.py)), podemos substituir o cálculo de delta-time por uma função de tempo real em [`runtime/nvn/objects.c`](file:///d:/Projetos/Jogos/Android/BOTWRecompAndroid/runtime/nvn/objects.c):
  ```c
  // Exemplo de integração em C no runtime
  float rt_dynamic_timestep(float nominal_dt) {
      static uint64_t last_ns;
      uint64_t now_ns = SDL_GetTicksNS();
      if (!last_ns) { last_ns = now_ns; return nominal_dt; }
      float delta_s = (float)((now_ns - last_ns) / 1e9);
      last_ns = now_ns;
      
      // Limites de segurança: previne estouro de física em travamentos (mínimo 20 FPS, máximo 120 FPS)
      if (delta_s > 0.050f) delta_s = 0.050f;
      if (delta_s < 0.008f) delta_s = 0.008f;
      return delta_s;
  }
  ```
- **Benefício:** O jogo passa a responder com velocidade $1.0\times$ perfeita em qualquer taxa (35, 42, 47 ou 60 FPS), eliminando completamente a sensação de arrasto ou aceleração.

### 2. Redução de Draw Distance / Constante de LOD (Inspirado no UltraCam)
- O BOTW carrega a distância máxima de visão de atores e objetos de terreno a partir de constantes escalares (o valor `25000.0f` documentado no UltraCam).
- Aplicar um patch via `tools/patches.py` reduzindo essa constante para `12500.0f` ou `15000.0f`:
  - Diminui a quantidade de chamadas de desenho do G-buffer em até **30% a 40%** (de $\approx 3.250$ para $\approx 2.000$ draws).
  - Reduz o tempo de GPU de $\approx 23\text{ ms}$ para **$\approx 15\text{ ms}$**.
  - **Resultado imediato:** Permite que o hardware alcance **60 FPS constantes** no Santuário e no Overworld.

### 3. Poda de Passes de Pós-Processamento e FXAA
- O BOTW executa passes de tela cheia para FXAA e blur de profundidade.
- No executor Vulkan ([`runtime/nvn/vulkan/exec.c`](file:///d:/Projetos/Jogos/Android/BOTWRecompAndroid/runtime/nvn/vulkan/exec.c)), já temos o mecanismo `LARECOMP_SKIP_FSHASH`.
- Ao identificar o hash do fragment shader responsável pelo FXAA do motor, podemos saltar o draw:
  - Elimina a leitura e escrita de tela inteira na GPU.
  - Economiza **1,5 a 2,5 ms** de tempo de renderização por quadro.
  - O anti-aliasing pode ser delegado com muito mais eficiência para o SMAA nativo ou FSR do próprio runtime no momento do present.

### 4. Resolução Interna Estática e Seletor no Menu (RRS)
- Atualmente, o BOTW desenha fixo em 1600x900 docked (ou 1280x720 handheld via `LARECOMP_HANDHELD=1`).
- Podemos expor uma opção nas configurações do Android (`Settings -> Resolução Interna`):
  - **720p (Handheld nativo):** Reduz a área de renderização do G-buffer em 36% em relação a 900p.
  - **540p (Modo Performance):** Permite encaixar o G-buffer perfeitamente na GMEM do Adreno, maximizando a eficiência de bateria e mantendo temperatura baixa.
  - O escalonador FSR 1 já presente no runtime do `exec.c` faz o upscale limpo do buffer de 540p/720p para a resolução nativa da tela do celular (3200x1440).

---

## 5. Matriz de Síntese e Prioridades de Otimização

| Mod / Otimização | Origem | Impacto Técnico | Prioridade no BOTWRecompAndroid |
|---|---|---|---|
| **Dynamic FPS Nativo** | FPS++ / BOTW-DFPS | Elimina câmera lenta quando o FPS fica entre 35 e 55 FPS; permite desbloquear 60 FPS com velocidade 1.0x contínua. | **Alta** (Experiência de jogo fluida) |
| **LOD / Render Distance Scaler** | UltraCam | Reduz o número de draw calls do G-buffer em 30–40%, aliviando o maior gargalo atual da GPU (23 ms $\to$ 15 ms). | **Alta** (Caminho direto para 60 FPS) |
| **Bypass de FXAA / Passes Redundantes** | Yuzu / UltraCam | Economiza 2 ms de GPU suprimindo passes de pós-processamento de tela cheia que são redundantes com o FSR/SMAA do runtime. | **Média** (Otimização limpa) |
| **Controle de Resolução (RRS 540p / 720p)** | Skyline / Cheats RRS | Reduz consumo de banda e encaixa passes na GMEM do Adreno, reduzindo aquecimento térmico em sessões longas. | **Média** (Bateria e estabilidade térmica) |
| **Correções Físicas Específicas** (Ragdolls / Água / Flechas) | FPS++ | Necessário apenas ao habilitar o Dynamic FPS acima de 30 FPS para evitar distorções em colisões e física da água. | **Dependente** (Após ativar Dynamic FPS) |

---
*Documento compilado e integrado ao repositório do projeto em `docs/MODS-DESEMPENHO-BOTW.md`.*
