# Artes TOTK / Zonai — v1

Geradas com a ferramenta integrada image_gen. Artes originais inspiradas em The Legend of Zelda: Tears of the Kingdom.

- `hyrule-sky-islands-background-v1.png`: fundo do launcher, paisagem de Hyrule com ilhas flutuantes, 1672 × 941.
- `zonai-touch-atlas-v1.png`: atlas RGBA de controles, quatro células em grade 2 × 2. Superior esquerda: botão circular; superior direita: botão de ombro; inferior esquerda: base do analógico; inferior direita: disco do analógico.
- `zonai-menu-concept-v1.png`: referência visual do menu, 1672 × 941. Os textos e controles desta imagem são ilustrativos; a interface funcional deve ser desenhada no runtime.

Direção visual: pedra verde-escura, energia jade, dourado envelhecido, espirais geométricas Zonai e texto marfim.

## Integração Android

O build empacota a paisagem como `assets/res/launcher-background.png` e os controles como `assets/res/zonai-touch-atlas.png`. O runtime também aponta para os novos arquivos no checkout. O atlas original de 1254 × 1254 mantém transparência e é recortado por UV; as regiões de toque, gestos e configurações de opacidade continuam iguais.

O menu funcional adapta a paleta e os motivos do conceito: fundos verde-escuros, seleção jade, molduras douradas, texto marfim e espiral Zonai vetorial no título e no botão touch do menu. Mantém as abas e opções reais do aplicativo; o conceito PNG é uma referência visual, não uma imagem sobreposta à interface.

## Prompt — fundo

Use case: stylized-concept. Asset type: landscape launcher background for a Tears of the Kingdom Android fan project. Create a breathtaking painterly Hyrule landscape inspired by The Legend of Zelda Tears of the Kingdom: vast green valleys far below, distant castle and mountain silhouettes, majestic floating sky islands with ancient pale Zonai ruins, golden-leaved trees, dangling roots and slender waterfalls dissolving into clouds. Wide 16:9 composition, high resolution. Islands concentrated in upper right and far horizon, quiet darker grassy cliff foreground and restrained detail on left third for launcher text overlay. Soft sunrise, turquoise atmosphere, warm gold sunlight, expansive sense of discovery, refined hand-painted adventure game concept art. No characters, no lettering, no logo, no UI, no watermark. Fully opaque background.

## Prompt — touch

Use case: stylized-concept. Asset type: production transparent touch control sprite atlas for Android game, inspired by the Zonai of Zelda Tears of the Kingdom. SQUARE canvas with exactly FOUR sprites in strict equal 2x2 grid. Each centered in its quadrant, 5 percent padding. Front-on orthographic flat 2D game UI textures, dark moss-green carved stone, delicate weathered antique gold edging, restrained luminous jade-green channels and angular Zonai spiral engravings, no blue Sheikah eye. Top-left: circular button rim only, outer diameter 90 percent of cell, thin decorated rim, fully transparent central hole at least 68 percent of cell. Top-right: symmetric rounded rectangular shoulder button rim only, width 90 percent of cell and height 42 percent of cell, fully transparent center. Bottom-left: circular analog base rim only, diameter 90 percent of cell, transparent hole at least 62 percent of cell, segmented jade channels. Bottom-right: circular flat thumb-cap disk, diameter 90 percent of cell, dark green stone face with restrained engraved concentric Zonai spiral, fine jade perimeter and antique gold trim. Crisp restrained ornaments legible at small mobile sizes. No letters, no labels, no UI scene, no grid lines, no extra sprites, no external shadows, no perspective. Background and holes of first three sprites must have actual transparent alpha, not checkerboard pixels.

## Prompt — menu

Use case: ui-mockup. Asset type: polished landscape 16:9 menu art direction reference for Android Tears of the Kingdom fan launcher. Design a beautiful usable Zonai-inspired settings menu: large dark moss-green stone panel with slender antique gold chamfered border, restrained jade luminous angular spiral ornament at top, subtle ancient geometric engraving only at corners. Background is softly blurred Hyrule floating islands in golden sunrise. Editorial elegant ivory typography. Exact Portuguese title 'CONFIGURAÇÕES'. Left vertical navigation exact labels 'Geral', 'Gráficos', 'Áudio', 'Controles', with 'Gráficos' selected by a jade highlight and small gold marker. Right spacious settings panel with exact labels 'Resolução', 'Qualidade', 'Tela cheia', two elegant selection rows showing 'Nativa' and 'Alta', one jade toggle. Bottom left button exact label 'Voltar', bottom right outlined button exact label 'Restaurar padrões'. Strong hierarchy, generous padding, readable high contrast, delicate ancient advanced technology aesthetic of Zonai, polished actual game interface. No Sheikah eye, no blue cyan palette, no device frame, no watermark. This is a single complete menu visual concept, not a sprite atlas.

