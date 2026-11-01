# Výchozí audit 30. 10. 2026

Audit před implementací pracoval se skutečným GitHubem a Google Drivem.
Soukromé souřadnice, poznámky, originální JSON a zdrojové katalogy se nepublikují do Gitu.

## Field 1.1

Repozitář `rznratn-sys/fortgps-field`, větev main: čtyři výchozí soubory README.md,
index.html, manifest.webmanifest, sw.js. HTML označuje verzi 1.1, používá browser
geolocation, localStorage, samostatná UUID a Google Identity Services `drive.file`.
Neobsahuje nativní Android projekt, Room, fotografické relace ani výškovou vrstvu.

Původní upload: hledání podle názvu → create multipart → uložit vzdálené ID.
Slabiny vzhledem k novým požadavkům: samotná shoda názvu bez kontroly obsahu není
důkaz shodného záznamu; přerušení mezi create a lokálním potvrzením vyžaduje silnější
idempotenci. Webový token je dočasný a background sync funguje jen při otevřené stránce.
Web se v této etapě neopravuje ani nepřepisuje; zůstává provozní zálohou.

V explicitně zadané importní složce bylo přečteno všech **6 JSON záznamů**. UUID jsou
jedinečná a platná. Skutečné klíče:

`id, recorded_at, latitude, longitude, accuracy_m, position_time, type, note,
source, verification_status, position_role`.

Všech šest má `position_role=observer_device`. Žádný nemá fotografické časové
hranice, výšku nebo potvrzenou geometrii objektu. Nejsou zpětně interpretovány
jako object_survey. Soukromé originály byly použity k auditu migrace.

## Infrastruktura

Repozitář `rznratn-sys/fortgps` v době auditu obsahoval pouze README a .gitignore.
Není v něm produkční Field/Spatial runner, na který by bylo možné jen připojit webhook.
Publikovaný funkční kód i katalogy byly dohledány na Drivu.

| Publikace | Ověřený stav |
|---|---|
| OSM ČR 01 v1.0.0 | `fort_osm_cz_v1.0.0.gpkg`, namespace `cz-osm-20261024`, upstream SHA-256 `276bfcdb75db10a599921fef3722884521b9b7f19420214d397e253d63ad9ec7` podle skutečné provenience v české SQLite; nový OSM import neproveden |
| Spatial ČR 01 v1.0.0 | Horizontální run `e503a592984dfc710c06bbc6ad04c0ca`, oba R-tree a populace v rozšířené SQLite |
| Spatial ČR 02 v2.0.0 | Terrain run `8bec34b88a96cb33774ed0b1ed09fa25`, publikace completed; SHA-256 skutečně stažené DB `4465d818f69ed44bf5bf8bbc5bb09b56495718cd8cb2e582e2c908159ddbe8ba` |
| Francie Spatial 02 v2.0.0 | Horizontální run `d5b322718707de35187acf68b4df54c4`; terrain run `e82058093a37a1497c7ee80afc959c17`; SHA-256 skutečně stažené DB `9c51645ffdc8d53a618be7219ead7a1ac40c19da85d314a8136917032dbb0134` |

Obě skutečné SQLite a publikovaný kód byly otevřeny jen pro čtení. Zkontrolováno
schéma `spatial_runs`, `spatial_objects`, `spatial_object_versions`, `source_catalogs`,
`source_records`, `spatial_point_rtree`, `spatial_geometry_rtree`, `terrain_runs`,
`terrain_elevations`. Česká vrstva navíc poskytuje `spatial_populations` a populaci
`structures`. Stabilní UID a provenance se berou z katalogu, nesestavují se znovu.

`TerrainCatalog.nearby` přidává terrain údaje přes existující `_public`, u ČR umí
populační filtr před K. `nearby_3d` požaduje výškovou kompatibilitu; Field adaptér mu
nepodsouvá výšku telefonu jako výšku terénu. Česká výšková CRS je Bpv, francouzská
vrstva pochází z vlastního publikovaného IGN řešení; žádný nový DEM výpočet nenastal.

Národní česká obálka `[12.0,48.5,19.0,51.1]` je výslovně výpočetní, není státní hranice.
Automatický výběr národního katalogu podle této obálky by byl příliš silný předpoklad,
proto CLI vyžaduje explicitně zvolený katalog a mimo jeho oblasti neposkytuje fallback.

## Android prostředí

Výchozí prostředí mělo JRE 17 bez javac, bez Android SDK a Gradlu. Byly doplněny
lokální JDK 17, Android SDK 35 a připnutý Gradle pro skutečné sestavení. Nejde jen
o projekt napsaný bez pokusu o kompilaci. Konkrétní výsledky jsou v TEST_REPORT.md.

OAuth Cloud Console, souhlas vlastníka a přístup nativního klienta k původní složce
nejsou nahrazeny oprávněními konektoru v tomto chatu. Tato hranice zůstává výslovná.
