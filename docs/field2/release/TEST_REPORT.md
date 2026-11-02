# Ověření převzetí a pipeline

2. 11. 2026. Aplikační zdroj f04dba7c2147bf1041482e6c2e872325c5a4633e se nemění.

- V tomto prostředí: původních 14 Python testů prošlo; s 20 novými release testy
  prošlo všech 34. Kontrolováno odmítnutí cizího/mizejícího podpisu, chyb nástroje,
  debug/test manifest flags, package/verzí, změněných assets/manifestu/checksumů,
  chybějících reportů/secrets, nechráněného Environment a neplatné zálohy/JSON.
- Bash `-n`, Python compile a YAML parse prošly.
- Nové podpisové klíče se při testech negenerovaly. Testy parserů nenahrazují
  kryptografické ověření skutečného nově podepsaného APK.
- Zdrojové CI běhy 41337560798 a 41337556994 jsou success. Log prvního potvrzuje
  debug build, JUnit/Robolectric task, sestavení instrumentačního APK, debug lint
  a 14 Python testů. Lokální historické tvrzení 55 úspěšných/1 skipped nebylo
  z tohoto textového logu nezávisle spočítáno. Bez soukromých fixtures mohou být
  další dva testy přeskočeny.
- Nový lokální Android build byl blokován stažením Gradlu/sítí prostředí.
  Nový úspěšný unsigned CI běh je uveden níže. Signed release build bude až po
  vlastnických krocích.
- Fyzický Samsung, skutečný import přes odinstalaci, živý GNSS/Drive a Gallery
  nebyly testovány. Originální soukromé JPEG/export fixtures zde nebyly dostupné.

Při vydání tento soubor není kopírován do assetu. Pipeline vygeneruje vlastní
TEST_REPORT.md z JUnit XML, debug/release lint XML a skutečných Python výsledků
konkrétního build běhu, s přesným zdrojovým a pipeline commitem a ID Actions run.

## Nově spuštěné CI

Běh [41453567055](https://github.com/rznratn-sys/fortgps-field/actions/runs/41453567055)
byl dokončen úspěšně: debug + instrumentační + unsigned release APK, 56 JUnit
celkem (53 úspěšných, 3 přeskočené, 0 chyb/selhání), Python 14 + 19 úspěšných.
Debug/release lint: každý 0 chyb, 29 varování. Oproti historickému lokálnímu
55/1 zde chybějí dvě soukromé fixtures. Rozpis lint ID a skipped testů je nově
součástí build-evidence.json. Signing/publikace/public readback byly skipped.

Doplněný test sestavení celého alpha4 delivery bundle používá explicitně mock
kryptografického ověření a veřejné testovací bajty. Ověřuje generovaný manifest,
verzi v test reportu, certifikáty a přesnou šestici assetů. Nevytváří keystore
ani skutečný instalační APK. Reálný apksigner zůstává povinným gate workflow.

Rozpis 29 lint warnings v každé variantě: GradleDependency 14, UseKtx 10,
ApplySharedPref/OldTargetApi/DataExtractionRules/KaptUsageInsteadOfKsp/UsableSpace
po jednom. Nový běh 41455289268 úspěšně ověřil také rozšířenou evidenci a předání
ID unsigned artefaktu. Kryptografické signing/publication kroky zůstaly skipped.
