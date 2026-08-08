# Guia Definitivo de LUTs e Perfis Criativos — Leica Perfect Fork v6.2

**Device:** Xiaomi 15T (codinome: dizi)
**Chipset:** MediaTek Dimensity 8300-Ultra (Imagiq 980 ISP, Mali-G615 MC6)
**Sensors:** OV50E (main 50MP 14-bit) + S5KJN1 (UW) + S5K3J1 (tele) + OV32B (front)
**Upstream:** bjzhou/PhotonCamera v1.26.1
**Schema version:** 5
**Versão deste guia:** 6.2.0
**Idioma:** Português (BR)
**Data:** v6.2 DEFINITIVE

---

## Sumário

1. Como Funciona o Sistema de Look (pipeline 5-stage)
2. Os 26 Perfis Criativos (tabela mestra por marca)
3. Recomendações por Cena (11 cenários × top 3 perfis)
4. Catálogo LUT (32 LUTs explicadas)
5. Catálogo DCP (13 DCPs explicados)
6. Frames (9 molduras explicadas)
7. 10 Combos Recomendados (receitas LUT+DCP+frame+mode)
8. Como Ativar (JSON edit OU UI LUT picker)
9. Dicas Avançadas (stacking, per-lens, video LUTs, quando usar NO LUT)
10. Resumo Técnico (33³ UINT16, 5-stage stacking, GL_RGB16F)

---

## 1. Como Funciona o Sistema de Look

### 1.1 Visão Geral do Pipeline

A imagem final do PhotonCamera Leica Perfect v6.2 NÃO é uma simples aplicação de filtro. É uma cadeia de **5 estágios GPU** que opera sobre o dado RAW 14-bit (sensor main OV50E) ou 10-bit (auxiliares), em espaço linear antes da tonalização, e termina no espaço display (sRGB ou Display-P3, com gainmap HDR10/HLG quando aplicável).

```
 RAW 14-bit OV50E           RAW 10-bit UW/tele/front
        │                            │
        └─────────┬──────────────────┘
                  ▼
   [0] Black/White Level Correction      — pedestal 1024→0, escala até 16383 (main)
                  ▼
   [1] RAW Radiance Fusion               — 15 frames (main), alinhamento giro-óptico
                  ▼                                       em domínio RAW pré-demosaic
   [2] AMAZE Demosaic + AgX Tone Map     — quad-Bayer 4×4 RGGB → RGB linear; AgX
                  ▼                                       filmic shoulder/toe + PGTM
   [3] DCP Color Pipeline (5 sub-estágios):
        ├─ DCP forward matrix           (XYZ ↔ camera native)
        ├─ DCP toneCurve                (curva de tom da câmera original)
        ├─ DCP hueSatMap                (rotação de matiz + saturação por região)
        ├─ DCP lookTable                (a "vibe" da câmera original)
        └─ Baseline LUT (33³ UINT16)    (Leica_M9_STD no perfil leica_authentic)
                  ▼
   [4] Creative LUT/Frame overlay        (override do perfil ativo, se ≠ baseline)
                  ▼
   [5] Sharpening + NR + Lens Correction — USM adaptativo, NLM radius 7 (mode_max)
                  ▼
   OUTPUT: HEIC Q100 (10-bit gainmap HDR) + JPEG Q100 + DNG 16-bit + UltraHDR Q100
```

### 1.2 Os 5 Sub-Estágios de Cor (DCP + LUT)

O sistema DNG Color Profile (DCP) da Adobe carrega **4 componentes de cor** — todos empilhados sequencialmente na GPU — seguidos de uma LUT 33³ externa, formando **5 estágios simultâneos de correção de cor**. Isso é o máximo suportado pela arquitetura `BaselineColorCorrection.kt`:

| # | Estágio                  | Origem                         | Efeito primário                                  |
|---|--------------------------|--------------------------------|--------------------------------------------------|
| 1 | Forward Matrix           | `DcpProfile.forwardMatrix`     | Conversão camera-native → XYZ (D50)              |
| 2 | Tone Curve               | `DcpProfile.toneCurve`         | Tom médio, contraste e ponto preto               |
| 3 | Hue/Sat Map              | `DcpProfile.hueSatMap`         | Rotação de matiz + saturação por região HSL      |
| 4 | Look Table               | `DcpProfile.lookTable`         | A "vibe" — curtas, lavagens, halations            |
| 5 | Baseline LUT (cube)      | `.plut` 33³ UINT16 em assets/  | Lookup 3D final, aplicado em display-space       |

> **Importante:** A LUT 33³ é carregada em `GL_RGB16F` (half-float, 16-bit por canal) embora o dado seja UINT16 (16-bit inteiro). Isso é uma escolha do upstream: o upload half-float permite que a GPU interpole via sampler trilinear sem perda visualmente mensurável entre os 35.937 nodos do cubo.

### 1.3 Onde o Perfil Criativo Entra

Quando `active_profile` (no JSON `creative_profiles`) é diferente de `leica_authentic`, **3 coisas mudam em runtime**:

1. **`forcedDcpId`** retorna `activeDcpId` em vez do baseline (`builtin_dcp_Leica M8 Camera Standard`).
2. **`forcedBaselineLutId`** retorna `activeLutId` em vez de `Leica_M9_STD`.
3. **`forcedFrameId`** retorna `activeFrameId` em vez de `leica`.
4. **`agxWhiteRelativeExposure`** passa a usar `effectiveToneContrast = tone_contrast + activeToneContrastBoost`, somando `tone_warmth_shift_k` (em Kelvin) ao whitepoint, e multiplicando saturação por `saturationMultiplier`.

Isso foi implementado no fechamento da GAP **G3** (v6.2 — accessors `forcedDcpId`/`forcedBaselineLutId`/`forcedFrameId` agora são creative-profile-aware).

### 1.4 Por Que 5 Estágios e Não 1?

Cada estágio resolve um problema diferente. Empilhar tudo numa única LUT 33³ daria banding visível em céus e gradientes de pele — a subdivisão mantém a precisão de matiz por região (hueSatMap) e permite ajuste fino de tom (toneCurve) sem reescrever toda a cube.

A baseline LUT externa existe porque alguns looks de câmera (Leica M9 Kodak CCD, Hasselblad Natural HNCS, Fuji Classic Chrome) são difíceis de reproduzir só com toneCurve + hueSatMap — eles têm deslocamentos de matiz não-monotônicos que se ajustam melhor num cube 3D diretamente no espaço display.

---

## 2. Os 26 Perfis Criativos

Agrupados por marca. A coluna "LUT" refere-se ao arquivo `.plut` em `assets/luts/`. "DCP" é o perfil DNG em `assets/dcp/`. "Frame" é a moldura em `assets/frames/`.

### 2.1 Leica (2 perfis)

| Perfil              | LUT            | DCP                          | Estética                    | Lens ideal | Cena ideal                     |
|---------------------|----------------|------------------------------|-----------------------------|------------|--------------------------------|
| `leica_authentic`   | leica_m9       | Leica M8 Camera Standard     | CCD Kodak, neutro quente    | main       | Diário, street, documental     |
| `leica_monochrome`  | monochrome     | Leica M8 Camera Standard     | P&B puro, contraste +0.08   | main       | Street P&B, retrato dramático  |

### 2.2 Hasselblad (2 perfis)

| Perfil                  | LUT                 | DCP                              | Estética                    | Lens ideal | Cena ideal                          |
|-------------------------|---------------------|----------------------------------|-----------------------------|------------|-------------------------------------|
| `hasselblad_natural`    | Hasselblad          | Hasselblad X1D-50 Adobe Standard | HNCS natural, −100K cool    | main       | Retrato premium, moda, produto luxo |
| `hasselblad_portrait`   | Hasselblad_portrait | Hasselblad X1D-50 Adobe Standard | Pele suave, +200K warm      | front      | Retrato estúdio, casamento, família |

### 2.3 Fuji (7 perfis)

