# Nezávislé převzetí vydávání alpha3 — 2. 11. 2026

## Potvrzený stav

GitHub API: veřejný repozitář `rznratn-sys/fortgps-field`, default `main`.
`field-2-native` = `f04dba7c2147bf1041482e6c2e872325c5a4633e`.
`field-2-alpha3-delivery-20261102` = `352f603df6b8aedbc992a26aaab209bc3a3e95b1`.
Compare API mezi nimi ukazuje pouze přidaný publish-alpha3.yml. APK kód alpha3
se při převzetí nemění. Main je samostatná webová větev: release workflow proto
používá oddělený checkout přesného aplikačního commitu.

Gradle: JDK 17, wrapper 8.11.1 s distribution SHA-256, AGP 8.9.1,
Kotlin 2.0.21, compile/target SDK 35, minSDK 26. `applicationId` a namespace
`cz.fortgps.field`, versionName `2.0.0-alpha3`, versionCode `20002`.
Gradle bez FIELD_KEYSTORE vytvoří unsigned release. Stará signing konfigurace
má fallback aliasu/hesla; nová pipeline ji nepoužívá a klíč do Gradlu nepředává.

Room: verze 2, exportovaná schémata 1/2. Jediná migrace 1→2 přidá
xmp_operations. Sessions/outbox se nemažou; není zapnutý destructive fallback.
Alpha2→alpha3 nemění DB verzi. To se vztahuje na upgrade se zachováním DB;
odinstalace při změně podpisu ji odstraní.

GNSS: LocationCapture žádá GPS_PROVIDER, nikoli network/cached fallback;
GnssPolicy odmítá mock, špatné souřadnice, budoucí/staré/out-of-order fixy.
Ruční potvrzení kontroluje konkrétní measurement UUID a monotónní freshness 10 s.
Nejlepší přesnost není automaticky vybraný historický fix. Pozadí zastavuje odběr.
Fotografické hranice jsou oddělené od čekání na závěrečný fix.

JPEG/XMP: existující ochrana EXIF/obrazu/traileru, kontrola SEF 101/107,
whitelist známých položek, idempotence historického alpha2 XMP. Camera_Scene_Info,
Gallery_DC_Data, neznámé varianty a Motion Photo/MPF/extended XMP zůstávají
odmítnuté. Tato práce nepřidává jejich podporu a neověřuje reálné fotografie.

Dokumentace alpha3 obsahuje historická tvrzení o lokálních soukromých fixtures,
podepsaném buildu a původním certifikátu. Nejsou důkazem současné dostupnosti
klíče. Nový OWNER_RUNBOOK/INSTALL a generovaný TEST_REPORT je pro nové vydání
nahrazují; původní technické zprávy zachováváme jako historický záznam.

Při dotazu na Releases existovala pouze `v2.0.0-alpha2`; alpha3 ani draft alpha3
nebyl vrácen. Změny zde žádný release/asset/tag nevytvářejí ani nenahrazují.

## Podpisové materiály a omezení přístupu

Cloud runtime hlásí observations_current=true, secrets=[], runtime_variables=[]
a outbound_identities=[]. Žádný nakonfigurovaný podpisový vault zde není.
Vlastník následně výslovně potvrdil „Původní klíč nemám.“
GitHub connector neposkytuje Secrets/Environments metadata API; endpointy secrets
nezkoušíme obcházet. Shell gh auth nefunguje a proxy spojení selhalo.
Není tudíž prokázáno, že v každém jiném soukromém úložišti nic není. Vlastník musí
zkontrolovat metadatové seznamy v Settings podle OWNER_RUNBOOK před uploadem.
Klíč ani hesla nebyly čteny, vytvořeny, vypsány nebo nahrány.

Starý alpha2 veřejný certifikát dle release:
SHA-1 B7:C8:49:00:77:67:0B:EC:95:94:BF:4C:F6:6A:B2:09:A1:43:1D:8D,
SHA-256 ef2ee10dc4833159a50f0b550e8eb4eeac53f21ef47079136b5151990b74fb72.
Soukromou část nelze získat z APK ani certifikátu. Nový otisk ještě neexistuje.

## CI a vlastní ověření

