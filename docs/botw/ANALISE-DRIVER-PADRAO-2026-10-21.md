# Driver padrão Qualcomm: análise e possíveis soluções

Data: 21/10/2026. Escopo: analisar a versão atual e preparar trabalho futuro, sem implementar correções. A execução no celular foi autorizada durante a análise.

**Atualização posterior, 21/10/2026:** o usuário autorizou corrigir os dois shaders e testar no celular. Essa etapa foi concluída; ver a seção final. O restante do relatório preserva a análise anterior à correção.

## Conclusão

O defeito visual foi reproduzido com o driver padrão: Link aparece quase inteiramente preto; terreno e árvores perdem a aparência normal, enquanto céu, interface e minimapa continuam visíveis. A captura não permite determinar sozinha se faltam texturas de materiais ou dados de iluminação.

A evidência mais forte desta execução é a falha de **dois pipelines de computação**, acompanhada de comandos pulados continuamente após terminar a compilação. A hipótese de falha geral no carregamento de texturas não foi confirmada: não houve mensagens de formato incompatível ou falha de criação de imagem na coleta. Isso não prova que todos os uploads e descritores estejam corretos.

Prioridade recomendada: identificar e corrigir os dois shaders que falham; conferir os dados que alimentam a iluminação; só então aprofundar formatos, uploads e desempenho. A ausência de memória compartilhada entre o jogo e a GPU torna necessário um caminho de cópias correto, mas não torna a renderização correta impossível.

## Versão e método

- Projeto em `4fc0d281105ee737fea6c05a31d299e9853fca3a`, com alterações preexistentes no workspace. O código gráfico consultado já contém arena residente (`gres`) e aprendizado de endereços (`gmiss`); não se deve propor implementá-los do zero.
- `runtime/nvn/vulkan/exec.c`: SHA-256 `23C8CF167EC94A88A65699DB707201D14AE6BCA06AA49AE08DFF6C0BE77ACFA8`.
- `runtime/nvn/vulkan/textures.c`: SHA-256 `F28820A1E7541D6A7E07606447043AA4F6967A738D68FA4B93DFCAC7FEDA9F49`.
- Celular POCO F7 Ultra, modelo `24122RKC7G`, Adreno 830. APK instalado informa `versionName=0.0.1`, `versionCode=1`, atualização `2026-10-21 12:31:37` conforme Android. Esses números não identificam sozinhos o commit do binário; não foi feita verificação binária de equivalência com o fonte.
- O jogo estava fechado. Foi aberto o APK existente, sem recompilar ou instalar, com `LARECOMP_NO_LAUNCHER=1`, `LARECOMP_VK_DRIVER=none`, `LARECOMP_VK_LIST_EXT=1`, `LARECOMP_GMISS=1` e um arquivo de log próprio. O usuário carregou o save e preparou a cena.
- `none` não é uma biblioteca válida: provoca o fallback explícito para `libvulkan.so`. O log confirmou `using the system driver`, Adreno 830 e o caminho específico Qualcomm. Não representa falha de inicialização do driver do sistema.
- Não foi alterada a preferência permanente de driver, nem código, APK ou parâmetros de correção. Não foram executados testes comparativos com outras opções nesta coleta. O jogo foi deixado aberto; a sessão normal pode realizar autosaves.
- Os tempos incluem instrumentação `GMISS`, estado térmico e atividade do aparelho; servem para caracterizar este teste, não como benchmark definitivo contra Turnip.

Evidências locais, não destinadas a commit:

- [Captura da cena](../build/driver-system-analysis-20261021/scene.png).
- [Log da execução](../build/driver-system-analysis-20261021/runtime.log).
- Log original no celular: `/sdcard/Android/data/com.botwrecomp.android/files/driver-system-analysis-20261021.log`.

## Resultados observados

