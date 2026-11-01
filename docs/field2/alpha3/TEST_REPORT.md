# FortGPS Field 2.0.0-alpha3: testovací zpráva

Datum: 1. listopadu 2026. Výchozí alpha2 commit:
`1a250d9364b0c13fbeff6274ac055f663fb4cd51`.
Výsledný zdrojový commit, konkrétní APK, podpis a CI běh určují release-manifest.json
v prerelease. Tento text neprohlašuje fyzické ověření telefonu.

## Implementováno

- GPS_PROVIDER, průběžné neměnné snapshoty, ruční potvrzení UUID aktuálního fixu,
  monotónní freshness limit 10 s, nekonečné čekání bez automatického uložení.
- Živé UI, rozlišení čekání, platného fixu, průběžného měření, ztráty signálu,
  stáří, pozastavení a chyby. Nejlepší přesnost je výhradně informativní.
- Pozastavení odběru v pozadí, nový fix po návratu, zachování ViewModelu při změně
  konfigurace, zahození nepotvrzeného záměru při ukončení procesu.
- Oddělení fotografických hranic od časů GNSS, ochrana hranic a hodin.
- Zachování historických dat, Room verze 2, migrace alpha1 1→2, revizní outbox,
  MediaStore journal a obnovy. Sémantické porovnání starého JSON při importu.
- Kontrola známých SEF verzí 101/107 a typů, bezpečné odmítnutí neznámých variant.
- Nové XMP uvádí alpha3. Odpovídající historické XMP alpha2 se nemění jen kvůli
  verzi zapisovače; platí i pro obnovu rozpracované alpha2 operace. Obsahový
  konflikt nebo neznámá verze zůstávají odmítnuté.

## Automaticky otestováno

Lokální JUnit/Robolectric: **56 testů, 55 úspěšných, 1 přeskočený, 0 chyb/selhání**.
Python kontrakt a podpisový parser: **14 úspěšných testů**.
Debug APK, instrumentační APK a debug lint sestaveny; lint má **0 chyb a 14 varování**.
Varování jsou zejména KTX doporučení, kapt, synchronní uložení hodinové epochy,
allocatable space a starší backup pravidla. Nejsou důvodem přepisovat tyto subsystémy.
Podepsaný release build a release lint rovněž prošly (0 chyb, 14 varování).
Skutečný apksigner ověřil stejný SHA-1/SHA-256 certifikát jako stažená alpha2;
aapt ověřil balíček a versionCode 20002. AGP ve vnořeném Android projektu vrací
`NO_SUPPORTED_VCS_FOUND`; jednoznačný commit/tree proto dokládá vnější manifest
a kontrola čistého Git worktree, nikoli chybějící vnitřní VCS identifikátor.

| Oblast | Skutečně provedená kontrola |
| --- | --- |
| GNSS politika | Network před GPS, velmi přesný network/fused/passive odmítnut, špatný GPS lze ručně potvrdit, zpřesnění i zhoršení, přesun, nejlepší versus aktuální, staré/future/out-of-order/duplicitní fixy, mock a neplatné údaje, nové získání signálu |
| UI | 2 Compose testy v Robolectric API 35 s nativním rendererem: zakázané/povolené potvrzení, konkrétní UUID, stale/recovery, chyba, opětovné připojení a zrušení |
| Životní cyklus | Robolectric API 28: zrušení prvního měření, dvojí potvrzení bez duplicit, background/resume, otočení Activity se zachováním ViewModelu a záměru, zrušení závěrečného měření beze změny uložené relace, modelové ukončení a nové vytvoření Activity/store |
| Fotografický interval | Dlouhé závěrečné čekání nepřibere pozdější snímek, legacy pravidla, chybějící/nesouhlasné hranice, změna hodin/epochy, smíšená alpha2/alpha3 relace, dosavadní EXIF/DST a hraniční případy |
| Historie a offline | Přiložených 24 relací načteno, validováno, round-trip JSON, uloženo do Room, znovu otevřeno; UUID, síťové polohy, revize, XMP stavy a payloady/outbox zůstaly zachovány. Samostatně zachována rozpracovaná XMP cesta zálohy |
| Upgrade alpha1 | SQLite fixture vytvořená podle skutečného exportovaného Room schématu v1 → migrace v2, zachované payloady, vzdálená ID a fronta; není to instalace APK na fyzický telefon |
| Drive | Dosavadních 6 MockWebServer testů identity, revizí, potvrzení obsahu, restartu, vzdáleného ID a retry; bez skutečného účtu nebo produkční složky |
| JPEG/XMP | Strukturální ochrana, idempotence, historický writer alpha2, cizí XMP, malformed XML, obsahové konflikty, syntetické SEF známé/neznámé položky, vadné offsety/typy/verze/trailer; 1 skutečný historický JPEG se SEF 107 |
| Podpis a manifest | Pozitivní formáty apksigner, chybějící/ambiguitní/cizí certifikáty, odmítnutí public-key digest místo certificate digest; vlastní APK se ověřuje skutečným apksigner a aapt |

