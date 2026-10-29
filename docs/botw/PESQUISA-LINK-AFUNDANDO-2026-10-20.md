# Pesquisa: Link afundando no terreno em BOTW

Data da consulta: **20/10/2026**, horário de referência America/Sao_Paulo.

Escopo solicitado: pesquisar emuladores de Switch e o port de BOTW para Mac/iOS, documentando relatos, soluções e relevância para este projeto. **Somente pesquisa e este registro: não foram alterados código, configurações, APK, saves ou arquivos do jogo; nenhum teste novo foi executado no celular.**

## Resultado principal

Há relatos semelhantes com pelo menos duas soluções confirmadas pelos próprios autores: desativar **Reactive Flushing** no Eden e selecionar **Dynarmic (JIT)** como backend de CPU. Isso não identifica uma causa única para todos os casos e não fornece um patch diretamente aplicável ao nosso recompilador.

No port Mac/iOS consultado não encontrei um relato público equivalente nos materiais examinados. Esse projeto usa **BOTW de Wii U**, não a revisão Switch usada aqui. A ausência de relatos em um projeto recente não demonstra ausência do defeito.

Os registros locais já investigaram parte da hipótese de coerência CPU/GPU. A pista local mais específica continua sendo a diferença entre a altura do ator e a raiz do modelo, possivelmente relacionada à consulta de chão/ajuste da animação. **A causa local permanece não confirmada.**

## Visão comparativa

| Projeto/caso | Evidência encontrada | Solução ou situação documentada | Aplicabilidade aqui |
| --- | --- | --- | --- |
| Eden, issue #672 | Pernas abaixo do terreno | Reactive Flushing desligado; autor confirmou | Comparar coerência de memória; não copiar configuração |
| Eden, relato de usuário | Afunda parado/agachado na grama | Dynarmic/JIT; autor confirmou | Investigar diferenças na execução da CPU |
| Yuzu, relato de 2023 | Afundamento e colisão da água | Leitura integral indisponível nesta pesquisa | Pista histórica, sem solução validada |
| Ryujinx, PR #3302 | Nota técnica menciona possível relação com nadar no ar | Correção de sincronização de texturas | Precedente relacionado, não o mesmo defeito demonstrado |
| Sudachi, Suyu, Citron | Buscas por sintomas equivalentes | Não localizei caso específico verificável com correção | Resultado inconclusivo |
| Skyline/Strato e Ryubing | Buscas por sintomas equivalentes | Não localizei caso específico verificável com correção | Resultado inconclusivo |
| BotWRecomp Mac/iOS | README, documentação, patches e issues consultados | Nenhum relato equivalente encontrado | Arquitetura e executável diferentes |
| Cemu, Wii U | Changelog técnico e documentação de timestep | Readback/cache/JIT e limitações acima de 30 FPS | Comparação técnica, não emulador de Switch |

“Não localizei” significa apenas resultado das buscas e fontes acessíveis na data, não garantia sobre todo o histórico desses projetos. As fontes e ressalvas de cada linha estão abaixo.

## 1. Eden: Reactive Flushing

