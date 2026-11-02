# Ověření převzetí a release nástrojů — 2. 11. 2026

Finální kód prošel [CI 41456590437](https://github.com/rznratn-sys/fortgps-field/actions/runs/41456590437)
a [CI 41456607330](https://github.com/rznratn-sys/fortgps-field/actions/runs/41456607330).
Aplikační zdroj: `f04dba7c2147bf1041482e6c2e872325c5a4633e`, beze změn.
Pipeline kód main: `da9f837b9ee66a432c16e3a25ca67522f513895b`;
delivery: `5cfb0d8fc396e0a3e3b4b4bd1b95b3b31243e2bd`.
Následná revize mění pouze dokumentaci.

- Sestaven debug APK, instrumentační APK a unsigned release APK.
- JUnit/Robolectric: **56 celkem, 53 úspěšných, 3 skipped, 0 chyb/selhání**.
  Skipped jsou soukromý export alpha2, soukromý Samsung JPEG a sada sedmi fotek.
  Rozdíl proti historickým 55/1 tvoří dvě nedostupné soukromé fixtures.
- Python: **14 původních + 20 release testů prošlo** v CI i lokálně (celkem 34).
- Debug/release lint: každý **0 chyb, 29 warnings**. GradleDependency 14, UseKtx 10;
  ApplySharedPref/OldTargetApi/DataExtractionRules/KaptUsageInsteadOfKsp/UsableSpace
  po jednom. Historických 14 warnings není aktuální výsledek.
- Bash syntax, Python compile, YAML/artifact output wiring a skutečný offline
  backup CLI proti veřejné fixture/schématu prošly. Lokální Android build blokovala
  síť, úplné sestavení a lint proto byly skutečně zopakovány v GitHub CI.

Release testy kontrolují podpisové parsery/tool exit code, certifikát, package,
verzi, debug/test flags, úplnou šestici assetů, poškození manifestu/checksumů,
reporty, chybějící secrets, ochranu Environment, obnovené zálohy a JSON.
Test celého alpha4 bundle explicitně mockuje kryptografii a používá testovací
bajty; ověřuje manifest, verzi zprávy, certifikátová pole a asset inventory.
Žádný keystore se při těchto testech nevytváří.

Skutečný podpis novou identitou, publikace a veřejný readback dosud neproběhly;
jejich workflow joby byly správně skipped. Nové signing credentials/zálohy
teprve vyžadují vlastníka. Fyzický Samsung, instalace přes odinstalaci, skutečný
export/import telefonu, živý GNSS, Samsung Gallery a produkční Drive nejsou ověřeny.

Při vydání tento soubor není kopírován do assetu. Pipeline vytvoří vlastní
TEST_REPORT.md ze skutečných JUnit XML, debug/release lint XML a Python výsledků
konkrétního běhu, s přesným zdrojovým/pipeline commitem a ID Actions run.