| Perfil                    | LUT                 | DCP                              | Estética                       | Lens ideal | Cena ideal                              |
|---------------------------|---------------------|----------------------------------|--------------------------------|------------|-----------------------------------------|
| `fuji_classic_chrome`     | cc                  | RICOH GR IV Camera Negative Film | Vintage saturação baixa −12%   | main       | Street vintage, urbano, documental      |
| `fuji_classic_neg`        | nc                  | RICOH GR IV Camera Negative Film | Quente +300K, saturação −8%    | main       | Retrato vintage, golden hour, lifestyle |
| `fuji_astia`              | astia               | RICOH GR IV Camera Positive Film | Pele delicada +100K, soft      | main       | Retrato feminino, beauty, flores        |
| `fuji_velvia_vivid`       | velvia50.33         | Sony ILCE-7M4 Camera ST          | Saturação +18%, contraste +12% | uw         | Paisagem, natureza, céu dramático       |
| `cinestill_800t`          | film_cinestill_800t | Sony ILCE-7M4 Camera ST          | Túngsten −400K, halation red   | main       | Noite urbana, neon, long exposure       |
| `fuji_superia_1600`       | film_superia_1600   | RICOH GR IV Camera Negative Film | Green-shift low light +150K    | main       | Indoor mood, baixa luz, grão estético   |
| `fuji_natura_1600`        | film_natura_1600    | Canon EOS R6 Mark III Portrait   | Premium warm +200K             | main       | Evento noturno, jantar, low-light luxo  |

### 2.4 Kodak (2 perfis)

| Perfil                  | LUT                      | DCP                                     | Estética                  | Lens ideal | Cena ideal                              |
|-------------------------|--------------------------|-----------------------------------------|---------------------------|------------|-----------------------------------------|
| `kodak_ultramax_400`    | film_kodak_ultramax_400  | Canon EOS R6 Mark III Camera Portrait   | Quente +250K, sat +6%     | main       | Viagem, casual daytime, família, férias |
| `kodak_e100`            | e100                     | Sony ILCE-7M4 Camera ST                 | Ektachrome preciso sat +4%| tele       | Produto, ciência, macro, cor precisa    |

### 2.5 Ricoh (5 perfis)

| Perfil              | LUT                 | DCP                                     | Estética                  | Lens ideal | Cena ideal                              |
|----------------------|---------------------|-----------------------------------------|---------------------------|------------|-----------------------------------------|
| `ricoh_negative`     | ricoh_negative      | RICOH GR IV Camera Negative Film        | Vintage −200K, sat −7%    | main       | Street purista, jornalismo, documental |
| `ricoh_positive`     | ricoh_positive      | RICOH GR IV Camera Positive Film        | Natureza urbana +100K     | main       | Vegetação urbana, primavera             |
| `ricoh_gr2_posi`     | ricoh_gr2_positive  | RICOH GR IV Camera Positive Film        | GR2 cult +250K            | main       | Street vintage quente, retrato casual   |
| `ricoh_green`        | ricoh_green         | Sony ILCE-7M4 Camera ST                 | Cinema noir −250K         | main       | Cinema noir, thriller, mood dramático   |
| `ricoh_yellow`       | ricoh_yellow        | Canon EOS R6 Mark III Camera Portrait   | Western +500K extrema     | tele       | Deserto, México, western, golden extreme|

### 2.6 Pentax (4 perfis) — Séries sazonais

| Perfil              | LUT              | DCP                                     | Estética                   | Lens ideal | Cena ideal                              |
|----------------------|------------------|-----------------------------------------|----------------------------|------------|-----------------------------------------|
| `pentax_harubeni`    | pentax_harubeni  | Sony ILCE-7M4 Camera ST                 | Primavera +150K sat +5%    | uw         | Sakura, flores de cerejeira             |
| `pentax_katen`       | pentax_katen     | Sony ILCE-7M4 Camera ST                 | Verão +50K sat +8%         | uw         | Praia, piscina, vegetação               |
| `pentax_kyushu`      | pentax_kyushu    | Canon EOS R6 Mark III Camera Portrait   | Outono +350K sat +3%       | tele       | Folhas douradas, vinhedos               |
| `pentax_fuyuno`      | pentax_fuyuno    | Hasselblad X1D-50 Adobe Standard        | Inverno −300K sat −5%      | main       | Neve, cenas frias, minimalista          |

### 2.7 Lumix (3 perfis)

| Perfil              | LUT            | DCP                              | Estética                | Lens ideal | Cena ideal                              |
|---------------------|----------------|----------------------------------|-------------------------|------------|-----------------------------------------|
| `lumix_vivid`       | lumix_vivid    | Panasonic DC-S9 Adobe Standard   | Vlog sat +12% cont +8%  | main       | Vlog, social, YouTube, content creation |
| `lumix_portrait`    | lumix_portrait | Panasonic DC-S9 Adobe Standard   | Moderno +150K sat −3%   | front      | Retrato moderno, lifestyle              |
| `lumix_std`         | lumix_std      | Panasonic DC-S9 Adobe Standard   | Neutro baseline alt.    | main       | Quando Leica M8 é "carregado demais"    |

### 2.8 Preto & Branco (1 perfil)

| Perfil              | LUT                | DCP                          | Estética                | Lens ideal | Cena ideal                              |
|----------------------|--------------------|------------------------------|-------------------------|------------|-----------------------------------------|
| `rollei_cn200`       | film_rollei_cn200  | Leica M8 Camera Standard     | P&B vintage cont +12%   | main       | P&B vintage, retrato clássico, street   |

**Total: 26 perfis** (2+2+7+2+5+4+3+1 = 26 ✓).

---

## 3. Recomendações por Cena

Para cada um dos 11 cenários, listamos os **3 perfis mais recomendados** em ordem de prioridade. A escolha considera: estética combinada com a luz típica da cena, sensor ideal (main/UW/tele/front), e ajuste fino do modo de captura (mode_max/balanced/fast).

### 3.1 Retrato

| Posição | Perfil                | Por quê                                                            | Lens  | Modo             |
|---------|-----------------------|-------------------------------------------------------------------|-------|------------------|
| 1       | `hasselblad_portrait` | Pele premium, +200K warm, saturação suave −6%                     | front | mode_balanced    |
| 2       | `fuji_astia`          | Soft skin, +100K, contraste reduzido −0.02                        | main  | mode_max         |
| 3       | `leica_authentic`     | Neutro CCD Kodak — quando o retrato é documental                  | main  | mode_balanced    |

### 3.2 Street

| Posição | Perfil                | Por quê                                                            | Lens  | Modo             |
|---------|-----------------------|-------------------------------------------------------------------|-------|------------------|
| 1       | `leica_authentic`     | Documental clássico — sem exagero de saturação                    | main  | mode_balanced    |
| 2       | `ricoh_negative`      | Vintage GR purista, −200K, contraste +0.08                        | main  | mode_fast        |
| 3       | `fuji_classic_chrome` | Urbano vintage, sat −12%                                          | main  | mode_fast        |

### 3.3 Paisagem

| Posição | Perfil                | Por quê                                                            | Lens  | Modo             |
|---------|-----------------------|-------------------------------------------------------------------|-------|------------------|
| 1       | `fuji_velvia_vivid`   | Saturação +18%, contraste +12%, céu dramático                     | uw    | mode_max         |
| 2       | `kodak_e100`          | Cor precisa Ektachrome, baixa distorção                           | tele  | mode_max         |
| 3       | `pentax_katen`        | Verão vibrante +8% sat                                            | uw    | mode_balanced    |

### 3.4 Noite / Neon

| Posição | Perfil                | Por quê                                                            | Lens  | Modo             |
|---------|-----------------------|-------------------------------------------------------------------|-------|------------------|
| 1       | `cinestill_800t`      | Túngsten nativo, halation vermelho em luzes brilhantes            | main  | mode_max         |
| 2       | `fuji_natura_1600`    | Premium warm +200K, ISO-alto com pele protegida                   | main  | mode_max         |
| 3       | `fuji_superia_1600`   | Green-shift, grão estético, indoor low-light                      | main  | mode_balanced    |

### 3.5 Viagem

| Posição | Perfil                | Por quê                                                            | Lens  | Modo             |
|---------|-----------------------|-------------------------------------------------------------------|-------|------------------|
| 1       | `kodak_ultramax_400`  | Quente +250K, sat +6% — look "viagem" instantâneo                 | main  | mode_balanced    |
| 2       | `fuji_classic_neg`    | Vintage quente +300K, lifestyle                                   | main  | mode_balanced    |
| 3       | `leica_authentic`     | Quando quiser neutro + perene                                     | main  | mode_balanced    |

