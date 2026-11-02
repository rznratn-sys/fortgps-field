# Lokální podpis na Raspberry Pi OS ARM64

Aktuální konkrétní postup pro vec-pi5, JDK 21 a USB: **PI_JDK21_AND_BACKUPS.md**.
Produkční vytvoření nyní vyžaduje předem ověřenou identity-readiness.json.

Tento postup **nahrazuje předchozí návrh s klíčem v Actions Secrets**.
Soukromý produkční klíč smí být pouze na vlastním Pi a v nezávislých šifrovaných
zálohách. Příkaz pro upload keystore byl odstraněn. GitHub dostane pouze
podepsaný APK, veřejný certifikát/otisky a dokumentaci. Zatím jde pouze o přípravu:
žádné produkční klíče, změny Pi/Věc, migrace, sloučení PR nebo publikace.

## Rozdělení práce

1. GitHub Actions: přesný schválený commit aplikace, testy, debug/release lint,
   unsigned release, `zipalign -P 16`, kontrola package/verzí/debug flags.
2. GitHub přidá handoff.json s SHA-256 APK, úplným ZIP inventářem, zdrojem,
   pipeline commitem, run ID a výsledky testů. Samostatné CI zkusí skutečný podpis
   dočasným TEST ONLY klíčem na x86-64 a ARM64. Testovací APK ani klíč se neuploadují.
3. Pi: zkontroluje schválený build a hash, lokálně podepíše Java apksigner.jar,
   ověří podpis a shodu každého původního ZIP souboru. Žádný Gradle ani síť v signeru. JVM signing procesu má limit heapu 256 MiB
   a nejvýše dvě aktivní CPU; nejde o rezervaci zdrojů. Spouštěj jej při volné
   kapacitě. Nastavení projektu Věc se nemění.
4. Až po pozdějším samostatném souhlasu: šest veřejných souborů do GitHub draftu.
   Chráněné ruční workflow stáhne draft i původní unsigned artifact, nezávisle
   ověří podpis/obsah/metadata, zveřejní a znovu stáhne veřejné soubory.

Raspberry Pi nebude self-hosted runner. Žádné služby, otevřené porty, Docker,
změny apt/profilů/cronu/systemd ani zásahy do projektu Věc. Veškeré nové soubory
patří do nové oddělené složky; existující složku nepřepisuj.

## 1. Až budeš doma: nezapisující kontrola

Na Pi nejprve pouze `uname -m`, `cat /etc/os-release`, `python3 --version`,
`java -version` a `df -h`. Požadavek: Linux aarch64/arm64, Python 3.11+, lokální JDK 17 nebo 21.
Na vec-pi5 ponech stávající OpenJDK 21.0.13; další JDK 17 se nevyžaduje.
Konkrétní plný signing/recovery test na tomto Pi musí před produkčním create
projít podle PI_JDK21_AND_BACKUPS.md. Samotné `version` není celý test.
Neměň sdílený Java runtime projektu Věc. Pokud už vhodný JDK funguje, další neinstaluj. Jinak lze později po souhlasu stáhnout samostatný
**Linux ARM64 JDK 17 nebo 21** (např. Temurin), porovnej archiv s publikovaným SHA-256
výrobce a rozbal jej do vlastní nové složky. Totéž pro ARM64 gh CLI, pokud není
k dispozici. Žádné `sudo`, apt install, update-alternatives ani změny `.bashrc`.

Příklad rozložení (pouze nové soubory; při existenci nejdřív zastav a zkontroluj):

```sh
mkdir "$HOME/fortgps-release"
mkdir -m 700 "$HOME/fortgps-release/private"
mkdir "$HOME/fortgps-release/tools" "$HOME/fortgps-release/work"
git clone https://github.com/rznratn-sys/fortgps-field.git "$HOME/fortgps-release/repo"
```

