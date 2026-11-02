# JDK 21 a obnova podpisové identity — 2. 11. 2026

Finální kód main: `1a492bef0c0f766f8b6ad9a03afaa844928162a9`;
delivery: `cd84b9843db6bc6fb56acd5a0a136a53655d41f0`.
Následující revize mění pouze dokumentaci. Zdroj aplikace je beze změn
`f04dba7c2147bf1041482e6c2e872325c5a4633e` (cz.fortgps.field / alpha3 / 20002).

## Provedené cloudové testy

[Finální CI main 41505335151](https://github.com/rznratn-sys/fortgps-field/actions/runs/41505335151)
a [delivery 41505354278](https://github.com/rznratn-sys/fortgps-field/actions/runs/41505354278)
jsou success. Matice: x86_64/aarch64 × JDK 17/21, Python **3.13.5**.
ARM64 JDK 21 report přímo uvádí:

- java.version i keytool.java.version **21.0.13**.
- java.runtime.version i keytool runtime **21.0.13+9-LTS**, vendor Eclipse Adoptium.
- os.arch / architecture **aarch64**, RSA **4096**.
- private_test_files_removed **true**, physical_raspberry_pi_tested **false**.

Každá ze čtyř kombinací prošla 31 release/recovery Python testy a 10 skutečnými
kryptografickými kontrolami bez mocku: podpis/verify skutečného unsigned alpha3
APK, opakovaná identita, zachování ZIP payloadu/zarovnání, odmítnutí existujícího
výstupu, špatného hesla/certifikátu, testovacího bundle a subjectu, skutečně
pozměněného APK a použitelnost obnovené privátní testovací kopie přes CSR.

Nové testy navíc skutečně ověřují vnější OpenPGP AES-256 šifrování/obnovu,
odmítnutí poškození, chybného hesla a přepisu, oba testovací private-key restores,
chybějící readiness a zákaz produkčního odemknutí z cloud/lab rehearsal.
Použity pouze dočasné RSA 4096 TEST ONLY klíče s platností dva dny; žádný produkční
klíč. Keys, hesla a podepsané testovací APK nejsou artifacts. Přežívají pouze
veřejný unsigned handoff, veřejný JAR a veřejné crypto-report.json.

Build aplikace zůstává na JDK 17: debug APK, instrumentační APK a zarovnaný unsigned
release APK prošly. JUnit XML: **56 celkem, 53 passed, 3 skipped**, 0 failures/errors.
Skip jsou dva soukromé export/JPEG fixtures a sada sedmi fotografií.
Python zdroj **14** + release **31** = **45 úspěšných testů**; lokální cloudová
sada také prošla. Lint debug/release: každý **0 errors, 29 warnings**.
Bash syntax, Python compile a YAML prošly.

Zdejší samostatný cloudový Debian: OpenJDK **21.0.12.1**, Python **3.12.14**,
GPG **2.4.7**. I zde prošlo skutečné RSA 4096/PKCS12/CSR a šifrovaná obnova.
GPG používá vlastní temp homedir, explicitní S2K a --no-autostart; nedotýká se
existujícího agenta, keyringu nebo konfigurace jiného projektu.

## Skutečný Pi — oznámeno vlastníkem, nikoli námi vzdáleně vykonáno

vec-pi5/operator: Pi 5/8 GB, Debian 13.7 aarch64, Python 3.13.5, Git 2.47.3,
OpenJDK 21.0.13, při první kontrole přibližně 41 GB volného místa.
Checkout 73719fc byl čistý, owner_key --help fungovalo, Java/keytool a dočasný
RSA 4096 PKCS12 test prošly a testovací klíč byl odstraněn. Připnutý JAR
SHA-256 00ef9948f843fe395d2440ae3ef41405b8040a6d5d46493bd1902ac0ee6deae7
byl extrahován z oficiálního build-tools_r35_linux.zip a version vrátilo 0.9.
To dokládá start JAR/Java primitiva, ne celý podpisový workflow na tom zařízení.

## Co musí být dokončeno na fyzickém Pi

1. pi_preflight se skutečným unsigned vstupem, jeho nezávisle ověřeným SHA/run/pipeline,
   stávajícím JDK 21 a skutečným keytool. Záznam musí uvést tento Pi a úspěšný úklid.
2. Správné již připojené USB, jeho UUID a fyzická nezávislost na Pi/Věc; GPG musí být dostupné.
3. Testovací šifrovaný archiv → USB restore/CSR → jiné úložiště → skutečné stažení
   odtamtud zpět → druhý restore/CSR, s hesly obnovitelnými odděleně od zařízení.
4. Až pak vznikne identity-readiness.json. Cloud/lab záznam má production_ready=false.
   Žádný test nepotvrdil konkrétní USB ani nezávislost účtu/média vlastníka.

Produkční create je nyní zablokováno bez čerstvé místní readiness. Konečný produkční
backup receipt i signer vyžadují dvě obnovené kopie. To nenahrazuje fyzickou kontrolu
médií a obnovitelnosti správce hesel, kterou provádí vlastník.

**Nic produkčního nevzniklo, nebyl vytvořen draft/release, nic nebylo publikováno ani
sloučeno.** Publish/readback joby byly správně skipped. Pi/Věc nebyly vzdáleně měněny.
Samsung, migrace uživatelských dat a živé GNSS/Drive/Gallery nebyly testovány.
JDK 21 je pro nástroje prokázaně podporovaný; na Pi není důvod instalovat další JDK 17.
Konkrétní Debian/Pi plný test ovšem teprve vykoná vlastník dle PI_JDK21_AND_BACKUPS.md.
