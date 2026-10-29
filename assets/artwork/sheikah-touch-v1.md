# Controles touch Sheikah — v1

Atlas: `sheikah-touch-atlas-v1.png` (1254 × 1254, RGBA, transparência real).
Gerado com a ferramenta integrada `image_gen`, usando o esboço aprovado como referência.
O PNG original foi copiado sem edição; as quatro regiões são selecionadas por UV no runtime.

O APK contém o atlas em `assets/res/sheikah-touch-atlas.png`. A skin só muda a apresentação:
layout, áreas de toque, gestos, editor, visibilidade automática e tamanho dos controles mantêm o comportamento.
O botão de menu usa o olho Sheikah, desenhado em ImGui para permanecer nítido.

Os multiplicadores de opacidade existentes permanecem: preenchimento 40, pressionado 120,
borda 110, texto 200 e analógico em repouso 70 (todos na escala 0–255), multiplicados pela
opacidade configurada. O desvanecimento em repouso de 0,6 permanece. O alpha do PNG é
multiplicado pela mesma opacidade das bordas; os centros vazados deixam o jogo aparecer.

## Prompt do atlas

Convert the approved Sheikah touch-controller concept image into a PRODUCTION GAME UI SPRITE ATLAS. Preserve its worn charcoal stone, restrained gold/bronze engraving, fine cyan luminous grooves and ancient Sheikah spiral motifs. Output a SQUARE image with a genuinely transparent alpha background, exactly four equally sized cells arranged in a strict 2 by 2 grid, each sprite precisely centered in its own quadrant. NO text, NO letters, NO game scene, NO title, NO labels, NO grid lines, NO checkerboard drawn into the pixels, NO extra symbols floating in the margins. Top-left cell: circular button BORDER/RIM ONLY, perfectly round front-on, outer diameter 90 percent of the cell width, outer 12 percent is a thin dark stone ring with gold edging and a cyan circular channel, completely TRANSPARENT central hole (at least 68 percent of cell width); no fill in the center. Top-right cell: a rounded rectangular shoulder-button BORDER/RIM ONLY, front-on, 90 percent of cell width and 42 percent of cell height, transparent central hole, dark stone with small engraved cyan channels and gold trim, symmetric so it works for either L or R. Bottom-left cell: a circular analog BASE BORDER/RIM ONLY, front-on, diameter 90 percent of cell width, open TRANSPARENT center at least 62 percent width, charcoal engraved stone frame, segmented cyan ring, small bronze geometric accents contained inside the outer diameter. Bottom-right cell: circular analog thumb-cap DISK, perfectly front-on, diameter 90 percent of cell width, flat charcoal stone face with a subtly engraved spiral, very thin gold edge and thin cyan perimeter; no perspective and no extruded side wall. Consistent matched material, understated crisp ornament that reads at small mobile-screen sizes, no huge glow or opaque external shadows. Every sprite is completely isolated and fits wholly inside its quadrant with equal 5 percent padding. Render as polished hand-painted 2D game textures, not a UI mockup. The background and the holes in the first three frame sprites must be truly transparent. Keep cyan and gold moderate; all opacity modulation will be controlled by the game.