Jeden přeskočený test je starší `actualPilotSevenPhotos`: sada všech sedmi snímků
nebyla v tomto běhu dostupná. Dostupný ZIP obsahuje jeden snímek a byl testován
samostatně. Starý podmíněný test Field 1.1 bez soukromé fixture nepředstavuje novou
kontrolu reálného exportu Field 1.1.

## Nezávisle ověřeno jiným parserem

Dodaný JPEG `20261031_185120.jpg` již obsahuje XMP alpha2. S odpovídající skutečnou
relací se ověřila bajtová idempotence; pro jinou relaci odmítnutí konfliktu.
Nový zápis se prováděl na **odvozené testovací fixture v paměti**, která odstraní
jen vlastnosti FortGPS z XMP kopie. Dodaný soubor nebyl změněn.

ExifTool 13.60, Pillow a samostatný rozbor SEF potvrdily proti dodanému souboru:

- Bajtově stejný obrazový scan včetně EOI a shodné dekódované pixely.
- Bajtově stejné původní EXIF, které v tomto vzorku zahrnuje GPS.
- Shodná ostatní metadata kromě očekávané změny XMP a délky souboru.
- Stejnou verzi SEF 107, čtyři bloky, jejich délky a SHA-256 payloadů.

Bajtové důkazy jsou v `jpeg-evidence.json`. Nejde o ověření Samsung Gallery ani
o všechny JPEG stejného modelu telefonu. ExifTool a vlastní kontrola nejsou
nezávislým organizačním auditem; jde o různé implementace parsování.

## Samsung SEF: výslovně nepodporované případy

`Camera_Scene_Info` a `Gallery_DC_Data` zůstávají odmítnuté. Originály tří
problémových fotografií nebyly dostupné pod zadanými názvy. Žádné syntetické testy
nejsou vydávány za ověření jejich vnitřních odkazů nebo kompatibility Gallery.
Podrobný důvod, struktury a další postup obsahuje SEF.md.

## Neověřeno na fyzickém zařízení

- Skutečný update release alpha2→alpha3 se zachováním nainstalované databáze.
  Shoda balíčku, vyšší versionCode a certifikát prokazují podpisový předpoklad,
  nikoli úspěšný upgrade konkrétního telefonu.
- Živý GNSS a pravdivost odhadu jeho přesnosti na Samsungu.
- Reálné ukončení procesu v přesném okamžiku Room/MediaStore zápisu, odebrání
  oprávnění během zápisu, plný disk, systémový souhlas a zamítnutí.
- Samsung Gallery a její editační funkce.
- Skutečný OAuth upload a odpojení/reconnect skutečného Drivu. Uživatelská OAuth
  konfigurace se nepředpokládá a v tomto vydání se neupravuje.
- Instrumentační APK byl sestaven, nikoli spuštěn na emulátoru nebo telefonu;
  UI a integrační výsledky výše jsou Robolectric simulace.

## Reprodukce a CI

JDK 17, Gradle 8.11.1, AGP 8.9.1, Kotlin 2.0.21, Android SDK/Build Tools 35.
Použít `scripts/build-alpha3.sh` s původní soukromou podpisovou konfigurací;
klíče nepatří do repozitáře. Následně `scripts/verify-alpha3.py` ověří certifikát,
balíček/verzi, čistý Git worktree a vytvoří manifest. Python testy:
`python -m unittest discover -s tests -v`.

Soukromé fixtures jsou volitelné: `FIELD_ALPHA2_BACKUP` a `FIELD_REAL_JPEG`.
Bez nich CI poctivě přeskočí navíc 2 testy. Nejsou committované. Compose UI testy
mají explicitní API 35, native graphics a paused looper. Původní pokus s API 28
nevykazoval stabilní idle stav a není vykazován jako úspěšný test UI.
CI zdrojového commitu je před publikací znovu ověřeno a identifikováno v manifestu.
Reprodukce build procesu neznamená garantovanou bajtovou shodu nezávislých buildů.

## Vyžaduje rozhodnutí uživatele

Žádné další rozhodnutí není nutné pro tuto omezenou alpha3: neověřené SEF varianty
zůstávají bezpečně odmítnuté. Je třeba provést telefonní test podle INSTALL.md.
Případná budoucí podpora odmítnutých variant vyžaduje původní JPEG v ZIPu a nové
rozhodnutí až po technickém ověření jejich payloadů.