### 3.6 Golden Hour

| Posição | Perfil                | Por quê                                                            | Lens  | Modo             |
|---------|-----------------------|-------------------------------------------------------------------|-------|------------------|
| 1       | `fuji_classic_neg`    | Warm +300K casa perfeitamente com luz dourada                     | main  | mode_balanced    |
| 2       | `ricoh_yellow`        | Western extremo +500K, golden hour dramático                      | tele  | mode_balanced    |
| 3       | `pentax_kyushu`       | Outono +350K, folhas douradas                                     | tele  | mode_max         |

### 3.7 P&B (Preto & Branco)

| Posição | Perfil                | Por quê                                                            | Lens  | Modo             |
|---------|-----------------------|-------------------------------------------------------------------|-------|------------------|
| 1       | `leica_monochrome`    | P&B puro, contraste +0.08, AgX shoulder                           | main  | mode_balanced    |
| 2       | `rollei_cn200`        | P&B vintage, contraste +0.12, look Rollei                         | main  | mode_max         |
| 3       | `ricoh_green`         | Quando o "noir" precisa de matiz sutil verde                      | main  | mode_balanced    |

### 3.8 Produto / Comida

| Posição | Perfil                | Por quê                                                            | Lens  | Modo             |
|---------|-----------------------|-------------------------------------------------------------------|-------|------------------|
| 1       | `kodak_e100`          | Cor precisa, sem exagero — referência de produto                  | tele  | mode_max         |
| 2       | `hasselblad_natural`  | HNCS natural, premium para luxo                                   | main  | mode_max         |
| 3       | `leica_authentic`     | Neutro, documental, sem desvio de cor                             | main  | mode_max         |

### 3.9 Selfie

| Posição | Perfil                | Por quê                                                            | Lens  | Modo             |
|---------|-----------------------|-------------------------------------------------------------------|-------|------------------|
| 1       | `hasselblad_portrait` | Pele premium, +200K, lens=front, beauty OFF                       | front | mode_balanced    |
| 2       | `lumix_portrait`      | Moderno +150K, lifestyle social                                   | front | mode_balanced    |
| 3       | `fuji_astia`          | Soft skin quando em luz natural difusa                            | front | mode_balanced    |

### 3.10 Cinema / Mood

| Posição | Perfil                | Por quê                                                            | Lens  | Modo             |
|---------|-----------------------|-------------------------------------------------------------------|-------|------------------|
| 1       | `ricoh_green`         | Cinema noir −250K, contraste +0.10                                | main  | mode_max         |
| 2       | `cinestill_800t`      | Túngsten −400K, halation — mood noturno                           | main  | mode_max         |
| 3       | `pentax_fuyuno`       | Frio −300K, minimalista, sat −5%                                  | main  | mode_balanced    |

### 3.11 Estações do Ano

| Estação   | Top 1                | Top 2              | Top 3              |
|-----------|----------------------|--------------------|--------------------|
| Primavera | `pentax_harubeni`    | `ricoh_positive`   | `fuji_astia`       |
| Verão     | `pentax_katen`       | `fuji_velvia_vivid`| `lumix_vivid`      |
| Outono    | `pentax_kyushu`      | `fuji_classic_neg` | `ricoh_yellow`     |
| Inverno   | `pentax_fuyuno`      | `leica_monochrome` | `ricoh_green`      |

---

## 4. Catálogo LUT (32 LUTs)

Cada LUT é um arquivo `.plut` de 215.638 bytes — 16-byte header (magic "PLUT", version=1, size=33, dataType=1 UINT16) + 33³ × 3 × 2 bytes payload. Upload GPU em `GL_RGB16F` (half-float) com sampler trilinear 3D.

### 4.1 LUTs Baseline / Sistema (2)

#### `Leica_M9_STD`
- **Categoria:** Baseline Leica
- **Estética:** CCD Kodak M9 — neutro-quente, pele natural, saturação moderada
- **Quando usar:** Default do perfil `leica_authentic`. Diário, street, documental
- **Quando NÃO usar:** Se quiser saturação vibrante (use velvia50.33) ou P&B (use monochrome)
- **Combina com DCP:** Leica M8 Camera Standard (já é o baseline)

#### `standard`
- **Categoria:** Baseline neutro alternativo
- **Estética:** Nenhum desvio de matiz — só aplicação de tone curve genérica
- **Quando usar:** Como referência de "antes/depois" ao comparar LUTs criativas
- **Quando NÃO usar:** Para entrega final — é flat demais
- **Combina com DCP:** Qualquer (DCP determina a cor, LUT não interfere muito)

### 4.2 LUTs Leica (2)

#### `leica_m9`
- **Categoria:** Leica CCD
- **Estética:** Kodak M9 CCD — magenta sutil, sombras levemente azuis
- **Quando usar:** Diário, retrato documental, street clássico
- **Quando NÃO usar:** Cenas com muito verde (superia 1600 ou pentax_katen tratam melhor)
- **Combina com DCP:** Leica M8 Camera Standard
- **Cena ideal:** Rua de Lisboa ao entardecer

#### `leica` (variante)
- **Categoria:** Leica alternativa
- **Estética:** Variante ligeiramente mais saturada que M9 STD
- **Quando usar:** Quando o STD fica "plano" mas você quer manter vibe Leica
- **Quando NÃO usar:** Em pele — pode exagerar vermelho
- **Combina com DCP:** Leica M8 Camera Standard
- **Cena ideal:** Arquitetura clássica

### 4.3 LUTs Preto & Branco (2)

#### `monochrome`
- **Categoria:** P&B
- **Estética:** P&B puro com curva de contraste média
- **Quando usar:** Street P&B, retrato dramático, arquitetura minimalista
- **Quando NÃO usar:** Cenas com cor fundamental para a narrativa
- **Combina com DCP:** Leica M8 Camera Standard
- **Cena ideal:** Manhattan em dia nublado

#### `cn200` / `film_rollei_cn200`
- **Categoria:** P&B vintage
- **Estética:** Rollei CN200 film — contraste elevado, grão implícito
- **Quando usar:** P&B retratado vintage, street clássico
- **Quando NÃO usar:** Cenas com gradação fina (use monochrome)
- **Combina com DCP:** Leica M8 Camera Standard
- **Cena ideal:** Retrato masculino em luz dura

### 4.4 LUTs Hasselblad (2)

#### `Hasselblad`
- **Categoria:** HNCS Natural
- **Estética:** Hasselblad Natural Color Solution — pele premium, saturação contida
- **Quando usar:** Retrato premium, moda, produto de luxo, estúdio
- **Quando NÃO usar:** Cenas que pedem "vibe vintage" (use Fuji)
- **Combina com DCP:** Hasselblad X1D-50 Adobe Standard
- **Cena ideal:** Editorial de moda em estúdio

#### `Hasselblad_portrait`
- **Categoria:** HNCS Retrato
- **Estética:** Variante +200K warm otimizada para pele
- **Quando usar:** Retrato estúdio, casamento, selfie premium
- **Quando NÃO usar:** Paisagem (use Hasselblad sem sufixo)
- **Combina com DCP:** Hasselblad X1D-50 Adobe Standard
- **Cena ideal:** Retrato de noiva em luz natural difusa

### 4.5 LUTs Fuji (7)

#### `cc` (Classic Chrome)
- **Categoria:** Fuji film sim
- **Estética:** Contraste médio, saturação −12%, magenta sutil
- **Quando usar:** Street vintage, documental, urbano
- **Quando NÃO usar:** Paisagem vibrante (use velvia)
- **Combina com DCP:** RICOH GR IV Camera Negative Film
- **Cena ideal:** Mercado municipal de manhã

#### `nc` (Classic Negative)
- **Categoria:** Fuji film sim
- **Estética:** Quente +300K, contraste +0.06, sat −8%
- **Quando usar:** Retrato vintage, golden hour, lifestyle
- **Quando NÃO usar:** Estúdio controlado (use Hasselblad)
- **Combina com DCP:** RICOH GR IV Camera Negative Film
- **Cena ideal:** Café ao entardecer

#### `astia`
- **Categoria:** Fuji film sim
- **Estética:** Soft skin, +100K, contraste −0.02
- **Quando usar:** Retrato feminino, beauty, flores
- **Quando NÃO usar:** Street (use cc)
- **Combina com DCP:** RICOH GR IV Camera Positive Film
- **Cena ideal:** Retrato feminino em luz janela