Po schválení/merge PR checkoutuj přesný odsouhlasený main commit. Níže `<pipeline>`
znamená jeho celý 40hex SHA, ne pohyblivou větev. Vše prováděj z tohoto checkoutu.
Na stávajícím vec-pi5 používej funkční Javu 21 z PATH bez další instalace.
Pokud funguje PATH, celý prefix FORTGPS_JAVA_HOME vynech.
V ostatních případech lze použít `FORTGPS_JAVA_HOME=/cesta/k/soukromemu/jdk`;
platí pouze pro tento proces a nepřepíná systémovou Javu.

## 2. Přenosný podpisový nástroj

Linux Android SDK obsahuje nativní x86-64 aapt/zipalign: **na ARM je nespouštěj**.
Potřebujeme pouze `lib/apksigner.jar` z Build Tools **35.0.0**, který používá JVM.
Jeho schválený SHA-256 je připnutý v `scripts/release/tools-lock.json`.

Stáhni oficiální Linux Build Tools 35.0.0 archiv z Android SDK repository na
počítači/Pi (nebo jej přenes). Extrahuj jen ověřený JAR, nikoli celý SDK do systému:

```sh
python3 scripts/release/portable_tools.py \
  --sdk-archive /cesta/build-tools-linux-35.zip \
  --output "$HOME/fortgps-release/tools/build-tools-35"
FORTGPS_JAVA_HOME=/cesta/k-vlastnimu-jdk /cesta/k-vlastnimu-jdk/bin/java -jar \
  "$HOME/fortgps-release/tools/build-tools-35/apksigner.jar" version
```

Výstupní složka musí být nová. Nástroj nepouští SDK installery, nekonfiguruje
PATH a odmítne jiný JAR. Alternativně přenes samostatný `field-apksigner-*` CI
artifact a zkontroluj **stejný připnutý SHA-256**. Neber otisk jen ze stejného
neověřeného stažení. JAR je spustitelný kód s přístupem ke klíči: jeho kontrolu
nelze nahradit pouhou kontrolou APK.

## 3. Nový produkční klíč — až po budoucím výslovném souhlasu

Nyní se tento krok neprovádí. Na vlastním ARM64 Pi, v interaktivním terminálu:

```sh
FORTGPS_JAVA_HOME=/cesta/k-vlastnimu-jdk python3 scripts/release/owner_key.py create \
  --readiness "$HOME/fortgps-release/private/identity-readiness.json" \
  --keystore "$HOME/fortgps-release/private/fortgps-release.p12"
```

Nástroj vyžaduje CREATE, dlouhé heslo zadávané skrytě a nepřepisuje existující
soubor. PKCS12, RSA 4096, alias field-release, platnost 10000 dnů. Žádné heslo
v argumentech/historii/logu; nikdy neposílej p12 ani heslo do chatu nebo GitHubu.
Produkční vytvoření/signing je v CI zakázané; testy používají jiný explicitní režim.

Udělej šifrovanou zálohu na odpojeném médiu a druhou nezávislou šifrovanou zálohu
na jiném místě. Heslo/recovery ulož nezávisle do správce hesel, nikoli vedle p12.
Nešifrovaná pracovní kopie zůstává pouze na Pi. Šifruj i jeho disk/privátní úložiště
podle svého provozního návrhu — tento projekt nastavení Pi automaticky nemění.

Obnov z média kopii zpět do jiné privátní složky **na Pi** a ověř heslo z recovery:

```sh
FORTGPS_JAVA_HOME=/cesta/k-vlastnimu-jdk python3 scripts/release/owner_key.py verify-backup \
  --keystore "$HOME/fortgps-release/private/fortgps-release.p12" \
  --restored /privatni-obnovena-slozka/usb.p12 \
  --second-restored /privatni-obnovena-slozka/second.p12 \
  --receipt "$HOME/fortgps-release/private/fortgps-recovery.json"
```