Zdrojový CI [41337560798](https://github.com/rznratn-sys/fortgps-field/actions/runs/41337560798)
a 41337556994 jsou success na f04dba7. Načtené job steps a log prvního potvrzují
assembleDebug, testDebugUnitTest, assembleDebugAndroidTest a lintDebug;
BUILD SUCCESSFUL, Python Ran 14 tests / OK. Testové artifacts jsou dostupné.
Přesný počet úspěšných JUnit testů není ve staženém textovém logu vytištěn;
historických 55/1 proto nezaměňujeme za nově nezávisle spočítaný výsledek.
Bez dvou soukromých fixtures dokumentace očekává navíc dva přeskočené testy.

Původní publish run [41442451499](https://github.com/rznratn-sys/fortgps-field/actions/runs/41442451499)
selhal v Require private signing material. Build a signing byly skipped.

V aktuálním prostředí skutečně prošlo původních 14 Python testů; po přidání
release testů prošlo 33 testů (19 nových). Bash syntax a Python compile/YAML parse
prošly. Android Gradle pokus byl proveden, ale nemohl stáhnout distribuci:
proxy v shellu nedostupná a síťový přístup JVM blokovaný. Nejde o selhání testů
aplikace. Nová CI pipeline znovu spouští build/test i debug/release lint na f04dba7,
sbírá XML počty a vytváří zprávu ze skutečných výsledků daného běhu.

Kryptografický test s novým release klíčem, podpis nového APK, jeho publikace,
public readback a reálná instalace dosud neproběhly. Parsery/trust boundaries,
chybějící secrets, poškozené assety, reporty, neplatný certifikát, přístup do
Environment, kontrola obnovy a JSON jsou automaticky testovány bez generování klíče.

## Bezpečnost pipeline a čekající vlastnické kroky

Původní workflow mělo push trigger, globální contents:write, pohyblivé action tags,
klíč v Gradle procesu a pevně starý certifikát. Nahrazuje jej validation pro
push/PR a explicitní ruční podpis/publikace z main. Actions jsou připnuté plnými
commity ověřenými přes GitHub refs API. Contents:write má jen publikační job;
signing job nemá token pro zápis. Žádný pull_request_target, sdílený runner,
Gradle cache v signing jobu, wildcard upload mimo delivery nebo přepis assets.

Signed job je oddělen od buildu a schválen přes chráněný Environment.
Vyžaduje přesný pipeline SHA, certifikát a timestamp obnovené zálohy odpovídající
certifikátu. Public repository var je pouze otisk certifikátu, nikoli tajný údaj.
Nezávislé zálohy nemůže cloud fyzicky ověřit; lokální restore/CSR a vlastnické
potvrzení jsou explicitní podmínkou. Kdokoli může navrhnout změnu workflow;
jen branch restrictions, ochrana main a nezávislá review brání jejímu zneužití.
Tyto ochrany nesmí být nahrazeny pouhou podmínkou `if` uvnitř YAML.

Repo rulesets API vrátilo []; branch protection endpoint vrátil 403 integračního
oprávnění. Není tedy doložena správná ochrana main ani Environments.
Tyto nastavení tento PR sám nezapnul. Upload nástroj odmítá nechráněné prostředí.

Nejdřív review a instalace pipeline na main; pak schválení vytvoření nového
klíče na zařízení vlastníka, nezávislé zálohy a skutečná obnova; teprve potom
výslovně schválený upload Secrets a ruční workflow. Do té doby žádné APK vydání.
Datová migrace má oddělené STOP podmínky v INSTALL.md, včetně drive.file rizika
neviditelných historických souborů a duplicitního uploadu.

## Nové CI při převzetí (potvrzeno, doplňuje starší log)

[Kontrolní běh 41453567055](https://github.com/rznratn-sys/fortgps-field/actions/runs/41453567055)
na pipeline commitu 30596a42458296ece6b5ace3064aacb343160044 úspěšně sestavil
přesný f04dba7: debug APK, instrumentační APK, unsigned release APK, JUnit,
debug lint a release lint. Z XML nově spočítáno **56 testů: 53 passed, 3 skipped,
0 failures/errors**. Python zdroj 14/14, release nástroje 19/19. Oba linty mají
0 chyb a **29 varování**, což je rozdíl proti historickým 14. Rozpis ID varování
je doplněn do evidence následující revize pipeline. Podpis/publikace/public
readback byly správně skipped. Unsigned artifact není instalační release APK.

Pipeline dále předává artefakty přes výstupní ID jobu, aby opakování pouze
neúspěšného signing/publication jobu používalo přesný artifact úspěšného
předchůdce i po zvýšení run_attempt. Žádné hledání podle globu napříč běhy.
