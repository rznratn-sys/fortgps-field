# Ověření převzetí a pipeline

2. 11. 2026. Aplikační zdroj f04dba7c2147bf1041482e6c2e872325c5a4633e se nemění.

- V tomto prostředí: původních 14 Python testů prošlo; s 19 novými release testy
  prošlo všech 33. Kontrolováno odmítnutí cizího/mizejícího podpisu, chyb nástroje,
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
  Nový unsigned CI běh bude mít samostatné výsledky; nelze jej prohlásit za
  úspěšný před dokončením. Signed release build bude až po vlastnických krocích.
- Fyzický Samsung, skutečný import přes odinstalaci, živý GNSS/Drive a Gallery
  nebyly testovány. Originální soukromé JPEG/export fixtures zde nebyly dostupné.

Při vydání tento soubor není kopírován do assetu. Pipeline vygeneruje vlastní
TEST_REPORT.md z JUnit XML, debug/release lint XML a skutečných Python výsledků
konkrétního build běhu, s přesným zdrojovým a pipeline commitem a ID Actions run.