| Evidência | Resultado | Interpretação e limite |
| --- | --- | --- |
| Driver e memória | Qualcomm; `no heap import`; arena de 128 MB iniciada | O caminho de cópias e arena está ativo; não é o heap colocado diretamente na GPU usado com Turnip. |
| Compute | Duas mensagens `[compute] pipeline failed: -13` | Falhas reais de criação. `-13` é `VK_ERROR_UNKNOWN`; não identifica a causa ou a função visual desses shaders. |
| Compilação gráfica | Nenhuma mensagem de falha de pipeline gráfico localizada | Não confundir falha de compute com falha de todo o renderizador. |
| Formatos e imagens | Nenhuma mensagem `not supported: RGBA8`, `image creation failed` ou `no BC textures` | Não houve evidência positiva desses fallbacks nesta coleta. |
| Amostra estável, t=80–110 s | 15 linhas de estatísticas; média simples de 16,1 FPS, faixa 14,6–17,9; zero novas compilações | As falhas visuais e perdas de comandos persistem com compilação estabilizada. |
| Comandos pulados na mesma amostra | 210–252 por intervalo de aproximadamente 2 s | O contador `stat_skipped` inclui dispatches de compute; não são necessariamente desenhos de textura pulados. |
| Volume contabilizado como upload | Média de 4.022 MB por intervalo de aproximadamente 2 s | Contador de alocações/cópias no caminho transitório; não significa 4 GB de texturas novas, nem mede diretamente tráfego físico de RAM. |
| Acessos fora da tabela | Até 94.189 acessos em um relatório; último registro da cópia coletada: 903 no frame 1604 | `gmiss` retorna zero nesses acessos e aprende regiões para quadros seguintes. Não é prova de textura inexistente. |
| Arena no frame 2400 | 23 regiões vivas, 126 entradas, 6,1/32 MB residentes, 10 regiões aprendidas | Nenhum fallback por falta de espaço, tabela cheia, anel ou tamanho excessivo nos relatórios coletados. Saturação é risco de robustez, não causa demonstrada nesta cena. |

Depois do frame 1604 não aparecem novos relatórios de misses no log copiado, mas a captura continua defeituosa. Isso reduz a força da hipótese de que apenas aumentar a cobertura aprendida resolveria a imagem. O aprendizado é amostrado e limitado, portanto ausência de novas mensagens também não certifica todos os acessos.

## Lista priorizada de trabalho

### 1. Identificar e corrigir os dois pipelines de computação — prioridade máxima

**Evidência:** falham nesta execução, e `dispatch_compute()` abandona o comando quando o pipeline não está pronto ou falhou. O log atual mostra apenas o resultado, sem endereço/hash do shader que falhou.

**Trabalho proposto:** enriquecer o diagnóstico de `build_compute()` com endereço/hash, dimensões locais, bindings e capacidades utilizadas; guardar GLSL/SPIR-V em pasta privada; validar SPIR-V e limites do dispositivo. Reproduzir isoladamente cada pipeline no Qualcomm, comparando com Turnip apenas como controle. Investigar fluxo de controle, tipos/operações de 64 bits, especialização do tamanho do grupo e pressão de recursos somente conforme o shader identificado.

**Possível solução:** ajustar a tradução Maxwell→GLSL/SPIR-V para o padrão que dispara a falha, com variante específica de compatibilidade quando necessária. Não tratar o código `-13` como diagnóstico de falta de memória ou de textura.

**Aceite:** ambos os pipelines são criados, seus dispatches executam, a saída contém os dados esperados e a cena melhora. Se não houver melhora visual, manter a correção de execução e investigar a etapa seguinte; ainda não está provado que ambos produzam iluminação.

**Referências locais:** `runtime/nvn/vulkan/exec.c`, `build_compute()` (~3920), `compute_program()` (~3947), `dispatch_compute()` (~3990); `runtime/nvn/maxwell/translate.c`.

### 2. Validar a continuidade dos dados entre compute e desenho — prioridade alta

**Evidência:** o histórico documenta a lista de luzes lida antes de o resultado do compute retornar ao heap. O código atual já tenta resolver isso mantendo regiões na arena da GPU. Houve misses e aprendizado reais no teste.

