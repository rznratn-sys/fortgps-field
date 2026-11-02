# Převzetí alpha3 — ověřený stav 2. 11. 2026

## Repozitář a zdroje

- `field-2-native`: `f04dba7c2147bf1041482e6c2e872325c5a4633e`, schválený zdroj alpha3.
- Původní delivery větev: `352f603df6b8aedbc992a26aaab209bc3a3e95b1`.
  Compare API proti f04dba7 potvrzuje pouze přidaný publish-alpha3.yml.
- Výchozí `main`: `302b753959780d6c9ce777db3d713a73de58b735`, samostatná webová aplikace.
- [PR #2](https://github.com/rznratn-sys/fortgps-field/pull/2) přidává release nástroje na main;
  [PR #3](https://github.com/rznratn-sys/fortgps-field/pull/3) opravuje původní delivery workflow.
  Jsou připravené k review, nikoli sloučené. Android ani webový aplikační kód se nemění.

Gradle: JDK 17, wrapper 8.11.1 s SHA-256 distribuce, AGP 8.9.1, Kotlin 2.0.21,
compile/target SDK 35, minSDK 26. Package/namespace `cz.fortgps.field`,
versionName `2.0.0-alpha3`, versionCode `20002`. Bez FIELD_KEYSTORE vzniká unsigned
release; nová pipeline klíč Gradlu nikdy nepředává ani nepoužívá jeho fallback aliasu.

Room v2, exportovaná schémata 1/2; migrace 1→2 pouze přidává xmp_operations.
Sessions/outbox se nemažou, destructive fallback není nastaven. Alpha2→alpha3
nemění DB verzi. Odinstalace při změně podpisu ovšem privátní DB odstraní.

GNSS používá GPS_PROVIDER, ruční potvrzení konkrétního fix UUID, monotónní limit
10 s, odmítnutí mock/budoucích/neplatných/out-of-order fixů a zastavení odběru
v pozadí. Nejlepší přesnost není automaticky ukládaný historický fix.
Fotografické hranice zůstávají oddělené od čekání na závěrečný GNSS.

JPEG/XMP zachovává ochranu EXIF/obrazu/traileru, SEF 101/107 whitelist a historickou
alpha2 idempotenci. Camera_Scene_Info, Gallery_DC_Data, neznámé varianty,
Motion Photo/MPF/extended XMP zůstávají odmítnuté. Podpora se nerozšiřuje.

## Skutečně zopakované testy

Finální aplikační/pipeline kód prošel CI na obou větvích:
[main nástroje — 41456590437](https://github.com/rznratn-sys/fortgps-field/actions/runs/41456590437),
[delivery — 41456607330](https://github.com/rznratn-sys/fortgps-field/actions/runs/41456607330).
Testovaný pipeline commit main `da9f837b9ee66a432c16e3a25ca67522f513895b`,
delivery `5cfb0d8fc396e0a3e3b4b4bd1b95b3b31243e2bd`; následná revize upravuje jen dokumentaci.

- Debug APK, instrumentační APK a unsigned release APK sestaveny z přesného f04dba7.
- JUnit/Robolectric XML: 56 celkem, **53 passed, 3 skipped, 0 failures/errors**.
  Přeskočeny FieldBackupTest.privateAlpha2FieldBackupRoundTripAndOfflineReopen,
  RealJpegTest.privateSamsungOriginalPreservedAndIndependentlyParsed a
  XmpTest.actualPilotSevenPhotos — soukromé fixtures nejsou dostupné.
- Python: **14 původních + 20 release testů prošlo**, rovněž všech 34 lokálně.
  Ověřen i CLI preflight/porovnání JSON proti veřejné fixture a skutečnému schématu.
- Debug/release lint: každý **0 chyb, 29 varování**. Rozpis: GradleDependency 14,
  UseKtx 10; ApplySharedPref, OldTargetApi, DataExtractionRules,
  KaptUsageInsteadOfKsp a UsableSpace po jednom. Závislosti ani target se nemění.
- Bash syntax, Python compile, YAML a předávání artifact ID ověřeny.

Historická zpráva uváděla 55/1 s dvěma soukromými fixtures; zde jsou navíc dvě
skip. Historických 14 lint warnings nepřebíráme: aktuální součet je 29, z toho
15 tvoří GradleDependency/OldTargetApi. Staré lint XML zde nebylo parsováno,
proto přesnou příčinu rozdílu nelze potvrdit. Starší CI 41337560798/41337556994
je také success; samotné historické tvrzení o podepsaném buildu nedokládá dnešní
obnovitelnost klíče. Lokální Gradle blokovala síť; nový GitHub CI build toto omezení obešel.

Nové kryptografické podepsání, publikace, public readback, fyzický Samsung,
živý GNSS/Gallery/Drive a skutečná migrace telefonu **nebyly provedeny**.
Alpha4 bundle test explicitně mockuje kryptografii a žádný klíč negeneruje.

## Klíč a bezpečnost vydávání

Vlastník potvrdil „Původní klíč nemám.“ Cloud readiness má observations_current=true,
secrets=[], runtime_variables=[], outbound_identities=[]; podpisový vault zde
není nakonfigurovaný. GitHub connector neposkytuje Secrets/Environment metadata,
shell gh nemá funkční autentizaci. Nelze tedy tvrdit, že byla prohledána všechna
jiná soukromá úložiště; vlastnická kontrola seznamů je popsána v OWNER_RUNBOOK.md.
Žádný nový klíč ani heslo nebyly vytvořeny, čteny nebo nahrány.

Starý veřejný alpha2 certifikát dle vydání:
SHA-1 B7:C8:49:00:77:67:0B:EC:95:94:BF:4C:F6:6A:B2:09:A1:43:1D:8D,
SHA-256 ef2ee10dc4833159a50f0b550e8eb4eeac53f21ef47079136b5151990b74fb72.
Z APK/certifikátu nelze obnovit soukromou část. Nový otisk zatím neexistuje.

Původní run 41442451499 selhal v Require private signing material; build/signing
byly skipped. Starý workflow měl push signing, globální contents:write,
pohyblivé action tags, pevně starý certifikát a klíč v procesu Gradlu.
Náhrada odděluje build/sign/publish/public readback, připíná actions plnými SHAs,
klíč zpřístupňuje pouze signing kroku přes chráněný Environment, omezuje write
na publikační job a nikdy nepřepisuje existující release/tag/assets.
Push/PR nesmějí podepisovat; ruční signing jen z main vyžaduje přesný schválený
pipeline SHA, certifikát a matching attestation skutečné obnovy nezávislé zálohy.
Samotná YAML podmínka nenahrazuje ochranu main a deployment branch/reviewer pravidla.

Repo rulesets API vrátilo []; branch-protection endpoint 403 integračního oprávnění.
Správná ochrana main/Environments tedy není doložena ani tímto PR nastavena.
Lokální upload nástroj odmítne Environment bez main-only politiky, nezávislé
review a zákazu admin bypass. Zálohu média/účtu musí fyzicky ověřit vlastník;
nástroj kontroluje obnovené bajty, certifikát a použitelnost private-key entry přes CSR.

## Release a migrace

Závěrečný dotaz na Releases vrací pouze `v2.0.0-alpha2`. Alpha3 ani její draft
nevznikly; neexistuje ověřený nový APK odkaz. Signing/publish/readback joby byly
v kontrolních bězích správně skipped. Unsigned artifact není instalační release.

JSON přenese aktuální sessions, nikoli historii outboxu/remote ID, xmp_operations,
privátní zálohy JPEG, fotografie nebo OAuth. Import vytvoří nové pending položky.
Změna certifikátu vyžaduje ověřený export mimo aplikaci před odinstalací; import
se kontroluje podle UUID, celých payloadů, revizí a XMP. drive.file může po změně
OAuth identity skrýt staré soubory a zvýšit riziko duplicit; sync musí zůstat
vypnutý do nezávislé kontroly. Podrobnosti a STOP podmínky: INSTALL.md.

Další kroky: review/merge pipeline, ochrany GitHubu, výslovný souhlas vlastníka
s novou identitou, vytvoření na jeho zařízení, nezávislé zálohy a obnova,
teprve pak samostatně schválený upload a ruční vydání. Přesné příkazy a alpha4:
OWNER_RUNBOOK.md. Stejný dlouhodobý klíč se zachová pro další vydání.