Porovnají se bajty, veřejný certifikát a skutečný přístup k private-key entry přes
CSR. BACKUP VERIFIED potvrď až po fyzickém ověření nezávislosti médií a hesla.
Receipt zůstává soukromý na Pi. Ani receipt, hash souboru keystore, obnovovací
kopie nebo heslo se do release nepřidávají. Ochrana keystore musí být mode 600.

## 4. Unsigned sestavení a ověření přenosu

Po review/merge spusť workflow `Field unsigned build and Pi-local release` na main,
`operation=build`. Zdroj alpha3 musí zůstat f04dba7c2147bf1041482e6c2e872325c5a4633e.
Čekej na úspěšné build **i obě crypto-test větve**. Po dílčím rerun spusť nový
kompletní build; handoff musí odpovídat aktuálnímu run_attempt. Zkontroluj výsledky, source/pipeline
SHA a z logu „APPROVED UNSIGNED SHA-256“ nezávisle opiš `<unsigned-hash>`.

Pro gh používej případně novou privátní konfiguraci přes `GH_CONFIG_DIR`; neměň
autentizaci jiného projektu. Read-only účet/token pro download stačí. Stahování
probíhá bez jakékoli cesty ke klíči:

```sh
GH_CONFIG_DIR="$HOME/fortgps-release/private/gh" \
python3 scripts/release/github_io.py --run <run-id> --pipeline-sha <pipeline> \
  --unsigned-sha256 <unsigned-hash> \
  --output "$HOME/fortgps-release/work/alpha3-input"
```

Nástroj odmítne PR/feature/failed build nebo jiný pipeline commit. Vstup musí
obsahovat právě unsigned.apk, handoff.json, INSTALL.md, RELEASE_NOTES.md.
Zkontroluje skutečný SHA-256, unsigned stav, zarovnání, každou ZIP položku, návody,
plan a testové výsledky. Package/verze/debug flags byly nezávisle ověřeny aapt
při sestavení; na Pi se jejich binární manifest nesmí změnit ani o bajt.

## 5. Offline lokální podpis

Odpoj síť, pokud to provoz projektu Věc dovoluje; **neodpojuj ji automaticky**.
Samotný signer síť nepoužívá. Zachovej provoz Věc. Podepisování běží pouze jako
jednorázový proces s privátními soubory; výstupní složka musí být nová:

```sh
FORTGPS_JAVA_HOME=/cesta/k-vlastnimu-jdk python3 scripts/release/local_sign.py \
  --input "$HOME/fortgps-release/work/alpha3-input" \
  --output "$HOME/fortgps-release/work/alpha3-delivery" \
  --keystore "$HOME/fortgps-release/private/fortgps-release.p12" \
  --receipt "$HOME/fortgps-release/private/fortgps-recovery.json" \
  --jar "$HOME/fortgps-release/tools/build-tools-35/apksigner.jar" \
  --pipeline-sha <pipeline> --build-run <run-id> --unsigned-sha256 <unsigned-hash>
```

Potvrď SIGN 2.0.0-alpha3 a zadej heslo skrytě. Signer kontroluje zálohu/certifikát,
v1/v2/v3 podpis, správnou identitu a úplnou shodu původních ZIP entries;
smí přibýt jen tři pevně pojmenované podpisové soubory. Nezarovnává APK po podpisu.
Chyba nezpřístupní částečný delivery adresář. Veřejný výstup má přesně šest souborů:
APK, release-manifest.json, SHA256SUMS, INSTALL.md, RELEASE_NOTES.md, TEST_REPORT.md.
Žádný privátní soubor se nekopíruje. `--test-only` není produkční zkratka:
jeho výstupy jsou označené a staging/publikace je odmítnou; odmítá se také
certifikát s testovacím subjectem, i kdyby někdo přepsal příznak v JSON.

## 6. Pozdější publikace — nyní zakázaný krok