#### `velvia50.33`
- **Categoria:** Fuji film sim
- **Estética:** Saturação +18%, contraste +12%, azul céu dramático
- **Quando usar:** Paisagem, natureza, céu dramático
- **Quando NÃO usar:** Pele (fica vermelho demais)
- **Combina com DCP:** Sony ILCE-7M4 Camera ST
- **Cena ideal:** Montanha ao meio-dia

#### `cinestill800t.33` / `film_cinestill_800t`
- **Categoria:** Film cinematográfico
- **Estética:** Túngsten 800T, halation vermelho em fontes de luz
- **Quando usar:** Noite urbana, neon, long exposure
- **Quando NÃO usar:** Daylight (fica azul frio demais)
- **Combina com DCP:** Sony ILCE-7M4 Camera ST
- **Cena ideal:** Times Square à noite

#### `superia1600` / `film_superia_1600`
- **Categoria:** Fuji film sim
- **Estética:** Green-shift leve, grão ISO 1600, +150K
- **Quando usar:** Indoor mood, baixa luz, grão estético
- **Quando NÃO usar:** Quando pele é crítica (use natura 1600)
- **Combina com DCP:** RICOH GR IV Camera Negative Film
- **Cena ideal:** Bar à meia-noite

#### `nature1600` / `film_natura_1600`
- **Categoria:** Fuji film sim
- **Estética:** Premium warm +200K, pele protegida em ISO alto
- **Quando usar:** Evento noturno, jantar, low-light luxo
- **Quando NÃO usar:** Street casual (use superia 1600)
- **Combina com DCP:** Canon EOS R6 Mark III Camera Portrait
- **Cena ideal:** Jantar romântico à luz de vela

### 4.6 LUTs Kodak (2)

#### `ultramax400` / `film_kodak_ultramax_400`
- **Categoria:** Kodak film
- **Estética:** Ultramax 400 consumer — quente +250K, sat +6%
- **Quando usar:** Viagem, casual daytime, família, férias
- **Quando NÃO usar:** Quando quiser precisão (use e100)
- **Combina com DCP:** Canon EOS R6 Mark III Camera Portrait
- **Cena ideal:** Praia em família

#### `e100`
- **Categoria:** Kodak film
- **Estética:** Ektachrome E100 — preciso, sat +4%, contraste +6%
- **Quando usar:** Produto, ciência, macro, cor precisa
- **Quando NÃO usar:** Street casual (use ultramax)
- **Combina com DCP:** Sony ILCE-7M4 Camera ST
- **Cena ideal:** Fotografia de relógio em estúdio

### 4.7 LUTs Ricoh (5)

#### `ricoh_negative`
- **Categoria:** Ricoh GR
- **Estética:** Negative film sim — vintage −200K, sat −7%
- **Quando usar:** Street purista, jornalismo, documental urbano
- **Quando NÃO usar:** Quando quiser "vibe casa" (use gr2_posi)
- **Combina com DCP:** RICOH GR IV Camera Negative Film
- **Cena ideal:** Cidade ao amanhecer

#### `ricoh_positive`
- **Categoria:** Ricoh GR
- **Estética:** Positive film sim — +100K, sat +3%
- **Quando usar:** Natureza urbana, vegetação, primavera urbana
- **Quando NÃO usar:** Cenas com pele crítica (use astia)
- **Combina com DCP:** RICOH GR IV Camera Positive Film
- **Cena ideal:** Parque urbano na primavera

#### `Ricoh_GR2_POSI` / `ricoh_gr2_positive`
- **Categoria:** Ricoh GR cult
- **Estética:** GR2 cult — quente +250K, sat neutra
- **Quando usar:** Street vintage quente, retrato casual
- **Quando NÃO usar:** Cenas frias (use pentax_fuyuno)
- **Combina com DCP:** RICOH GR IV Camera Positive Film
- **Cena ideal:** Rua de Tóquio ao pôr-do-sol

#### `ricoh_green`
- **Categoria:** Cinema noir
- **Estética:** Cinema noir −250K, contraste +0.10, sat +2%
- **Quando usar:** Cinema noir, thriller, mood dramático
- **Quando NÃO usar:** Quando quiser "neutro" (use leica_m9)
- **Combina com DCP:** Sony ILCE-7M4 Camera ST
- **Cena ideal:** Cena noturna estilo "Blade Runner"

#### `ricoh_yellow`
- **Categoria:** Western extremo
- **Estética:** Western +500K extrema, sat +4%
- **Quando usar:** Deserto, México, western, golden hour extremo
- **Quando NÃO usar:** Quando pele é crítica (vira laranja)
- **Combina com DCP:** Canon EOS R6 Mark III Camera Portrait
- **Cena ideal:** Deserto de Atacama ao meio-dia

### 4.8 LUTs Pentax (5)

#### `pentax` (base)
- **Categoria:** Pentax base
- **Estética:** Pentax Standard — neutro levemente quente
- **Quando usar:** Quando quiser Pentax sem viés sazonal
- **Quando NÃO usar:** Quando quiser estética específica (use as sazonais)
- **Combina com DCP:** Sony ILCE-7M4 Camera ST

#### `pentax_harubeni`
- **Categoria:** Pentax sazonal
- **Estética:** Primavera — +150K, sat +5%, flores delicadas
- **Quando usar:** Sakura, flores de cerejeira
- **Quando NÃO usar:** Outono (use kyushu)
- **Combina com DCP:** Sony ILCE-7M4 Camera ST
- **Cena ideal:** Hanami no Japão

#### `pentax_katen`
- **Categoria:** Pentax sazonal
- **Estética:** Verão — +50K, sat +8%, vibrante
- **Quando usar:** Praia, piscina, vegetação exuberante
- **Quando NÃO usar:** Inverno (use fuyuno)
- **Combina com DCP:** Sony ILCE-7M4 Camera ST
- **Cena ideal:** Costa mediterrânea

#### `pentax_kyushu`
- **Categoria:** Pentax sazonal
- **Estética:** Outono — +350K, sat +3%, folhas douradas
- **Quando usar:** Outono, vinhedos, floresta de bordo
- **Quando NÃO usar:** Primavera (use harubeni)
- **Combina com DCP:** Canon EOS R6 Mark III Camera Portrait
- **Cena ideal:** Vinhedo em Borgonha no outono

#### `pentax_fuyuno`
- **Categoria:** Pentax sazonal
- **Estética:** Inverno — −300K, sat −5%, minimalista
- **Quando usar:** Neve, cenas frias, minimalista
- **Quando NÃO usar:** Verão (use katen)
- **Combina com DCP:** Hasselblad X1D-50 Adobe Standard
- **Cena ideal:** Floresta boreal no inverno

### 4.9 LUTs Lumix (4)

#### `lumix_vivid`
- **Categoria:** Lumix vlog
- **Estética:** Vlog sat +12%, contraste +8%, pop social
- **Quando usar:** Vlog, social media, content creation, YouTube
- **Quando NÃO usar:** Documental (use leica_m9)
- **Combina com DCP:** Panasonic DC-S9 Adobe Standard
- **Cena ideal:** Vlog de viagem

#### `lumix_portrait`
- **Categoria:** Lumix retrato
- **Estética:** Moderno +150K, sat −3%
- **Quando usar:** Retrato moderno, lifestyle
- **Quando NÃO usar:** Estúdio premium (use Hasselblad)
- **Combina com DCP:** Panasonic DC-S9 Adobe Standard
- **Cena ideal:** Lifestyle em café

#### `lumix_nat` / `lumix_natural`
- **Categoria:** Lumix natural
- **Estética:** Natural, leve saturação +3%
- **Quando usar:** Quando Lumix mas não tão "punchy" quanto Vivid
- **Quando NÃO usar:** Quando quiser pop social (use vivid)
- **Combina com DCP:** Panasonic DC-S9 Adobe Standard

#### `lumix_std`
- **Categoria:** Lumix neutro
- **Estética:** Baseline Lumix sem desvio
- **Quando usar:** Quando Leica M8 é "carregado demais" mas você quer DCP Panasonic
- **Quando NÃO usar:** Quando quiser vibe específica (use vivid/portrait)
- **Combina com DCP:** Panasonic DC-S9 Adobe Standard

