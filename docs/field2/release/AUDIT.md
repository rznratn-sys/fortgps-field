# Převzetí alpha3 a alternativa Pi-local — 2. 11. 2026

Zdroj zůstává f04dba7c2147bf1041482e6c2e872325c5a4633e, package cz.fortgps.field,
2.0.0-alpha3 / versionCode 20002, Room v2. GNSS, XMP/JPEG, nepodporované Samsung
SEF, offline a Drive implementace se nemění. Původní alpha3 delivery změnila proti
f04dba7 pouze workflow. Main je samostatný web; Android se checkoutuje zvlášť.

Na přání vlastníka se předchozí cloud signing návrh nahrazuje lokálním podpisem
na jeho Raspberry Pi OS ARM64. Odstraněn signing job, signing secrets a nástroj
pro upload keystore. Zůstává vytvoření/obnova klíče výhradně na vlastním ARM64
zařízení po budoucím souhlasu. Pi není runner, žádná změna jeho konfigurace/Věc.

Build/test/lint/alignment/Android metadata běží na GitHubu bez klíče. Handoff váže
APK na source/pipeline/run/hash a úplný inventář ZIP. Pi používá jen připnutý
Java apksigner.jar, odmítá jiné vstupy/nástroje a ověřuje podpis i každý původní
ZIP soubor. GitHub později ověří veřejný draft proti původnímu unsigned artifactu.
Publikace má samostatný explicitní enable flag, main/Environment review, schválený
pipeline SHA/certifikát a backup timestamp. Testovací klíč se odmítá pro publikaci.
Existující release/tag/assets se nenahrazují. PR #2 a #3 zůstávají nesloučené.

Nyní je autorizována pouze příprava a testování s dočasným klíčem. Není vytvořen
produkční klíč, draft/release, nepřenášejí se uživatelská data a neprobíhá migrace.
Původní klíč vlastník nemá; cloud nemá připojený signing vault. Metadata ostatních
GitHub Secrets/ochran integrace plně nezpřístupňuje; ochrany musí před případným
budoucím vydáním nastavit vlastník. Žádné produkční tajné hodnoty zde nejsou potřeba.

Historické f04dba7 CI i nové předchozí CI prošly (53 JUnit passed/3 skipped,
14 Python, oba linty 0 errors/29 warnings). Dosavadních 55/1 v dokumentaci bylo
s dvěma dalšími soukromými fixtures. Nová release sada má 26 testů a všech 40 Python testů prošlo lokálně i v CI.
Real crypto testy na x86_64 i aarch64 prošly (po 10 kontrolách) s dočasnou
testovací identitou, včetně skutečného tamper/password/certificate odmítnutí,
opakovaného podpisu, testovací obnovy a úklidu. Finální main běh 41464270883
zopakoval build a obě architektury. Výsledky/odkazy: TEST_REPORT.md.

Klíč musí zůstat na Pi a v nezávislých šifrovaných zálohách. Dokud vlastník
neprovede obnovu/CSR test, není produkční obnovitelnost prokázaná. JSON ze staré
alpha2 není úplná DB/outbox/JPEG journal záloha; budoucí migrace má STOP podmínky
v INSTALL.md. Nynější práce ji neprovádí ani neslibuje bezpečnost reálného importu.

Přesné oddělené umístění JDK/nástrojů/klíče, žádné sudo/apt/systemd/profily,
budoucí souhlasy, postup draft/public readback a alpha4: OWNER_RUNBOOK.md.

Připnutý Java nástroj Build Tools 35.0.0: SHA-256
00ef9948f843fe395d2440ae3ef41405b8040a6d5d46493bd1902ac0ee6deae7.
Otisk pochází z SDK instalace GitHub runneru; nemění se podle vstupního APK.
Publikace musí být výslovně povolená i uvnitř validačního skriptu, nikoli pouze
YAML podmínkou. Draft/public tag se kontroluje proti přesnému source commitu.
Závěrečný dotaz na Releases stále vrátil pouze v2.0.0-alpha2, žádnou alpha3 ani draft.