**Trabalho proposto:** acompanhar, para um quadro e um shader consumidor, o endereço convidado, slot residente, intervalo, versão, contador de luzes e conteúdo antes/depois do produtor. Incluir ponteiros indiretos e cabeçalhos anteriores ao storage buffer. Comparar o mesmo dado consumido na GPU, não apenas a cópia atrasada na CPU.

**Possível solução:** preservar uma representação coerente por intervalo entre todos os produtores e consumidores; ampliar o mapeamento conforme acessos necessários e evitar depender de vários quadros de aprendizado para uma leitura correta. Avaliar também dados usados como uniformes, vértices ou fontes de textura, que podem seguir outro caminho de cópia.

**Aceite:** o consumidor lê a saída atual do produtor na mesma sequência de comandos; a cena permanece correta ao andar e carregar outras áreas. A arena já existe e não apresentou saturação nesta execução.

**Referências:** `gcopy_prepare()` (~3401), `gres_prepare()` (~3230), `gres_learn()` (~3202), `gcopy_retire()` (~3021), `stage_set()`; `translate.c`, geração de `GMap`/`gmiss` (~2524).

### 3. Auditar sincronização e propriedade CPU/GPU — prioridade alta

**Evidência estática:** `gres_sync()` verifica mudanças uma vez por quadro e coloca cópias no command buffer de upload, executado antes do buffer principal. `gcopy_retire()` concilia modificações em blocos de 256 bytes. `GMISS` é lido/zerado pela CPU no buffer mapeado; é preciso conferir sua relação com submissions ainda em voo.

**Trabalho proposto:** verificar escritas da CPU dentro do mesmo quadro, ordenação de uploads frente a computes, sobreposição de regiões e leitura CPU de resultados da GPU. Registrar propriedades reais dos tipos de memória da arena e do anel. Nesses caminhos, o código não exige explicitamente `HOST_COHERENT` e não apresenta chamadas locais de flush/invalidate correspondentes a cada acesso.

**Possível solução:** rastrear versões por região/submission, usar barreiras e esperas nas dependências reais, usar buffers de readback protegidos por conclusão da GPU e flush/invalidate quando a memória escolhida não for coerente. Uma barreira GPU não recupera dados copiados da CPU antes de ficarem prontos. Memória coerente também não elimina a necessidade de ordenar acessos concorrentes.

**Limite:** o projeto já chama `vk_full_barrier()` em transições importantes. Não há prova de que simplesmente adicionar uma barreira após cada draw resolva o defeito. Coerência do tipo de memória ainda precisa de medição específica.

**Aceite:** nenhuma leitura antecipada ou sobrescrita de resultados válidos; validação de sincronização limpa e resultado estável com submissions divididas e normais.

### 4. Corrigir fallbacks de textura que descartam conteúdo — prioridade média, condicional

**Evidência estática concreta:** `textures.c` pode trocar um formato não suportado por RGBA8. Em `upload_texture()`, `uploaded=true` e o hash são atualizados antes do retorno que descarta formatos sem conversor BC/ASTC. Assim, existe um caminho que registra upload sem enviar texels. Não apareceu mensagem que comprove esse caminho no teste atual.

**Trabalho proposto:** contar uploads efetivos, conversões e retornos sem envio por formato/textura; consultar suporte à combinação completa de formato, uso, dimensões, flags, amostras e tipo de imagem. Capturar albedo, normal e targets intermediários para distinguir falta de material de iluminação preta.

**Possível solução:** implementar conversão correta para o formato efetivamente encontrado; marcar upload concluído apenas após a preparação bem-sucedida dos comandos; validar separadamente views, espaço de cor, swizzle, mips e camadas. Evitar usar RGBA8 como substituto universal de profundidade, inteiros ou outros formatos incompatíveis.

**Aceite:** os texels enviados e os amostrados correspondem ao conteúdo esperado. `LARECOMP_NO_BC=1` pode isolar o decodificador BC em um teste futuro, mas não é uma correção confirmada nem foi usado aqui.

**Referências:** `textures.c`, criação (~374–440), `upload_texture()` (~632–690), `vk_view()`.

### 5. Robustez da arena e das tabelas — prioridade média