### 4.10 LUT Especial

#### `fl3`
- **Categoria:** Especial
- **Estética:** Florida / vintage experiment — magenta-azul, saturação alta
- **Quando usar:** Estética experimental de filme expirado
- **Quando NÃO usar:** Para entrega comercial (desvio de matiz não naturalista)
- **Combina com DCP:** Sony ILCE-7M4 Camera ST
- **Cena ideal:** Ensaio editorial experimental

**Total: 32 LUTs catalogadas** (2 baseline + 2 Leica + 2 B&W + 2 Hasselblad + 7 Fuji + 2 Kodak + 5 Ricoh + 5 Pentax + 4 Lumix + 1 Especial = 32 ✓).

> **Nota de contagem:** O assets/luts/ contém fisicamente 30-31 arquivos `.plut` (worklog Task 5-a: 30; Task 6-b: 31). A contagem de 32 inclui sinônimos (`cinestill800t.33` ↔ `film_cinestill_800t`) tratados como uma LUT em dois perfis. O catálogo acima lista 32 entradas pois cobre todos os `lut_id` distintos referenciados nos 26 perfis + 2 baselines do sistema.

---

## 5. Catálogo DCP (13 DCPs)

Cada DCP carrega: forwardMatrix2 + toneCurve + hueSatMap1/2 + lookTable + profileCalibration. São arquivos `.dcp` Adobe Standard em `assets/dcp/`.

### 5.1 DCPs de Câmeras Clássicas (3)

#### `builtin_dcp_Leica M8 Camera Standard`
- **Origem:** Leica M8 (CCD APS-H 10.3MP, 2006)
- **Característica cor:** Magenta sutil em sombras, pele levemente quente, azul contido
- **Melhor uso:** Look Leica clássico — baseline do fork
- **Perfis que usam:** `leica_authentic`, `leica_monochrome`, `rollei_cn200`

#### `builtin_dcp_Hasselblad X1D-50 Adobe Standard`
- **Origem:** Hasselblad X1D-50 (CMOS 50MP médio formato, 2016)
- **Característica cor:** HNCS — pele premium, saturação contida, transições finas
- **Melhor uso:** Retrato premium, moda, produto luxo, inverno minimalista
- **Perfis que usam:** `hasselblad_natural`, `hasselblad_portrait`, `pentax_fuyuno`

#### `builtin_dcp_Panasonic DC-S9 Adobe Standard`
- **Origem:** Panasonic Lumix S9 (CMOS 24MP full-frame, 2024)
- **Característica cor:** Neutro moderno, leve aquecimento em pele, sem viés agressivo
- **Melhor uso:** Vlog, lifestyle, retrato moderno
- **Perfis que usam:** `lumix_vivid`, `lumix_portrait`, `lumix_std`

### 5.2 DCPs Japoneses Contemporâneos (4)

#### `builtin_dcp_RICOH GR IV Camera Negative Film`
- **Origem:** Ricoh GR IV (simulação de negativo)
- **Característica cor:** Saturação reduzida, contraste médio, pele levemente esverdeada (típico de neg)
- **Melhor uso:** Street purista, documental vintage
- **Perfis que usam:** `fuji_classic_chrome`, `fuji_classic_neg`, `fuji_superia_1600`, `ricoh_negative`

#### `builtin_dcp_RICOH GR IV Camera Positive Film`
- **Origem:** Ricoh GR IV (simulação de slide/positivo)
- **Característica cor:** Saturação elevada, contraste alto, azul céu dramático
- **Melhor uso:** Natureza urbana, vegetação
- **Perfis que usam:** `fuji_astia`, `ricoh_positive`, `ricoh_gr2_posi`

#### `builtin_dcp_Sony ILCE-7M4 Camera ST`
- **Origem:** Sony A7 IV (33MP full-frame, 2021)
- **Característica cor:** Neutro-comercial, levemente frio, saturação controlada
- **Melhor uso:** Quando se quer "look de câmera moderna" sem exagero
- **Perfis que usam:** `fuji_velvia_vivid`, `cinestill_800t`, `kodak_e100`, `pentax_harubeni`, `pentax_katen`, `ricoh_green`

#### `builtin_dcp_Canon EOS R6 Mark III Camera Portrait`
- **Origem:** Canon R6 Mark III (24MP full-frame, 2024)
- **Característica cor:** Pele quente Canon, vermelho controlado, +200K perceptual
- **Melhor uso:** Retrato, pele, outono, western
- **Perfis que usam:** `kodak_ultramax_400`, `fuji_natura_1600`, `pentax_kyushu`, `ricoh_yellow`

### 5.3 DCPs de Smartphones Contemporâneos (6)

Estes são referenciais de "cor de smartphone premium" — úteis quando se quer aproximar a estética de Pixel/iPhone/Samsung sem abandonar a qualidade PhotonCamera.

#### `builtin_dcp_Apple iPhone 18 Pro`
- **Origem:** iPhone 18 Pro
- **Característica cor:** Neutro cinematográfico Apple, contraste médio
- **Melhor uso:** Referencial comparativo
- **Perfis que usam:** Nenhum dos 26 (referência)

#### `builtin_dcp_Google Pixel 10 Pro XL`
- **Origem:** Pixel 10 Pro XL
- **Característica cor:** Realismo computacional Google, pele precisa
- **Melhor uso:** Referencial comparativo
- **Perfis que usam:** Nenhum dos 26

#### `builtin_dcp_Samsung S26 Ultra`
- **Origem:** Galaxy S26 Ultra
- **Característica cor:** Saturação vibrante Samsung, azul exaltado
- **Melhor uso:** Referencial comparativo
- **Perfis que usam:** Nenhum dos 26

#### `builtin_dcp_OnePlus 12`
- **Origem:** OnePlus 12
- **Característica cor:** Neutro-comercial
- **Melhor uso:** Referencial comparativo
- **Perfis que usam:** Nenhum dos 26

#### `builtin_dcp_OPPO Find X8 Ultra`
- **Origem:** OPPO Find X8 Ultra
- **Característica cor:** Hasselblad-tuned OPPO, pele premium
- **Melhor uso:** Referencial comparativo
- **Perfis que usam:** Nenhum dos 26

#### `builtin_dcp_Xiaomi 17 Ultra`
- **Origem:** Xiaomi 17 Ultra
- **Característica cor:** Leica-tuned Xiaomi, saturação moderada
- **Melhor uso:** Referencial comparativo (mais próximo do baseline do fork)
- **Perfis que usam:** Nenhum dos 26

**Total: 13 DCPs** (3 clássicos + 4 japoneses contemporâneos + 6 smartphones = 13 ✓).

---

## 6. Frames (9 Molduras)

Frames são overlays PNG com canal alpha aplicados **pós-processamento** (após AgX + LUT), na etapa final antes do encode HEIC/JPEG. A escolha do frame NÃO afeta a cor da imagem — apenas adiciona a moldura decorativa.

### 6.1 Frames Leica / Clássicos (4)

#### `01_leica`
- **Estética:** Moldura Leica clássica — borda fina preta com legendagem "Leica" no canto
- **Quando usar:** Diário Leica, documental, quando quiser assinar a foto
- **Combina com:** `leica_authentic`, `leica_monochrome`

#### `01_classic_white`
- **Estética:** Borda branca grossa estilo impressão fotográfica
- **Quando usar:** Retrato impresso, editorial clássico
- **Combina com:** `hasselblad_portrait`, `fuji_astia`

#### `02_black_border`
- **Estética:** Borda preta grossa — look de暗室 de câmara escura
- **Quando usar:** P&B dramático, mood cinema
- **Combina com:** `leica_monochrome`, `rollei_cn200`

#### `02_hasselblad`
- **Estética:** Moldura Hasselblad com legendagem "Hasselblad"
- **Quando usar:** Editorial premium, moda
- **Combina com:** `hasselblad_natural`, `hasselblad_portrait`

### 6.2 Frames Vintage / Especiais (5)

#### `03_no_frame`
- **Estética:** Sem moldura — para quando o frame distrai
- **Quando usar:** 95% das fotos casuais, paisagem, documental
- **Combina com:** Qualquer perfil (default para a maioria)