Zatím se nevytváří ani draft. Po samostatném souhlasu vlastníka nastav na GitHubu
pouze **veřejné** variables FIELD_RELEASE_CERT_SHA256, FIELD_KEY_BACKUP_VERIFIED_AT,
FIELD_RELEASE_PIPELINE_SHA. Nikdy signing secrets. Ponech FIELD_PUBLICATION_ENABLED
prázdné/false, dokud nechceš skutečně vydat.

Chraň main reviewem/omezeným pushem a Environment `field-publication`: pouze main,
required reviewer, prevent self review, žádný admin bypass. Žádné prostředí
field-release s privátním klíčem už není potřeba. Pokud předchozí nastavení někde
existuje, nyní je nemažeme; před budoucím vydáním je vlastník zkontroluje podle názvů,
nikoli zveřejněním hodnot. Přístup k těmto nastavením z připojené integrace není úplný.

Nejprve pouze validuj veřejný bundle lokálně (bez --create-draft = bez uploadu):

```sh
FORTGPS_JAVA_HOME=/cesta/k-vlastnimu-jdk python3 scripts/release/stage_bundle.py \
  --directory "$HOME/fortgps-release/work/alpha3-delivery" \
  --input "$HOME/fortgps-release/work/alpha3-input" \
  --jar "$HOME/fortgps-release/tools/build-tools-35/apksigner.jar" \
  --pipeline-sha <pipeline> --build-run <run-id> \
  --unsigned-sha256 <unsigned-hash> --cert-sha256 <verejny-certifikat-sha256>
```

Až s pozdějším souhlasem přidej --create-draft a potvrď STAGE v2.0.0-alpha3.
Transportuje se pouze šest vyjmenovaných veřejných souborů. Existující draft,
release nebo tag se nepřepisuje. GitHub oprávnění pro draft upload jsou oddělená
od signing hesla; tento nástroj nemá parametr pro keystore ani heslo.

Nakonec vlastník výslovně povolí FIELD_PUBLICATION_ENABLED=true a ruční operation=publish
s draft_release_id a build_run_id. Chráněné workflow nezávisle stáhne původní
unsigned artifact z úspěšného schváleného main buildu, ověří přesný obsah proti
podepsanému APK, skutečný certifikát/aapt metadata/zipalign/checksumy a shodu backup
attestation. Testovací identity se odmítnou. Teprve potom zveřejní již ověřený draft.
Další runner stáhne veřejné soubory bez release tokenu a znovu je ověří.
Selhání se řeší vyšetřením; žádné automatické mazání nebo nahrazování assets.

## Alpha4 a obnova

Použij stejný p12, alias, certifikát a ověřené nezávislé zálohy. Na novém počítači
nikdy negeneruj náhradní klíč jen kvůli obnově zařízení; obnov jej zpět na vlastní
Pi a ověř certifikát/CSR. GitHub nemá soukromou kopii klíče a neumí ji obnovit.

Pro alpha4 schval nový zdrojový commit, versionName, vyšší nepoužitý versionCode
(např. 20003), tag/APK v plan.json a aktuální release notes/instalační pokyny.
Znovu main build → ověřený download → tentýž Pi klíč → ověřený draft/publikace.
Každá změna pipeline vyžaduje nový FIELD_RELEASE_PIPELINE_SHA a nový unsigned build.
Reálný budoucí alpha4 upgrade telefonu není touto infrastrukturální zkouškou prokázán.

Primární reference: https://developer.android.com/tools/apksigner (zipalign před
podpisem, hesla přes env/stdin, v1-signer-name a verify),
https://developer.android.com/tools/zipalign,
https://github.com/actions/partner-runner-images (ubuntu-24.04-arm).
Lokální signing CI pokrývá JDK 17/21 a Python 3.13.5; aplikační build zůstává JDK 17.
ARM64 CI je skutečný ARM64 Linux/JVM, nikoli fyzické Raspberry Pi OS/Věc.