**Evidência estática:** arena residente limitada a 32 MB/512 entradas; tabela `GMAP_MAX=16`; aprendizado `GL_MAX=16`; fila de retornos `gc_back[256]`. Algumas falhas voltam a cópias simples; `gcopy_prepare()` deixa a tabela vazia se o total superar `CHUNK_SIZE=64 MB`, produzindo leituras zero. O cadastro de retornos também pode deixar de ocorrer quando a fila está cheia.

**Possível solução:** instrumentar todos os limites, controlar reutilização pelo término da GPU, dividir intervalos/tabelas sem perder aliases e assegurar um fallback correto. Evitar juntar regiões distantes em uma cópia enorme somente para caber em 16 entradas. Auditar limpeza de filas e contadores no reboot interno.

**Aceite:** sessões longas e troca de áreas não provocam leituras zero silenciosas, crescimento sem recuperação ou perda de resultados. Não aumentar constantes indiscriminadamente: os contadores da cena atual não acusaram esses limites.

### 6. Cache, views e atualização de texturas — prioridade posterior

**Evidência estática:** uploads usam verificação por quadro, hash amostrado, varredura periódica e memoização de views. O aparelho confirmou ausência de rastreio de escrita de páginas. Alterações fora da amostra podem exigir detecção posterior; writes da GPU precisam ser tratados separadamente de writes da CPU.

**Possível solução:** invalidar por conteúdo/geração e sub-recurso quando conhecido; comparar testes individuais com `LARECOMP_TEX_FULLHASH=1`, `LARECOMP_VK_NO_TEX_MEMO=1` e `LARECOMP_NO_SET_CACHE=1`. Conferir aliases, reuso de descritores e feedback de render targets. Não executar todas as opções juntas, pois isso impede identificar a causa.

**Aceite:** texturas e alvos mudam no quadro correto, sem flicker ou regressão no Turnip. Esses testes não foram executados nesta análise.

### 7. Reduzir custo de cópias depois de recuperar a imagem — prioridade de desempenho

**Evidência:** aproximadamente 4 GB contabilizados em uploads por intervalo de 2 s, com cerca de 16 FPS nesta cena. A arena por si só não eliminou esse volume.

**Possível solução:** separar estatísticas de uniformes, vértices, índices, memória global e texels; reutilizar dados imutáveis ou sem alterações com invalidação correta; copiar somente regiões modificadas. Medir CPU de render, GPU, waits e apresentação separadamente. Repetir a medição sem diagnóstico depois de estabilizar a imagem.

**Aceite:** redução mensurável de bytes e tempo por quadro, mantendo equivalência visual e resultados dos shaders. Não prometer 60 FPS: o orçamento de CPU/GPU e a cadência do jogo são problemas adicionais.

## Caminhos já tentados que não são a primeira escolha

O histórico em [BOTW-PORT.md](BOTW-PORT.md), seção de medições do driver Qualcomm, registra tentativas desfeitas de `vkMapMemory` + `mremap`, importação/exportação `OPAQUE_FD` e AHardwareBuffer BLOB. Elas falharam no aparelho. Não foram repetidas aqui.

Reabrir essas alternativas exige uma diferença concreta de suporte ou implementação e um caso mínimo isolado. Forçar um mapeamento no endereço do heap pode comprometer a memória do jogo. A prioridade é tornar correto o caminho de cópias já disponível. O fato de uma extensão externa existir não garante a combinação de importação, formato e uso necessária.

## Sequência sugerida para implementação futura

1. Instrumentar e isolar os dois computes que retornam `-13`.
2. Corrigi-los e comparar a imagem na mesma cena.
3. Se a iluminação continuar incorreta, rastrear seus dados entre produtor e consumidor e auditar sincronização/coerência.
4. Se os dados estiverem corretos, verificar imagens intermediárias, uploads, formatos e descritores.
5. Validar movimento, interior/exterior, carregamento e reboot interno; depois otimizar cópias.

Um teste futuro com `LARECOMP_SAVE_MSL=1` pode guardar GLSL, mas o diagnóstico de erro ainda precisa associar cada falha ao shader. Os arquivos derivados do jogo devem permanecer privados, conforme `CONTRIBUTING.md`.

