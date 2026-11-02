# Pi-local signing: ověřené výsledky — 2. 11. 2026

Finální kód: main `af0dba69971f99dcb3e0161a9b46d7f2940bde72`,
delivery `0c5ebe33c4e27ccfeafd7ab0cc8083c95bec929a`. Následná revize upravuje jen dokumentaci.
[Finální CI main — 41464270883](https://github.com/rznratn-sys/fortgps-field/actions/runs/41464270883).
Aplikace zůstává přesně f04dba7c2147bf1041482e6c2e872325c5a4633e.

## Build a kontrolní testy

- Debug APK, instrumentační APK a unsigned release APK sestaveny; unsigned
  APK zarovnán Build Tools 35 zipalign -P 16 před podpisem.
- Aapt ověřil cz.fortgps.field, 2.0.0-alpha3 / 20002 a release/debug/test flags.
- JUnit/Robolectric XML: 56 celkem, **53 passed, 3 skipped, 0 failures/errors**.
  Skipped jsou soukromý export alpha2, soukromý Samsung JPEG a sada sedmi fotek.
- Python: **14 původních + 26 release/handoff testů prošlo**, také všech 40 lokálně.
- Debug/release lint: každý **0 errors, 29 warnings**. Historické hodnoty 55/1
  a 14 warnings nejsou výsledkem současného prostředí bez soukromých fixtures.
- Python compile, Bash syntax, YAML a artifact output wiring prošly.

## Skutečná kryptografie — bez mocku

Stejný skript integration_signing.py byl úspěšný na Ubuntu 24.04 **x86_64**
a **aarch64** s Temurin JDK 17 a připnutým Java apksigner.jar Build Tools 35.0.0.
Příklad plných ARM/x86 zpráv: [41462887930](https://github.com/rznratn-sys/fortgps-field/actions/runs/41462887930);
finální běh výše je zopakoval po doplnění publikačních kontrol.

Každá architektura prošla 10 kontrolami:

1. Skutečné podepsání skutečného unsigned alpha3 APK a apksigner ověření.
2. Opakované podepsání se stejnou soukromou identitou.
3. Zachování obsahu, velikosti a komprese všech původních ZIP souborů a zarovnání.
4. Odmítnutí existujícího výstupního adresáře.
5. Odmítnutí chybného hesla bez zpřístupnění částečného bundle.
6. Odmítnutí jiného očekávaného certifikátu.
7. Odmítnutí testovacího bundle pro produkční publikaci.
8. Skutečné odmítnutí dodatečně pozměněného APK podpisovým nástrojem.
9. Použitelnost obnovené kopie dočasného keystore přes certifikát/CSR.
10. Odmítnutí testovacího subjectu certifikátu pro produkční režim i bez JSON příznaku.

Testovací RSA 2048 klíče (platnost 2 dny, CN FortGPS TEST ONLY) i podepsané testovací
APK byly ve všech úspěšných zkouškách odstraněny spolu s dočasným adresářem.
Artifacts obsahují jen unsigned handoff, veřejný nástroj a veřejné crypto-report.json;
žádné keystore, heslo, obnovovací kopii nebo podepsaný testovací APK.
První discovery běh skončil před generováním klíče na nepřipnutém otisku JAR;
po jeho získání z SDK v CI a připnutí kontroly prošly. Bypass do produkce neexistuje.

## Co ověřeno není

- Fyzické Raspberry Pi 5, Raspberry Pi OS, instalace soukromého JDK, jeho reálná
  zátěž společně s Věc a vlastní nezávislá šifrovaná záloha nebyly dostupné.
- Žádná produkční podpisová identita nevznikla, nebyla nahrána ani použita.
- Žádný draft/release nebyl vytvořen/publikován; publish/public-readback joby byly
  správně skipped. Budoucí staging, publikace a veřejný readback vyžadují samostatný souhlas.
- Neproběhla instalace Samsungu, skutečná migrace uživatelských dat ani živé GNSS/Drive/Gallery.
- Alpha4 plán/identita jsou testované a klíč se opakovaně používá; skutečný budoucí
  alpha4 zdroj ani instalace aktualizace telefonu zatím neexistují.

## Reprodukce testu s dočasnou identitou

Na cloudovém hostu s předem ověřeným handoffem a připnutým JAR:

```sh
python3 scripts/release/integration_signing.py \
  --input unsigned --jar portable-tool/apksigner.jar --report crypto-report.json
```

Tento příkaz vytváří pouze krátkodobý testovací klíč a po sobě uklidí. Nic
neuploaduje a nemá publikační krok. Produkční návod: OWNER_RUNBOOK.md.