Fonte primária: [issue #672 — pernas abaixo do terreno](https://github.com/eden-emulator/Issue-Reports/issues/672).

Relato aberto em 21/09/2026: BOTW Switch 1.6.0, Eden Nightly `df05d3de23`, Android, Adreno 830. O autor observou o defeito a aproximadamente 30 FPS e informou que a base 1.0.0 não o apresentava. Diversas trocas de driver e ajustes gráficos não resolveram.

Em 27/09 foi sugerido desativar **Reactive Flushing**. Em 28/09 o autor confirmou a resolução; a issue está fechada. É um resultado confirmado naquele ambiente, sem patch causal identificado no relato.

**Relevância:** prioriza observar coerência de dados antes de presumir defeito exclusivo de driver. **Limite:** nosso caso já ocorre em Switch 1.0.0, e o runtime não é o Eden. Desligar genericamente todo readback não equivale necessariamente à opção dessa issue.

## 2. Eden: backend de CPU

Fonte primária, relato e confirmação dos usuários: [Link afunda quando fica parado](https://www.reddit.com/r/EdenEmulator/comments/1r6ni3m/in_zelda_breath_of_the_wild_link_sinks_into_the/).

Publicação de 16/02/2026: o jogador descreve Link afundando até a canela ao parar na grama, mas não sobre rocha. Em 02/05/2026 outro usuário relata o mesmo parado/agachado e recomenda selecionar **Dynarmic (JIT)** em configurações avançadas/debug. O autor confirma que funcionou e atualiza a postagem.

**Força da evidência:** confirmação prática de usuários, sem diagnóstico técnico do mantenedor ou comparação controlada de instruções. O backend anterior não foi documentado com precisão suficiente para afirmar que era NCE. Não concluir “bug de NCE” a partir disso.

**Relevância:** a diferença entre terreno e objetos rígidos se parece com o registro local. É uma pista para semântica de CPU, ponto flutuante e execução das consultas de chão. Este recompilador estático não possui uma opção equivalente a trocar para Dynarmic; a solução não é diretamente transferível.

## 3. Yuzu e derivados: limites da evidência

Foi localizado o relato de março de 2023 [“zelda botw - I'm sinking in the ground and water have no colission”](https://www.reddit.com/r/yuzu/comments/1236971/). O índice da busca apresenta o sintoma e uma comparação com Ryujinx, mas a página completa não pôde ser recuperada. **Não uso esses trechos para afirmar causa, solução ou superioridade de outro emulador.**

Buscas também incluíram Sudachi, Suyu, Citron, Skyline/Strato e Ryubing, combinados com BOTW, Link, feet, sinking, floor e ground. Não surgiu outro relato específico suficientemente verificável com correção confirmada. Foram descartadas páginas genéricas de desempenho, relatos de TOTK e resultados sem relação com o problema.

Não há base nesta pesquisa para recomendar instalar um fork ou driver aleatório como solução para o nosso código.

## 4. Ryujinx: precedente de sincronização de texturas

Fonte: [commit original preservado em espelho, PR #3302](https://git.axenov.dev/Museum/ryujinx/commit/d64594ec7476782a4254502dd0e36b8f1f49e381?show-outdated=&style=unified&whitespace=show-all), de 29/04/2022. Consulta adicional ao [changelog preservado](https://git.renil.cc/renil/Ryujinx/wiki/Changelog.md), versão 1.1.107.

O commit corrige o acompanhamento de ações de sincronização de texturas: uma flag podia continuar indicando uma ação registrada após ela ter sido consumida; havia também uma corrida no registro e problemas com a numeração da última sincronização. O comentário do autor menciona **possível** efeito sobre episódios de BOTW nadar no ar.

**Distinção:** a correção existe, mas a relação com BOTW foi descrita como possibilidade, não confirmação. Nadar no ar também não demonstra a mesma falha de posicionamento do modelo. A fonte é um espelho do código/histórico do autor original; não é uma nova investigação do mantenedor do espelho.

Como pista para o runtime, interessa a disciplina de invalidação, sincronização consumida e rearmamento. Não há evidência suficiente para aplicar esse diff neste projeto.

## 5. Port de BOTW para Mac e iOS

Projeto identificado: [IshaanKalra2103/BotWRecomp](https://github.com/IshaanKalra2103/BotWRecomp).

Revisão examinada: `2b84b82dbe7efe41707d236fdbac9ec5e98c8d3d`, commit de 17/10/2026. A API pública retornou um commit e uma issue, incluindo abertas e fechadas, na consulta de 20/10. Isso delimita um histórico público muito pequeno.

O [README dessa revisão](https://github.com/IshaanKalra2103/BotWRecomp/blob/2b84b82dbe7efe41707d236fdbac9ec5e98c8d3d/README.md) descreve recompilação **PowerPC → C++ do Wii U**, runtime Cafe OS e camada gráfica Latte do Cemu via Metal, para iPhone e Apple Silicon. Exige o executável USA atualizado **v208**; o README o chama de 1.6.0. Aqui o alvo é **ARM64 do Switch 1.0.0**, com NVN/Vulkan no Android. Números de versão entre essas plataformas não identificam executáveis intercambiáveis.

Materiais consultados:

- [Issues públicas](https://github.com/IshaanKalra2103/BotWRecomp/issues) e [issue #1](https://github.com/IshaanKalra2103/BotWRecomp/issues/1): a única issue retornada pergunta sobre Android/código aberto, sem relatar afundamento.
- [Notas de iOS](https://github.com/IshaanKalra2103/BotWRecomp/blob/2b84b82dbe7efe41707d236fdbac9ec5e98c8d3d/ios/PORTING.md): descrevem teste em um iPhone, travamento após alguns minutos e problemas de trabalho da GPU em segundo plano. Não encontrei nelas o defeito de Link enterrado.
- [TODO](https://github.com/IshaanKalra2103/BotWRecomp/blob/2b84b82dbe7efe41707d236fdbac9ec5e98c8d3d/todo.md) e [renderizador nativo experimental](https://github.com/IshaanKalra2103/BotWRecomp/blob/2b84b82dbe7efe41707d236fdbac9ec5e98c8d3d/docs/native-renderer.md): o renderizador experimental ainda não desenha o mundo completo. Essa limitação não equivale ao bug pesquisado.
- [Patches de FPS](https://github.com/IshaanKalra2103/BotWRecomp/blob/2b84b82dbe7efe41707d236fdbac9ec5e98c8d3d/runtime/src/game/fps.cpp) e `runtime/src/game/hooks.h`: há ajustes de pacing/timestep e proteção de determinadas cutscenes a 30 FPS. Não encontrei nesses arquivos um patch específico para pés/Foot IK/afundamento.

**Conclusão limitada:** não encontrei relato equivalente nem solução publicada nos materiais examinados. Não é possível atestar, só pelo README ou por ausência de issues, que o port esteja livre desse bug. Endereços PowerPC e patches GX2 não podem ser copiados para o nosso executável ARM64/NVN.

## 6. Cemu: comparação histórica e timestep

Cemu emula **Wii U**, portanto esta seção é comparativa.

O [changelog oficial 1.7.4](https://cemu.info/changelog/cemu_1_7_4.txt), com lançamento público em 09/04/2017, registra suporte experimental a leitura de texturas de volta à CPU, correção de corrupção no cache de vértices/uniformes e remoção de uma otimização JIT insegura que podia eliminar escritas de ponto flutuante. **O changelog não associa explicitamente uma dessas mudanças ao pé de Link.** Não é correto concluir que o problema histórico tinha uma única causa GPU demonstrada por essa lista.

A [wiki do próprio projeto, seção FPS++](https://wiki.cemu.info/wiki/The_Legend_of_Zelda:_Breath_of_the_Wild#Issues_caused_by_FPS++_(Playing_over_30FPS)), documenta dependências de timestep e problemas de física/colisão ao ultrapassar 30 FPS. Recomenda 30 FPS, modo estático ou desativar FPS++ para separar essas causas.

**Aplicação:** controlar timestep é um bom teste comparativo futuro, mas o registro local já relata afundamento persistente com limitação a 30 FPS. O texto da wiki contém orientações históricas de versões; não constitui especificação da revisão Switch usada aqui.

## 7. Confronto com as evidências já registradas neste projeto

Fonte local: [BOTW-PORT.md](BOTW-PORT.md), especialmente os registros “Link afundando” e “Link e NPCs afundando”, de 19–20/10/2026. Os valores abaixo são **anotações pré-existentes**, não novas medições desta pesquisa.

- O defeito também afeta ancião/Bokoblin e varia conforme o local. Sobre um tronco, o contato dos pés foi registrado como correto.
- Há comparação entre posição do ator, alturas `.hght`, array de alturas da GPU e malha de colisão decodificada. Para o ponto de referência, essas alturas ficam próximas de **214,352 m**.
- A raiz do modelo de Link foi registrada em **213,798 m**, cerca de **0,554 m abaixo do ator**.
- O histórico relata testes sem efeito com readback/writeback de certas classes de render targets, execução gráfica síncrona e limite de 30 FPS.
- As anotações de coerência CPU/GPU descartam caminhos e recursos específicos examinados. Não demonstram que toda forma de aliasing, sincronização ou leitura GPU→CPU do runtime esteja correta.
- Os testes diferenciais do lifter contra Unicorn não apresentaram falhas nos conjuntos anotados. Isso reduz a suspeita de erros cobertos por eles, mas não prova correção de toda sequência de execução, estado FP, concorrência ou interação com memória.

O documento local contém hipóteses históricas diferentes, inclusive uma atribuição ao terreno desenhado seguida do achado sobre a raiz do modelo. Não transformei essas etapas em uma conclusão causal única. O dado mais discriminante é **ator/colisão próximos entre si e modelo abaixo**, se confirmado em nova captura sincronizada.

## 8. Hipóteses e possíveis caminhos futuros — não executados

Esta ordem é uma avaliação da pesquisa e das notas locais, **não uma lista de correções comprovadas**.

| Prioridade | Hipótese | Observação futura que distinguiria a causa | Possível ação somente após confirmação |
| --- | --- | --- | --- |
| Alta | Consulta de chão usada por IK/ajuste da raiz difere do contato do controlador físico | Comparar no mesmo frame a altura do ator, raiz, pés e resultados dos raios sobre terreno e tronco | Corrigir retorno, filtro, transformação ou leitura da consulta demonstradamente errada |
| Alta | Diferença entre consulta ao terreno comprimido Havok e StaticCompound | Verificar hit/miss, fração do raio, posição, normal e material em ambas as classes de superfície | Corrigir o caminho específico da consulta; não elevar globalmente o personagem |
| Média | Semântica de execução CPU/FP em algum caminho não coberto | Traçar a primeira divergência na cadeia que calcula o deslocamento vertical; comparar FPCR/FPSR, NaN, arredondamento e instruções envolvidas | Corrigir a operação ou preservação de estado que divergir |
| Média | Coerência, alias ou dados obsoletos CPU/GPU fora dos recursos já testados | Identificar um consumidor real de dados desatualizados e sua sincronização esperada | Ajustar invalidação/readback/fence somente nesse caminho |
| Baixa, como repetição | Timestep/FPS como causa exclusiva | Medir delta do motor, não só contador de FPS, no mesmo save/cena | Ajustar o tempo do motor caso haja relação reproduzível |
| Baixa inicialmente | Driver, cache de shaders ou versão do jogo | A/B controlado com mesmas entradas e revisão suportada | Investigar backend/driver ou suporte de revisão, se a diferença for demonstrada |

Sobre a pista de CPU: o relato de Dynarmic torna a investigação pertinente, mas não demonstra que o nosso lifter tenha o mesmo erro. Sobre a pista gráfica: a solução do Eden não invalida os descartes locais já documentados, nem autoriza extrapolá-los para recursos que não foram observados.

Não recomendo como correção elevar Link por uma constante, desativar IK permanentemente, trocar o dump por uma revisão não suportada ou importar patches do Wii U. Isso pode apenas mascarar o sintoma, afetar outras superfícies/personagens ou quebrar assinaturas de código.

## 9. Critérios para uma futura validação

Quando houver autorização para investigar ou modificar, uma correção deveria ser comparada na mesma cena/save em terreno plano, inclinação, rocha e objeto estático; parado, andando, agachado, pulando e após recarregar. Incluir Link e pelo menos um NPC afetado.

Registrar revisão do jogo, build do runtime, configuração de execução, FPS e delta do motor, coordenadas do ator, matriz da raiz e consulta de chão relevante. O sucesso visual deve acompanhar coerência desses dados e não criar flutuação, colisão incorreta ou deslocamento de outros personagens.

## 10. Rastreabilidade e limitações da pesquisa

As referências nos itens 1, 2 e 5 são relatos/documentação dos próprios projetos ou autores; o item 4 usa código e mensagem de commit originais preservados por terceiros. O item 6 usa changelog e wiki do Cemu. Não foram usadas páginas de download de jogos, caches ou pacotes de terceiros como evidência técnica.

A API de issues consultada para Mac/iOS foi [state=all](https://api.github.com/repos/IshaanKalra2103/BotWRecomp/issues?state=all&per_page=100); o histórico foi [commits](https://api.github.com/repos/IshaanKalra2103/BotWRecomp/commits?per_page=100). O resultado se refere à visibilidade pública naquela data, sem acesso a Discord, mensagens privadas ou testes em hardware Apple.

Termos principais: `Breath of the Wild Link sinking into ground`, `BOTW feet terrain`, `BOTW NCE Dynarmic`, `reactive flushing`, `Ryujinx air swimming texture sync`, além das combinações com os nomes dos emuladores e do port Mac/iOS.

Nenhum dos relatos externos, isoladamente, identifica a causa do bug deste projeto. Este arquivo registra as evidências e próximos caminhos possíveis; **não aplica uma solução**.
