# Field alpha3: audit Samsung SEF

## Doložené vstupy a omezení

Soukromý export z 1. 11. 2026 (SHA-256
`10e2a5ccff470726139f56c437a6eade0f1fa2c1005bc332f2de862df3b8c432`)
obsahuje 21 relací toho dne, 36 měření (21 gps, 15 network), 15 dokončených
fotografických relací, 12 stavů `verified` a 3 `failed`. Jde o stavy aplikace,
nikoli nezávislé ověření 12 skutečných změněných JPEG.

Odmítnuté názvy dle exportu: `Camera_Scene_Info` u `20261101_084824.jpg`,
`Gallery_DC_Data` u `20261101_100209.jpg` a `20261101_111357.jpg`.
Původní JPEG nebyly nalezeny při cíleném hledání těchto názvů na Drivu a v Library.
Tento výsledek neprokazuje jejich úplnou neexistenci v jiných archivech.

## Dosavadní zapisovač a struktura

Alpha2 rozeznává JPEG segmenty, odděluje standardní XMP APP1 a chrání všechny
ostatní segmenty a celý obrazový scan/trailer bajtovým porovnáním. XMP vkládá
Apache Commons Imaging bez rekomprese. EXIF včetně GPS zůstává chráněný.

Samsung SEF je kontrolován za JPEG EOI. Na konci je SEFT, před ním délka adresáře,
adresář SEFH a položky s typem, délkou a záporným relativním offsetem od SEFH.
Blok nese také typ a délku jména. Dosavadní povolené typy jsou Image_UTC_Data,
MCC_Data, Color_Display_P3 a Camera_Capture_Mode_Info. Tři terénní fotografie
odmítl seznam povolených jmen, nikoli neúspěšný odhad polohy.

Vložení APP1 posune SEFH i jeho datové bloky stejným způsobem. **Vnější relativní
offsety se při bajtově shodném traileru nemění. To samo neprokazuje platnost
vnitřních odkazů v neznámém payloadu.** ExifTool například výslovně upravuje
absolutní offsety v QDIO audio bloku. Proto nelze generalizovat všechny SEF
varianty podle společného koncového SEFT nebo jen názvu.

Primární implementační zdroj: ExifTool `lib/Image/ExifTool/Samsung.pm`, čteno
1. 11. 2026: https://github.com/exiftool/exiftool/blob/master/lib/Image/ExifTool/Samsung.pm
(`ProcessSamsungTrailer`, tabulka Trailer). Komentáře obsahují jména obou
problémových typů, ale nedokládají kompletní význam každého pole jejich payloadů.

Původní regresní JPEG z 31. 10. 2026 má SEF verze **107**, čtyři známé
položky a byl dostupný v původním ZIPu. Tento dodaný soubor již má XMP alpha2.
Test ověřuje původní bajtovou idempotenci se skutečnou historickou relací a
odmítnutí konfliktu. Pro nový zápis vytvoří pouze v paměti odvozenou fixture
odstraněním vlastností FortGPS z XMP; EXIF, obraz a SEF se nemění. Nejde o dosud
neoznačený originál z fotoaparátu a dodaný soubor se nepřepisuje.
Verze 101 je popsána v implementačním
zdroji ExifTool. Ověření tohoto jednoho JPEG se nevztahuje na problémové fotografie
z 1. listopadu.

## Rozhodnutí alpha3

**Podpora Camera_Scene_Info a Gallery_DC_Data se nerozšiřuje.** Nejsou dostupné
skutečné bajty ani ověření Samsung Gallery. Ochranné odmítnutí je zachováno a jeho
zpráva vysvětluje neověřené vnitřní odkazy i zachování originálu. GPS se ukládá
nezávisle a zůstává dostupné.

Ochranný parser je zpřísněn: explicitní verze SEF 101 a 107, shoda typu bloku s typem
v adresáři, známá kombinace jména a typu, validní rozsahy bez překryvů a bez
nezařazených bajtů. Délky se porovnávají bez přetečení součtu. Hranice JPEG EOI
se hledá průchodem délek segmentů, entropy escape a restart markerů včetně dalších
scanů; prosté vyhledání prvního FF D9 není dostatečné pro více scanů.

Žádné odstranění SEF, nové fotografie, změna EXIF, rekomprese nebo tichý fallback.
Původní mechanismus zálohy, obnovení, konfliktu a idempotence zůstává. Neznámé
verze/typy, Motion Photo, MPF a extended XMP nadále odmítá.

## Co je nutné k budoucímu rozšíření

Získat originály přes ZIP, zachovat SHA-256 a pracovat výhradně na kopiích.
Rozebrat všechny SEF položky včetně payloadů, doložit každý dotčený offset,
porovnat EXIF/XMP a kompletní obrazový stream nezávislým parserem (např. ExifTool),
ověřit opakovaný zápis a konflikt. Následně ověřit Galaxy Gallery na fyzickém
Samsungu včetně souvisejících editačních funkcí. Pokud zůstává neprůhledný
payload s možnými absolutními odkazy, ponechat odmítnutí.

Syntetické testy adresáře a parseru neověřují konkrétní terénní fotografie ani
Samsung Gallery. Pouhé otevření JPEG v dekodéru není dostatečné ověření SEF.