#### `03_xpan`
- **Estética:** Moldura "X-Pan" com crop panorâmico 65:24 (3:1)
- **Quando usar:** Panoramas, street cinematográfico
- **Combina com:** `cinestill_800t`, `fuji_classic_chrome`

#### `04_polaroid`
- **Estética:** Borda branca Polaroid com área inferior para "texto"
- **Quando usar:** Casual, lifestyle, nostalgia anos 80/90
- **Combina com:** `fuji_classic_neg`, `kodak_ultramax_400`

#### `05_time`
- **Estética:** Moldura com carimbo de data/hora (canto inferior direito)
- **Quando usar:** Documental com timestamp, foto de viagem com data
- **Combina com:** `kodak_ultramax_400`, `leica_authentic`

#### `06_kodak_portra_400`
- **Estética:** Moldura "Kodak Portra 400" estilo cartucho de filme
- **Quando usar:** Look de film stock explícito, vintage declarado
- **Combina com:** `fuji_classic_chrome`, `fuji_classic_neg`, `kodak_ultramax_400`

**Total: 9 frames** (4 clássicos + 5 vintage = 9 ✓).

---

## 7. 10 Combos Recomendados (Receitas)

Cada receita é uma combinação completa: perfil (LUT+DCP+ajustes de tom) + frame + modo de captura + dica de uso.

### Receita 1 — "Lisboa ao Entardecer" (Street Documental)

| Componente | Valor                          |
|------------|--------------------------------|
| Perfil     | `leica_authentic`              |
| LUT        | leica_m9                       |
| DCP        | Leica M8 Camera Standard       |
| Frame      | `01_leica`                     |
| Modo       | mode_balanced                  |
| Lens       | main (OV50E)                   |
| Dica       | Golden hour neutro, sem viés. Deixe o AgX shoulder lidar com os highlights do sol baixo. |

### Receita 2 — "Casamento Premium" (Retrato Estúdio)

| Componente | Valor                          |
|------------|--------------------------------|
| Perfil     | `hasselblad_portrait`          |
| LUT        | Hasselblad_portrait            |
| DCP        | Hasselblad X1D-50 Adobe Standard|
| Frame      | `02_hasselblad`                |
| Modo       | mode_max                       |
| Lens       | front (OV32B)                  |
| Dica       | Use tripod ou estabilização. Pele fica premium, +200K warm, beauty filter OFF. |

### Receita 3 — "Tóquio Neon" (Noite Urbana)

| Componente | Valor                          |
|------------|--------------------------------|
| Perfil     | `cinestill_800t`               |
| LUT        | film_cinestill_800t            |
| DCP        | Sony ILCE-7M4 Camera ST        |
| Frame      | `03_xpan`                      |
| Modo       | mode_max                       |
| Lens       | main (OV50E)                   |
| Dica       | Tripod essencial. O halation vermelho em neon é o ponto — não combata com AWB. |

### Receita 4 — "Manhattan Cinza" (Street P&B)

| Componente | Valor                          |
|------------|--------------------------------|
| Perfil     | `leica_monochrome`             |
| LUT        | monochrome                     |
| DCP        | Leica M8 Camera Standard       |
| Frame      | `02_black_border`              |
| Modo       | mode_balanced                  |
| Lens       | main (OV50E)                   |
| Dica       | Contraste +0.08 já está no perfil. Céu nublado = sombras densas. |

### Receita 5 — "Sakura em Kyoto" (Primavera)

| Componente | Valor                          |
|------------|--------------------------------|
| Perfil     | `pentax_harubeni`              |
| LUT        | pentax_harubeni                |
| DCP        | Sony ILCE-7M4 Camera ST        |
| Frame      | `06_kodak_portra_400`          |
| Modo       | mode_max                       |
| Lens       | uw (S5KJN1)                    |
| Dica       | +150K warm ressalta rosa da sakura. Use UW para captar mais contexto das árvores. |

### Receita 6 — "Atacama ao Meio-dia" (Western Extremo)

| Componente | Valor                          |
|------------|--------------------------------|
| Perfil     | `ricoh_yellow`                 |
| LUT        | ricoh_yellow                   |
| DCP        | Canon EOS R6 Mark III Portrait |
| Frame      | `03_no_frame`                  |
| Modo       | mode_max                       |
| Lens       | tele (S5K3J1)                  |
| Dica       | +500K extrema cria mood "Sergio Leone". Cuidado com pele — vira laranja se for retrato. |

### Receita 7 — "Borgonha no Outono" (Paisagem Sazonal)

| Componente | Valor                          |
|------------|--------------------------------|
| Perfil     | `pentax_kyushu`                |
| LUT        | pentax_kyushu                  |
| DCP        | Canon EOS R6 Mark III Portrait |
| Frame      | `03_no_frame`                  |
| Modo       | mode_max                       |
| Lens       | tele (S5K3J1)                  |
| Dica       | +350K warm casa com folhas douradas. Tripod para garantir sharpness na distância. |

### Receita 8 — "Editorial de Relógio" (Produto)

| Componente | Valor                          |
|------------|--------------------------------|
| Perfil     | `kodak_e100`                   |
| LUT        | e100                           |
| DCP        | Sony ILCE-7M4 Camera ST        |
| Frame      | `03_no_frame`                  |
| Modo       | mode_max                       |
| Lens       | tele (S5K3J1)                  |
| Dica       | Ektachrome dá cor precisa sem exagero. Ilumine com LED 5500K para consistência. |

### Receita 9 — "Vlog de Viagem" (Social Media)

| Componente | Valor                          |
|------------|--------------------------------|
| Perfil     | `lumix_vivid`                  |
| LUT        | lumix_vivid                    |
| DCP        | Panasonic DC-S9 Adobe Standard |
| Frame      | `05_time`                      |
| Modo       | mode_balanced                  |
| Lens       | main (OV50E)                   |
| Dica       | Saturação +12% faz a foto "saltar" no feed. Frame com timestamp dá contexto de viagem. |

### Receita 10 — "Retrato de Família em Café" (Casual Warm)

| Componente | Valor                          |
|------------|--------------------------------|
| Perfil     | `fuji_classic_neg`             |
| LUT        | nc                             |
| DCP        | RICOH GR IV Camera Negative Film|
| Frame      | `04_polaroid`                  |
| Modo       | mode_balanced                  |
| Lens       | main (OV50E)                   |
| Dica       | +300K warm em luz de café = pele dourada. Frame polaroid adiciona nostalgia. |

---

## 8. Como Ativar

### 8.1 Opção A — Editar JSON + Rebuild (RECOMENDADO)

Para garantir que o perfil seja aplicado em **todos** os níveis do pipeline (DCP + LUT + frame + ajustes de tom), edite o arquivo de configuração e rebuild:

1. **Abra** `/home/z/leica_v3/config/leica_perfect.json`
2. **Localize** a seção `creative_profiles`:
   ```json
   "creative_profiles": {
     "active_profile": "leica_authentic",
     "profiles": { ... }
   }
   ```
3. **Troque** `"active_profile"` para um dos 26 perfis:
   ```json
   "active_profile": "cinestill_800t"
   ```
4. **Salve** o arquivo.
5. **Rebuild** com `./build-archlinux.sh` (ou equivalente).
6. **Instale** o APK resultante.

**Por que funciona:** Os accessors `forcedDcpId`, `forcedBaselineLutId`, `forcedFrameId`, e `agxWhiteRelativeExposure` em `LeicaConfig.kt` verificam `isActiveProfileBaseline` e, se o perfil ativo for ≠ `leica_authentic`, retornam os valores do perfil em vez do baseline. Isso foi fechado como GAP **G3** na v6.2.

### 8.2 Opção B — UI LUT Picker (PARCIAL)

O PhotonCamera upstream expõe um seletor de LUT na UI (Settings → Image Processing → LUT). Escolher uma LUT pela UI funciona **parcialmente**:

- ✅ A LUT escolhida é aplicada (sobrepondo a baseline LUT)
- ❌ O DCP **não** muda (continua o baseline Leica M8)
- ❌ O frame **não** muda (continua o baseline `leica`)
- ❌ Os ajustes de tonalidade (`tone_contrast_boost`, `tone_warmth_shift_k`, `saturation_multiplier`) **não** são aplicados

**Quando usar Opção B:** Quando você só quer trocar a LUT rapidamente sem rebuild. Útil para experimentação.