## Fontes técnicas consultadas

- [Khronos: VkResult](https://docs.vulkan.org/refpages/latest/refpages/source/VkResult.html): `-13` corresponde a `VK_ERROR_UNKNOWN`; o número sozinho não determina a causa.
- [Khronos: exemplos de sincronização](https://docs.vulkan.org/guide/latest/synchronization_examples.html): dependências entre produtor e consumidor devem cobrir estágios e acessos envolvidos; leitura CPU exige observar conclusão e visibilidade.
- [Vulkan Memory Allocator: mapeamento de memória](https://gpuopen-librariesandsdks.github.io/VulkanMemoryAllocator/html/memory_mapping.html): memória mapeada não implica coerência; cópias diretas precisam do tratamento de cache apropriado quando o tipo não é `HOST_COHERENT`.

As prioridades acima combinam evidência desta execução com inspeção de código. Nenhuma solução proposta foi implementada ou declarada validada nesta análise.

## Atualização: dois pipelines corrigidos e testados no Qualcomm

Após autorização para implementação, a instrumentação identificou os dois pipelines que retornavam `VK_ERROR_UNKNOWN`: endereços `36ec60000` e `36ee37000` na execução de diagnóstico. Eram duas instâncias do mesmo shader: GLSL idêntico, SHA-256 `d9da971da7bbbe7bb1bd76fa51106c6fbe5e9d86e25ad19c30c08f9ad68fd397`, grupo local `32×1×1`, dez bindings e 220.308 palavras de SPIR-V. Os endereços mudam entre sessões.

O SPIR-V original passou em `spirv-val --target-env vulkan1.3`. Um programa de diagnóstico independente reproduziu a rejeição no próprio celular, usando o Vulkan do sistema, a biblioteca shaderc do aplicativo e layouts derivados dos bindings. Isso permitiu testar a criação sem substituir shaders no jogo. O dispositivo reportou compilador `E031.47.18.49` e `/vendor/lib64/hw/vulkan.adreno.so`.

| Variante isolada | Resultado da criação |
| --- | --- |
| Original, busca binária com cache da última região | `-13` |
| SPIR-V otimizado com `spirv-opt -O` | `-13` |
| Amostragem de sombra removida, apenas diagnóstico | `-13` |
| Tamanho de grupo fixo em vez de especializado | `-13` |
| Fluxo de controle estruturado pelo tradutor existente | `-13` |
| Busca linear sem cache | `VK_SUCCESS` |
| Busca linear com cache da última região | `VK_SUCCESS` |
| Busca binária sem cache | `VK_SUCCESS` |

O gatilho isolado é a combinação da busca binária com o cache nesse shader grande; não foi identificado o defeito interno do compilador Qualcomm. A correção usa a busca linear com o mesmo cache. Ela resolve os mesmos intervalos não sobrepostos de `GMap`, mantém a identificação de misses e não remove instruções, texturas ou cálculos do jogo.

### Alterações

- `runtime/nvn/maxwell/shader.h`: opção `ShaderKey.gmem_linear_lookup`.
- `runtime/nvn/maxwell/translate.c`: variante de consulta linear que preserva o cache da última região.
- `runtime/nvn/vulkan/exec.c`: ativa a variante somente nos shaders de computação do driver proprietário Qualcomm no Android. Gráficos e Turnip mantêm a seleção anterior. O cache SPIR-V já depende do texto gerado, portanto não foi apagado o cache do usuário.
- Diagnóstico de compute agora identifica endereço, resultado, tamanho local, tamanho do SPIR-V e bindings. `LARECOMP_LOG_CS_PIPELINES=1` registra também sucessos; com `LARECOMP_SAVE_MSL=1`, falhas guardam o SPIR-V ao lado do GLSL. O módulo Vulkan temporário é liberado após criar o pipeline.

### Validação no jogo

- `assembleDebug --offline --console=plain`: sucesso. Permanece um aviso preexistente de descarte de `const` em `g_dump_sampled`, fora desta correção.
- APK instalado no celular e executado com o driver padrão. Foi criada e usada a cópia `save-shader-fix-20261021`, separada dos saves normais.
- Na sessão corrigida, as duas instâncias apareceram em `36eb8c000` e `36edef000`, ambas com `result 0`, `32×1×1`, dez bindings e 220.229 palavras de SPIR-V.
- Os dois SPIR-V gerados pelo aplicativo foram recuperados de seu cache e passaram individualmente em `spirv-val --target-env vulkan1.3`.
- Conferida também a equivalência textual entre a saída do tradutor corrigido e a variante aceita no teste isolado, desconsiderando apenas comentários de diagnóstico.
- Depois de terminar a compilação, as estatísticas passaram a registrar **zero comandos pulados**, com centenas de dispatches executados por intervalo. Na janela t=84–96 s, foram observados aproximadamente 11,5–14,7 FPS. Não é comparação de desempenho controlada: o aparelho estava mais quente e agora executava trabalhos antes descartados.
- A primeira compilação de alguns pipelines demorou dezenas de segundos; não há promessa de melhora no tempo de carregamento.

**Limite do resultado:** a captura ainda mostra Link como silhueta e o cenário sem a iluminação normal. Os dois erros de pipeline foram corrigidos; a falha visual completa permanece aberta. A próxima prioridade é rastrear os dados de iluminação entre compute e desenho, incluindo os caminhos de cópia e sincronização. Não houve alteração desses caminhos nesta etapa.

Evidências privadas em `build/shader-fix-20261021/`: `baseline.log`, `corrected.log`, `corrected.png`, `isolated-results.json`, fontes e binários dos shaders e do diagnóstico. Não adicionar dumps ou shaders derivados do jogo ao Git. A sessão deixada no celular usa os saves de teste.

## Atualização: causa da imagem sem luz encontrada e corrigida

Continuação da análise acima, mesma data. O passo 3 da sequência sugerida (rastrear os dados de iluminação) foi feito primeiro por exclusão e depois comparando passes.

1. **O modo de cópia não é a causa.** O Turnip com o heap compartilhado desligado (`LARECOMP_VK_NO_PLACED=1`) cai no mesmo caminho de cópias e arena (`gcopy`/`gres`, ~6 GB contabilizados a cada 2 s) e mostra a cena iluminada normalmente. O defeito é específico do driver da Qualcomm.
2. **Comparação passe a passe.** Quadro de depuração 2200 nos dois drivers (`LARECOMP_DEBUG_FRAME=2200`, com `LARECOMP_VK_SPLIT_US=0` no Turnip para os passes coincidirem), alinhados pelos programas de cada passe. O G-buffer sai igual nos dois, mas a composição da luz na Qualcomm não tem albedo.
3. **Causa.** O G-buffer do BOTW liga a mesma imagem em dois slots de cor do mesmo passe: albedo (`f0x38`) nos slots 1 e 2, normais (`f0x25`) nos slots 3 e 4. O jogo escreve só um slot de cada par (máscara de canais `f0f0f0ff`). Num tiler, cada slot tem sua cópia na memória de tiles e as duas são gravadas no fim do passe. No driver da Qualcomm, a cópia do slot mascarado, com o conteúdo antigo, chega por último e apaga o albedo. Resultado: Link em silhueta e cenário só com neblina. No Vulkan, duas attachments com a mesma imagem escritas no mesmo passe têm resultado indefinido; o Turnip só acerta a ordem por acaso.
4. **Correção** (`exec.c`, `begin_pass`): quando a mesma view aparece em mais de um slot, fica ligado só o slot que a máscara de canais escreve; os outros ficam `VK_NULL_HANDLE`, com formato `UNDEFINED` no pipeline. Se um draw posterior do mesmo passe escrever num slot desligado, o passe recomeça (`pass_dup_null`).
5. **Também corrigido, sem efeito visível nesta cena:** um sampler com comparação de profundidade usado numa leitura sem comparação (`texture()` comum) é indefinido no Vulkan. Agora essas leituras usam uma variante do sampler sem comparação (`vk_sampler_plain`, `textures.c`). Falso positivo no caminho: o passe de profundidade linear parecia zerado só por um desalinhamento de um passe na primeira comparação.
6. **Diagnóstico novo:** em quadros de depuração, a profundidade amostrada direto no passe é salva como `build/fbdepth_NNN_inplace.pgm`. `debug_save_depth` passou a aceitar `D32_SFLOAT`.

**Validação no celular:** com o driver da Qualcomm, a cena do platô fica com albedo, luz e Link normais, a ~13–15 fps (o modo de cópia continua lento). O Turnip continua igual, a 30 fps. Nenhum reinício do sistema registrado no `dropbox` durante os testes.

**Próximo passo no driver da Qualcomm:** desempenho. Render ~34 ms por quadro, dominado pelas cópias do modo de cópia (item 7 acima). Também vale conferir outras cenas (interiores, santuários, água) com esse driver.

## Atualização: desempenho no modo de cópia (rastreio de escrita e cache de vértices)

Perfil da thread de render no driver da Qualcomm (simpleperf, platô): ~37 ms por quadro, 22% em `memcpy` vindo de `guest_copy`. Medição das cópias: ~120 MB por quadro, ~90 MB disso em **vértices e índices** copiados a cada quadro. No Turnip eles são lidos direto do heap (`guest_direct`); o cache de vértices (`vc_*`) existia, mas exige rastreio de escrita de páginas, que o Android não tinha.

- **Rastreio de escrita no Android** (`runtime/platform/android.c`, só no modo de cópia sem memória placed):
  - `plat_written_pages` deixa as páginas consultadas somente leitura. A primeira escrita do jogo gera SIGSEGV; o tratador marca a página como escrita e a libera.
  - Cobre também uma escrita que cruza a fronteira entre duas páginas.
  - Os `pread` do `fs` liberam antes as páginas de destino (`plat_write_prepare`), porque uma escrita do kernel não gera falta, devolve EFAULT.
  - `LARECOMP_NO_WRITE_TRACKING=1` desliga.
- **Cache de vértices no modo de cópia:** preenchido pela CPU, só as páginas escritas, pelo buffer de upload (`vc_flush`).
- **Resultado:**
  - ~12.500 acertos de cache por quadro, ~30 KB atualizados por quadro e ~23 faltas de página por quadro.
  - Cópias de UBO: ~15 MB por quadro, igual ao Turnip.
  - **Thread de render: ~37 → ~18,5 ms.** Imagem correta.
- **Também:** cada slot de submissão tem seu próprio command pool, reiniciado inteiro com `vkResetCommandPool`. Antes, o reset implícito no `vkBeginCommandBuffer` devolvia e realocava memória do driver.
- **Por que o fps quase não subiu (~15–17):** o quadro é serial. O jogo espera a fence do quadro que acabou de enviar; a thread de render traduz (~18 ms); só então a GPU trabalha (~17–26 ms). Tudo somado passa de 33 ms e arredonda para 3–4 vsyncs.
  - O envio em pedaços (`LARECOMP_VK_CHUNK_US`), que no Turnip sobrepõe tradução e GPU, aqui dobra o tempo ocupado da GPU (~38 ms) e não muda o período, provavelmente porque o clock da GPU cai nos intervalos.
  - Próximos candidatos: o custo do próprio driver por draw (`vkUpdateDescriptorSets` e `vkAllocateDescriptorSets` ~18%, `vkCmdBeginRendering` ~9%, `vkCmdDrawIndexed` ~10%) e entender a queda de clock com envio em pedaços.

## Atualização: custo por draw (uniform buffers dinâmicos)

- **Mudança:** os uniform buffers dos programas gráficos passaram a ser `UNIFORM_BUFFER_DYNAMIC` (Android, até o limite do dispositivo; primeiro os buffers do jogo, depois as tabelas do runtime).
  - O offset de cada cópia sai do descriptor set e vai para o `vkCmdBindDescriptorSets`.
  - Antes, cada draw copiava seus UBOs para um lugar novo e quase nenhum set coincidia com o anterior: um set alocado e escrito por estágio por draw.
  - `LARECOMP_NO_DYN_UBO=1` desliga.
- **Resultado (A/B no mesmo build, platô):**

| Driver | Tempo em `sets` a cada 2 s | Render por quadro | GPU por quadro |
|---|---|---|---|
| Turnip | 307 → 234 ms | sem mudança mensurável (com o profiler ligado) | igual |
| Qualcomm | 198 → 105 ms | 21,4 → 19,1 ms | igual |

  Imagem correta nos dois.
- **Contagem nova no log de estatísticas** (`binds N pipe N sets`), Turnip, por quadro: ~5.100 draws, ~850 trocas de pipeline e ~4.050 binds de descriptor set. Cada objeto tem constantes próprias, então os offsets mudam a cada draw.
- **O custo de GPU por draw (~2 µs) não mudou.** Timestamps por draw (`LARECOMP_GPUPROF_DRAWS`) dão mediana ~8 µs e mínimo ~4 µs, mas serializam a GPU e não separam o custo do bind. Ficou aberto se vale separar os UBOs (que mudam a cada draw) das texturas em sets próprios.
- **Experimento (revertido): UBOs em sets próprios.**
  - **O que foi feito:** os buffers de constantes do jogo foram para os sets 2 e 3, separados das texturas (sets 0 e 1), e os sets de texturas só eram religados quando mudavam.
  - **Turnip, platô:** binds de set de texturas de ~4.050 → ~910 por quadro, com os UBOs religados a cada draw. A GPU ficou igual: 22,8 ms contra 22,1 ms sem a separação, dentro do ruído.
  - **Conclusão:** o custo de GPU por draw não está no bind de descriptor sets. O próximo suspeito é o próprio trabalho por draw no shader (por exemplo, as leituras de constantes via `cbr` com `min` por acesso) ou o custo fixo de draw do Turnip.
- **De onde vem o custo de GPU por draw (Turnip, G-buffer, quadro 2400, `[gpuprof]`).** Chaves de teste temporárias, removidas depois:

| Condição | G-buffer |
|---|---|
| Normal | 8,4–8,8 ms, ~3.600 draws |
| `TU_DEBUG=sysmem` | 8,9 ms |
| `TU_DEBUG=gmem` | 22,9 ms |
| Scissor de 1 pixel em todos os draws | 10,4 ms |
| Só 3 vértices por draw | **5,7 ms** |
| Sem rebind de sets dentro do mesmo layout | ~2,2 µs por draw, igual ao normal (o jogo passa a desenhar ~2.650 objetos, porque a imagem errada muda o culling) |

  - O Turnip já renderiza o G-buffer direto na memória; forçar tiles (GMEM) piora muito.
  - Quase nada é trabalho de pixel. O scissor de 1 pixel até piora, pela troca de scissor a cada draw.
  - O custo se divide em ~1,6 µs fixos por draw (~5,7 ms) mais ~3 ms de trabalho de vértice.
  - Os binds de set não explicam o custo fixo.
  - **Conclusão:** o custo fixo é do próprio Turnip por draw (estado, busca de vértices), e só cai com menos draws (LOD/distância do jogo) ou com shaders de vértice mais leves.
- **Vertex shaders do G-buffer (Turnip).** Li o GLSL dos seis programas com mais draws (`LARECOMP_SAVE_MSL=1`).
  - O trabalho é o do próprio jogo: transformações em FMA, normalizações e, em alguns, skinning por constantes indexadas e leituras de textura no vertex shader.
  - Não há tabelas `gfind` nem memória global nesses shaders.
  - O único acréscimo do tradutor por leitura é o `min(layer, textureSize(t, 0).z - 1)` nas texturas array, redundante no Vulkan, que já faz esse clamp ao amostrar.
  - **Teste:** tirar esse clamp, com A/B alternado e média de 250 quadros: GPU 23,0 / 23,5 / 23,1 ms. Sem efeito; revertido.
  - **Conclusão:** do lado do tradutor não há o que enxugar por vértice que pese. O que resta na GPU é o trabalho do jogo mais o custo fixo por draw do Turnip.
