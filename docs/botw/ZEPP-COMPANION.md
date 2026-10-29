# Companion ZeppOS

A implementação atual está em [companion/zepp](../companion/zepp/README.md).

A versão 0.2.0 mantém o mapa no Amazfit GTR 4 New e recebe coordenadas, direção de Link, pinos/carimbos e indicadores da missão selecionada. A coroa controla quatro níveis de zoom; o toque move o mapa e o botão **Centrar Link** retoma o acompanhamento.

```text
Leitura de estado no runtime → HTTP autenticado em loopback
    → Side Service do Zepp no mesmo Android → Bluetooth → mapa local no relógio
```

O alvo é deviceSource 9437441, 480×480 e API 1.0, conforme o catálogo instalado do Zeus. A ponte nativa está em `runtime/watch_map.c`; o servidor e a ativação ficam em `WatchMapServer.java`. Somente a revisão Switch 1.0.0 validada é aceita. Dados extraídos do dump local ficam fora do Git.

O README contém instruções de exportação, instalação, controles, protocolo, verificações realizadas e limites. O protótipo anterior de captura de minimapa fica disponível como diagnóstico legado.