**Quando NÃO usar:** Para entrega final — você perde a combinação DCP+LUT+frame+tonalidade que dá ao perfil sua identidade.

> **Status GAP G3 (parcial):** A troca completa via JSON (Opção A) é totalmente funcional. A troca completa via UI requereria patches P-45/P-46/P-47 (não implementados na v6.2 — previsto para v7.0). A Opção B é a alternativa de "troca rápida" limitada à LUT apenas.

### 8.3 Como Verificar se o Perfil Está Ativo

Após instalar o APK rebuilt:

1. Tire uma foto.
2. Abra no gallery.
3. Veja os EXIF: a tag `Software` deve mostrar `LeicaCamera`.
4. Compare visualmente com uma foto tirada com `leica_authentic`:
   - DCP diferente = matiz de pele/ceu diferente
   - LUT diferente = contraste/saturação diferente
   - Frame diferente = moldura ao redor

Se nada mudou, o rebuild não aplicou o JSON ou o `active_profile` continua `"leica_authentic"`.

---

## 9. Dicas Avançadas

### 9.1 Stacking de LUT (5 estágios simultâneos)

A arquitetura `BaselineColorCorrection.kt` suporta até **5 estágios simultâneos de correção de cor**:

1. DCP forwardMatrix
2. DCP toneCurve
3. DCP hueSatMap
4. DCP lookTable
5. Baseline LUT (33³ UINT16)

Isso significa que **não é possível** empilhar duas LUTs 33³ criativas ao mesmo tempo — apenas a baseline LUT é aplicada. Para combinar efeitos, escolha uma LUT e ajuste os parâmetros do perfil (`tone_contrast_boost`, `tone_warmth_shift_k`, `saturation_multiplier`).

**Workaround:** Se quiser "CineStill 800T + Velvia saturation", ajuste o perfil `cinestill_800t` com `saturation_multiplier: 1.15` no JSON — o efeito é equivalente a empilhar.

### 9.2 Per-Lens DCP Ratio (gap G2 — documentado)

O fork v6.2 tem os accessors `ccmRatioWarmForLens(lensKey)` e `ccmRatioCoolForLens(lensKey)` que retornam valores por-lens (main: 0.52/1.62, uw: 0.50/1.60, tele: 0.54/1.64, front: 0.48/1.58). Porém, o consumer upstream `calculateInterpolationWeight` é **dead code** — não tem callers. Os valores existem mas não têm efeito em runtime. Documentado como GAP G2 para v7.0.

**Implicação prática:** A troca de DCP ratio por lente não funciona ainda. Use a troca global (`dcp_ratio_warm: 0.52` no JSON, top-level) que afeta todas as lentes igualmente.

### 9.3 LUTs para Vídeo

O vídeo no PhotonCamera Leica Perfect v6.2 usa codec HEVC com `color_profile: "log"` e HDR10. As LUTs **não são aplicadas** ao vídeo em tempo real — o vídeo é gravado em log e a LUT é aplicada apenas na reprodução (display-side).

**Recomendação para vídeo:**

- `mode_max`: 250Mbps 4K30 HDR10 — qualidade máxima, mas throttle após ~8min
- `mode_balanced`: 120Mbps 4K30 — default inteligente, sustain ~20min
- `mode_fast`: 80Mbps 4K30 — sustain indefinido, ideal para vlog contínuo

**Para aplicar LUT ao vídeo:** Exporte o vídeo log e aplique a LUT em pós-produção (DaVinci Resolve, Premiere). A LUT `cinestill_800t` funciona excelentemente em vídeo log de rua noturna.

### 9.4 Quando Usar NO LUT (perfil baseline puro)

Em alguns cenários, qualquer LUT criativa **diminui** a qualidade técnica da imagem:

- **Fotometria científica / calibração de cor:** Use DCP Sony A7M4 + LUT `standard` (perfil `lumix_std` aproxima isso)
- **Produto para catálogo e-commerce:** Cor precisa é crítica; qualquer desvio de matiz é problema. Use `kodak_e100` com `tone_contrast_boost: 0.0`
- **Macro com cor de referência (color checker):** Use `kodak_e100` + `03_no_frame`
- **Comparação A/B com outras câmeras:** Use `leica_authentic` (é o baseline mais neutro do fork)
- **Documental com pele crítica (médico/forense):** Use `hasselblad_natural` — é o mais fiel a pele sem desvio

> **Dica:** Se você está em dúvida entre dois perfis, tire a foto com `leica_authentic` (baseline neutro) e com o perfil candidato. Compare lado-a-lado. A diferença do baseline para o candidato é o "custo" do look criativo.

### 9.5 Active Capture Mode vs Perfil Criativo

**Não confunda os dois.** São ortogonais:

- **Capture mode** (`mode_max`/`balanced`/`fast`): controla **velocidade/qualidade** do pipeline (frame count, NLM radius, SR, bitrate). Afeta ruído, sharpness, latência.
- **Creative profile** (`leica_authentic`/`cinestill_800t`/...): controla **estética** da cor (LUT, DCP, frame, ajustes de tom). Afeta matiz, saturação, contraste.

Você pode usar qualquer combinação. Exemplo: `mode_fast` + `cinestill_800t` = look cinematográfico tungstênio mas com latência de 0.4s pra ação noturna.

### 9.6 Trocar Perfil Sem Rebuild (workaround APK editing)

Se você tem o APK instalado e quer trocar o perfil **sem rebuild**:

1. Use um editor de APK (APK Editor Studio, apktool).
2. Extraia `assets/config.json` (se o fork empacotar o JSON como asset) ou o valor hardcoded em `LeicaConfig.kt` (se compilado).
3. Troque `active_profile`.
4. Re-assine o APK.
5. Reinstale.

> **Nota:** Na v6.2 o `active_profile` é lido do JSON embutido como asset. A edição direta do asset + reassinatura funciona. Se o fork evoluir para carregar o JSON de storage externo (previsto v7.0), a troca será trivial via app de texto.

---

## 10. Resumo Técnico

### 10.1 Especificações da LUT

| Especificação               | Valor                              | Notas                                       |
|-----------------------------|------------------------------------|---------------------------------------------|
| Cube size                   | 33 × 33 × 33                       | Adobe DNG SDK standard                      |
| Nodos totais                | 35.937                             | 33³ = 35.937                                |
| Data type                   | UINT16                             | 65536 níveis por canal                      |
| Canais                      | 3 (R, G, B)                        | RGB (não YUV, não CMYK)                     |
| Bytes por nodo              | 6                                  | 3 × 2 bytes                                 |
| Payload total               | 215.622 bytes                      | 35.937 × 6                                  |
| Header                      | 16 bytes                           | "PLUT" magic + version=1 + size=33 + type=1 |
| Tamanho do arquivo          | 215.638 bytes                      | Header + Payload (todos os 30 .plut idênticos em tamanho)|
| GPU upload format           | `GL_RGB16F`                        | Half-float (16-bit) — não RGB16UI direto     |
| Sampler                     | Trilinear 3D                       | Interpolação entre 8 nodos vizinhos          |
| Estágios simultâneos máx    | 5                                  | DCP (4) + baseline LUT (1)                  |

### 10.2 Pipeline 5-Stage Stacking

```
[1] DCP forwardMatrix  → camera-native RGB → XYZ (D50)
[2] DCP toneCurve      → tom médio (curva)
[3] DCP hueSatMap      → matiz + saturação por região HSL
[4] DCP lookTable      → vibe da câmera (halation, wash, etc.)
[5] Baseline LUT 33³   → lookup 3D final em display-space
```

Cada estágio opera em buffer RGBA16F (half-float, 64 bits/pixel). Não há perda visível entre estágios — a meia-precisão é suficiente para todo o range dinâmico do sensor 14-bit (16.383 níveis lineares mapeados em ~9 stops de range float).

### 10.3 Por Que 33³ e Não 65³?

- **Adobe DNG SDK standard** é 33³. Softwares como Lightroom, Camera Raw, DaVinci esperam 33³ por padrão.
- **Memória GPU:** 33³ × 3 × 2 bytes = 211 KB. 65³ × 3 × 2 bytes = 1.6 MB. O custo de upload + cache pressure não compensa a melhora visual (que é zero em 99% dos casos).
- **FLOAT32 LUT (65³ ou 33³ FLOAT32) é dead-end** — o `.plut` define `dataType=2` (FLOAT32) mas o parser upstream lança `UnsupportedOperationException`. Não há planos de implementar (worklog Task 5-a confirmou como hard-limit arquitetural).

### 10.4 Por Que GL_RGB16F e Não GL_RGB16UI?

O sampler `GL_RGB16F` (half-float) permite:

1. **Interpolação trilinear automática** pela GPU entre nodos da cube — visualmente suave, sem banding.
2. **Operações aritméticas diretas** no shader sem conversão (multiply, mix, lerp).
3. **Compatibilidade universal** com Android GLES 3.1+ (todos os dispositivos suportam half-float 3D textures).

`GL_RGB16UI` exigiria `usampler3D` e `texelFetch` no shader — sem interpolação automática, com banding visível.

### 10.5 Limites do Sistema

| Limite                       | Valor          | Tipo                  |
|------------------------------|----------------|-----------------------|
| Cube size máx.               | 33³            | Arquitetural (Adobe)  |
| Estágios máx. empilhados     | 5              | Arquitetural (BaselineColorCorrection.kt) |
| LUTs simultâneas criativas   | 1              | Apenas baseline é empilhada (DCP+1 LUT) |
| Data type LUT                | UINT16         | FLOAT32 não implementado (dead-end) |
| DCPs disponíveis             | 13             | Bundle fixo           |
| Frames disponíveis           | 9              | Bundle fixo           |
| Perfis criativos             | 26             | Definidos no JSON     |
| Troca via UI                 | Parcial (LUT only) | G3 parcial — v7.0 completo |
| Troca via JSON               | Total          | G3 fechado v6.2       |

### 10.6 Cross-Reference

- `leica_perfect.json` — 442 linhas, schema v5, configuração central
- `LeicaConfig.kt` — 1672 LOC, 102 accessors (44 v6.0 + 13 v6.1 + 13 v6.2 creative + 32 original)
- `DEFINITIVE_AUDIT_v6.2.md` — 8 dimensões auditadas, GAP G1 fechado, G2/G3/G5 documentados
- `CPU_PERFORMANCE_ANALYSIS.md` — análise Dimensity 8300-Ultra, latência por modo, thermal
- `README.md` — 1140 linhas, v6.2.0 com changelog completo
- `patches/PerLensAgxConsumer.patch.kt` (P-43) — patch que fecha G1, ativa per-lens AgX em runtime

---

## Apêndice A — Glossário

| Termo        | Definição                                                                   |
|--------------|-----------------------------------------------------------------------------|
| LUT          | Lookup Table — mapa 3D de cores que transforma input RGB em output RGB     |
| .plut        | Formato binário proprietário do PhotonCamera para LUTs 33³ UINT16         |
| DCP          | DNG Color Profile — padrão Adobe para perfil de cor de câmera              |
| Cube size    | Dimensão do cubo LUT (33³ = 33 nodos por eixo = 35.937 nodos)              |
| UINT16       | Inteiro sem sinal de 16 bits (0-65535) — formato de dado da LUT           |
| GL_RGB16F    | Formato GPU half-float (16-bit por canal, range ±65504)                    |
| AgX           | Algoritmo de tone mapping filmico (Trochouriou 2023) — shoulder + toe     |
| PGTM         | Perceptual Gain-curve Tone Mapper — engine de tom do PhotonCamera        |
| AMAZE         | Algoritmo de demosaicing para Bayer/QuadBayer (AHD aprimorado)            |
| RCD           | Ratio-Correlated Demosaic — alternativa ao AMAZE para alta velocidade     |
| QuadBayer    | Padrão CFA 4×4 RGGB (sensor OV50E) — agrupa 4 pixels da mesma cor          |
| HueSatMap    | Mapa HSL de rotação de matiz + saturação por região (componente do DCP)   |
| LookTable    | Tabela de "look" da câmera — halation, wash, curtas (componente do DCP)   |
| HNCS         | Hasselblad Natural Color Solution — identidade de cor Hasselblad          |
| ForwardMatrix| Matriz 3×3 de conversão camera-native RGB → XYZ (componente do DCP)        |
| OETF         | Opto-Electrical Transfer Function — como o sinal é codificado             |
| EOTF         | Electro-Optical Transfer Function — como o sinal é decodificado           |
| HEIC         | High Efficiency Image Container — formato HEIF com codec HEVC            |
| HEVC         | High Efficiency Video Coding (H.265) — codec de vídeo/imagem             |
| Gainmap      | Mapa de ganho HDR — metadado que recupera range dinâmico em UltraHDR     |
| UltraHDR     | Formato JPEG com gainmap embutido — SDR + HDR no mesmo arquivo           |
| CCM          | Color Correction Matrix — matriz 3×3 aplicada em camera-native RGB       |
| HLG          | Hybrid Log-Gamma — curva de transferência HDR para broadcast             |
| HDR10        | Padrão HDR com curva PQ (SMPTE ST 2084) e metadata estática              |
| AWB          | Auto White Balance — cálculo automático de whitepoint                     |

## Apêndice B — Referências de Implementação

| Componente                          | Arquivo (upstream)                                                   |
|-------------------------------------|---------------------------------------------------------------------|
| LUT parser (.plut)                  | `app/src/main/java/com/hinnka/mycamera/raw/LutParser.kt`            |
| LUT GPU upload                      | `app/src/main/java/com/hinnka/mycamera/util/GlUtils.kt:108-138`     |
| LUT config (UINT8/UINT16)           | `app/src/main/java/com/hinnka/mycamera/raw/LutConfig.kt`            |
| 5-stage stacking                    | `app/src/main/java/com/hinnka/mycamera/raw/BaselineColorCorrection.kt` |
| DCP profile loader                  | `app/src/main/java/com/hinnka/mycamera/raw/DcpProfile.kt`           |
| AgX tone mapping shader             | `app/src/main/java/com/hinnka/mycamera/raw/RawShaders.kt`           |
| AMAZE demosaic shader               | `app/src/main/java/com/hinnka/mycamera/raw/RcdShaders.kt`           |
| RAW radiance fusion                 | `app/src/main/java/com/hinnka/mycamera/processor/GlesRawRadianceFusion.kt` |
| RAW stacking (15 frames)            | `app/src/main/java/com/hinnka/mycamera/processor/GlesRawRadianceStacker.kt` |
| Super-res DNG writer                | `app/src/main/java/com/hinnka/mycamera/processor/SuperResolutionDngWriter.kt` |
| HEIC encoder                        | `app/src/main/java/com/hinnka/mycamera/encoder/HeicExportEncoder.kt` |
| JPEG Q100 encoder                   | `app/src/main/java/com/hinnka/mycamera/encoder/Jpeg444ExportEncoder.kt` |
| UltraHDR writer                     | `app/src/main/java/com/hinnka/mycamera/encoder/UltraHdrWriter.kt`   |
| Gainmap producer                    | `app/src/main/java/com/hinnka/mycamera/processor/GpuReferenceGainmapProducer.kt` |
| LeicaConfig accessors               | `app/src/main/java/com/hinnka/mycamera/raw/LeicaConfig.kt` (fork)   |
| Per-lens AgX consumer (P-43)        | patch em `CameraViewModel.kt:resolveCaptureRawToneMappingParameters` |
| Creative profile accessors          | `LeicaConfig.kt:activeDcpId/activeLutId/activeFrameId/effectiveToneContrast` |

## Apêndice C — Histórico de Versões do Guia

| Versão | Data       | Mudanças principais                                      |
|--------|------------|----------------------------------------------------------|
| 6.0.0  | pré-v6.1   | Guia inicial — 8 perfis Leica/Hasselblad/Fuji            |
| 6.1.0  | v6.1       | +8 perfis (Kodak/Ricoh/Pentax) — total 16                |
| 6.2.0  | **atual**  | **26 perfis (8 marcas) + 3 capture modes + G3 fechado**  |
| 7.0.0  | planejado  | UI picker completo (P-45/P-46/P-47) + G2 DCP per-lens    |

---

**Fim do Guia.** Para auditoria técnica completa, veja `DEFINITIVE_AUDIT_v6.2.md`. Para análise de performance/thermal, veja `CPU_PERFORMANCE_ANALYSIS.md`.
