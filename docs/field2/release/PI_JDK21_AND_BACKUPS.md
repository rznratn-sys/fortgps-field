# vec-pi5: JDK 21, podpisový test a ověřená obnova před vytvořením identity

**Nyní žádný produkční klíč, produkční podpis, publikace ani merge PR.** Tento návod
nahrazuje požadavek instalovat na Pi další JDK 17. Aplikační build na GitHubu zůstává
na JDK 17; lokální signing nástroje používají podporované JDK 17 nebo 21.

## Co je již doloženo a co zbývá

Vlastník oznámil: vec-pi5, operator, Pi 5/8 GB, Debian 13.7 aarch64, Python 3.13.5,
Git 2.47.3, OpenJDK 21.0.13, přibližně 41 GB volného místa. Checkout 73719fc byl čistý;
owner_key.py --help funguje. Vytvoření/odstranění dočasného RSA 4096 PKCS12 a spuštění
připnutého apksigner.jar (`version` = 0.9) proběhly podle sdělení vlastníka skutečně na Pi.
Nejde ještě o plný podpis APK, obnovu z USB nebo naše vzdálené ověření stroje.

Cloudové zkoušky a jejich přesné verze/výsledky jsou v TEST_REPORT.md. Cloudové aarch64
není fyzický Pi ani jeho konkrétní Debian OpenJDK build. JAR není vázaný na JDK 17;
používané keytool/apksigner/JVM volby jsou podporované i v JDK 21. Pokud dále uvedená
zkouška na vašem Pi projde, použijeme stávající JDK 21; další Javu neinstalujeme.

## 1. Pouze kontrola stávajícího prostředí

V Termuxu použij již ověřené SSH spojení, kontrolu známého host key a interaktivní TTY.
Neignoruj změněný SSH otisk. Nesdílej hesla, p12 ani SSH privátní klíče; při zadávání
hesel nevytvářej záznam terminálu a nevkládej je jako shell argumenty/proměnné/příkazy.
Python používá getpass a odmítne neinteraktivní terminál. Cesty a veřejné SHA nejsou hesla.

Na Pi, nikoli v lokálním Termux shellu:

```sh
hostname
whoami
uname -m
python3 --version
java -version
tty
df -h /home/operator/fortgps-release
free -h
command -v gpg
findmnt --version
```

Při chybějícím gpg/findmnt zastav; nic automaticky neinstaluj, neměň služby/profily/
update-alternatives ani konfiguraci Věc. Používá se pouze nová složka FortGPS. Je-li
Pi vytížené kamerami/Věc, odlož test. Limity heapu JVM nejsou garancí celkové zátěže.

## 2. Získání nových schválených nástrojů a unsigned testovacího vstupu

73719fc je stará příprava bez nových kontrol. Neprováděj podle ní produkční create.
Až zkontrolujeme nový PR commit, aktualizuj pouze oddělený FortGPS checkout; PR se neslučuje:

```sh
cd /home/operator/fortgps-release/repo
git status --porcelain
# Musí být prázdné; jinak STOP, žádný reset/stash naslepo.
git fetch origin <CELE_SCHVALENE_SHA_NASTROJU>
git switch --detach <CELE_SCHVALENE_SHA_NASTROJU>
```

Ze schváleného úspěšného PR CI stáhni artifact `field-unsigned-<RUN_ID>-1.zip`.
Je to jen unsigned APK a veřejný handoff, nikoli klíč. Jeho upload do testu/stažení
z Androidu a SCP na Pi je povolené. `github_io.py` správně odmítá PR jako produkční
build; **neobcházej jeho main-only kontrolu**. Pro tento dočasný test použij PR artifact
stažený přes GitHub UI. Příklad přenosu z Termuxu (uprav pouze název veřejného ZIP):

```sh
scp /sdcard/Download/field-unsigned-<RUN_ID>-1.zip operator@vec-pi5:/home/operator/fortgps-release/work/
```

V nové složce na Pi rozbal jen očekávané soubory; nespouští se nic z artifactu:

```sh
python3 - <<'PY'
from pathlib import Path
import zipfile
src=Path('/home/operator/fortgps-release/work/field-unsigned-<RUN_ID>-1.zip')
out=Path('/home/operator/fortgps-release/work/pi-signing-test-input')
expected={'unsigned.apk','handoff.json','INSTALL.md','RELEASE_NOTES.md'}
with zipfile.ZipFile(src) as z:
    if len(z.infolist())!=4 or {i.filename for i in z.infolist()}!=expected:
        raise SystemExit('STOP: neočekávaný obsah ZIP')
    if any(i.file_size>128*1024*1024 for i in z.infolist()):
        raise SystemExit('STOP: příliš velký artifact')
    out.mkdir(mode=0o700)
    for name in sorted(expected):
        with (out/name).open('xb') as target, z.open(name) as source:
            import shutil
            shutil.copyfileobj(source,target)
PY
```

Z CI logu ověř source f04dba7c2147bf1041482e6c2e872325c5a4633e a opiš veřejné
`APPROVED UNSIGNED SHA-256`, celý pipeline SHA a run ID. Neber hash jen z téhož ZIP.

## 3. Jediný plný podpisový test na konkrétním Pi

Použije existující JDK 21 z PATH a již ověřený JAR. **Pouze dočasný RSA 4096 klíč**
s platností dva dny, zkouška podpisu/ověření a kontrola obnovy, pak odstranění.
Žádný produkční soubor se nečte ani nevytváří; žádná síť/publikace:

```sh
cd /home/operator/fortgps-release/repo
python3 scripts/release/pi_preflight.py \
  --input /home/operator/fortgps-release/work/pi-signing-test-input \
  --jar /home/operator/fortgps-release/tools/build-tools-35/apksigner.jar \
  --pipeline-sha <CELE_SHA_Z_CI> --build-run <RUN_ID> \
  --unsigned-sha256 <SHA256_Z_CI_LOGU> \
  --report /home/operator/fortgps-release/private/pi-preflight.json
```

Zpráva zachytí skutečný Java vendor/verzi, Python, aarch64 a model Pi z read-only
/proc/device-tree/model. Musí mít 10 passed kontrol a private_test_files_removed=true.
Pošleš-li výsledek, stačí veřejné verze/počty/otisky, ne privátní soubory. Chyba
znamená STOP a konkrétní diagnostiku, nikoli instalaci jiné Javy naslepo.

## 4. USB a druhé úložiště: nejprve zkouška s testovací identitou

Zjisti již připojenou vlastní USB flashku:

```sh
lsblk -o NAME,PATH,TRAN,RM,SIZE,FSTYPE,LABEL,UUID,MOUNTPOINTS,PKNAME
findmnt
```

Nevybírej disk nebo složku projektu Věc. Nic neformátuj ani nepřipojuj automaticky;
pokud USB nemá mountpoint, nejprve společně schválíme správné připojení podle skutečného
UUID. Dva adresáře/oddíly na téže SD/USB nejsou dvě nezávislé zálohy. Skript odmítne
Pi filesystem a známý společný fyzický disk; nezávislost vzdálené služby musí potvrdit vlastník.

Příklad: USB je již připojené jako `/media/operator/FG_BACKUP` (nahraď skutečným
ověřeným mountpointem). Připrav pouze TEST archiv. Heslo A pro testovací PKCS12
(16+ znaků) a jiné B pro vnější šifrování (20+ znaků) zadej skrytě a dočasně ulož
odděleně do správce hesel; budou potřeba při dokončení. **Nejde o budoucí produkční hesla.**

```sh
python3 scripts/release/recovery_readiness.py prepare \
  --preflight /home/operator/fortgps-release/private/pi-preflight.json \
  --usb-dir /media/operator/FG_BACKUP \
  --state /home/operator/fortgps-release/private/recovery-drill.json
```

Vznikne pouze `FortGPS-TEST-RECOVERY-<náhodné>.p12.gpg`. Dočasný plaintext klíč
se po první obnově/CSR odstraní. Vnější OpenPGP AES-256 používá iterovaný S2K SHA-512,
integritní kontrolu a samostatné heslo; pouhé heslo PKCS12 nenahrazuje tuto vrstvu.
GPG má vlastní dočasný homedir, nepoužívá konfiguraci/klíče/agent projektu Věc a žádný
agent nespouští. Ciphertext se fsync zapisuje; před odpojením USB proveď sync a
bezpečné odpojení vlastního ověřeného svazku. Po znovupřipojení ověříme stejný UUID.

Zkopíruj **pouze .gpg** na jiné fyzické médium nebo nezávislé úložiště/účet,
a stáhni jej odtamtud zpět na Pi např. jako:
`/home/operator/fortgps-release/work/retrieved-TEST.p12.gpg`.
Druhý adresář na USB, pouhý přesun souboru ani lokální kopie na Pi tento krok nenahrazují.
Odpojení/ztráta telefonu nesmí současně odstranit jedinou zálohu i jediný způsob obnovy hesla.

V nové SSH relaci obnov hesla ze správce/recovery, USB znovu připoj a dokonči:

```sh
python3 scripts/release/recovery_readiness.py finish \
  --state /home/operator/fortgps-release/private/recovery-drill.json \
  --second-copy /home/operator/fortgps-release/work/retrieved-TEST.p12.gpg \
  --readiness /home/operator/fortgps-release/private/identity-readiness.json
```

Nástroj ověří oba ciphertexty, integritu, shodu celého keystore a skutečný CSR přístup
k obnovenému soukromému klíči. Vlastník výslovně potvrzuje nezávislé uložení a návrat
kopie. Po úspěchu existuje readiness, **stále žádný produkční klíč**. Cloud/lab
rehearsal má production_ready=false a nelze jej použít jako povolení produkčního create.

## 5. Kontrolní seznam PŘED budoucím produkčním CREATE

- Samostatně udělený výslovný souhlas vlastníka; PR nejsou automaticky sloučené.
- Schválený čistý tooling checkout; aplikační source zůstává f04dba7.
- Plný signing preflight prošel na tomto Pi, Pythonu a Javě 21; žádný zásah do Věc.
- GPG a oba návraty testovacích šifrovaných kopií prošly; USB UUID/disk jsou ověřené.
- Druhé úložiště je fyzicky/účtem nezávislé, nikoli další oddíl/adresář stejného disku.
- Hesla/recovery jsou dostupná odděleně od zařízení a médií. Žádné heslo v chatu,
  shell historii, logu, vedle nezašifrovaného p12 nebo jen ve ztratitelném telefonu.
- Připravena nová unikátní produkční hesla A (PKCS12) a B (vnější šifrování),
  odlišná od testovacích. Doporučeny náhodné ASCII řetězce ze správce hesel.
- identity-readiness.json je úplný, z tohoto Pi/runtime a nejvýše 7 dní starý;
  testovací šifrované kopie zůstávají dostupné jako důkaz, dokud není vytvoření schváleno.

## 6. Pozdější produkční kroky — NYNÍ NESPOUŠTĚT

Přesné příkazy jsou připravené pro další samostatně schválenou fázi:

```sh
# TEPRVE PO SOUHLASU: jediná dlouhodobá identita na vlastním Pi.
python3 scripts/release/owner_key.py create \
  --readiness /home/operator/fortgps-release/private/identity-readiness.json \
  --keystore /home/operator/fortgps-release/private/fortgps-release.p12
```

Bez dokončené readiness se vytvoření odmítne ještě před generováním klíče.
Poté okamžitě vytvoř vnější šifrovanou produkční zálohu (nikoli kopii plaintextu):

```sh
# AŽ POTOM; heslo B se zadává skrytě, není argumentem.
python3 scripts/release/encrypted_backup.py encrypt \
  --source /home/operator/fortgps-release/private/fortgps-release.p12 \
  --output /media/operator/FG_BACKUP/FortGPS-PRODUCTION.p12.gpg
```

Zkopíruj ciphertext do druhého nezávislého úložiště a stáhni jej zpět jako
`work/retrieved-PRODUCTION.p12.gpg`. Bezpečně odpoj/znovu připoj USB a obnov **obě**
kopie do nové soukromé složky na Pi, ne do USB/Androidu/repozitáře:

```sh
RESTORE_DIR=$(mktemp -d /home/operator/fortgps-release/private/restore-XXXXXXXX)
python3 scripts/release/encrypted_backup.py restore \
  --source /media/operator/FG_BACKUP/FortGPS-PRODUCTION.p12.gpg --output "$RESTORE_DIR/usb.p12"
python3 scripts/release/encrypted_backup.py restore \
  --source /home/operator/fortgps-release/work/retrieved-PRODUCTION.p12.gpg --output "$RESTORE_DIR/second.p12"
python3 scripts/release/owner_key.py verify-backup \
  --keystore /home/operator/fortgps-release/private/fortgps-release.p12 \
  --restored "$RESTORE_DIR/usb.p12" --second-restored "$RESTORE_DIR/second.p12" \
  --receipt /home/operator/fortgps-release/private/fortgps-recovery.json
```

Final receipt vyžaduje dvě samostatně obnovené soukromé kopie. Při chybě zálohy
zachovej primární produkční klíč; **negeneruj nový a neodstraňuj jediný existující**.
Produkční signer odmítne receipt bez ověření obou kopií. Obnovené pracovní plaintext
kopie pak odstraň cíleně; rm na SD/SSD není garantované fyzické přepsání buněk.
Produkční primární p12 a šifrované zálohy se nemažou.

Veřejnou identitu eviduj mimo zálohy a porovnávej se všemi budoucími APK:

```sh
keytool -list -v -keystore /home/operator/fortgps-release/private/fortgps-release.p12 -alias field-release
keytool -exportcert -rfc -keystore /home/operator/fortgps-release/private/fortgps-release.p12 \
  -alias field-release -file /home/operator/fortgps-release/work/field-release-certificate.pem
```

Heslo A zadávej pouze do skrytého promptu. Certifikát/otisky SHA-256 a SHA-1 jsou
veřejné; p12, hesla a privátní recovery receipt nikoli. GitHub nadále dostane pouze
veřejné release soubory a otisky. Stejný produkční p12/alias se použije pro alpha3,
alpha4 a další vydání. Skutečné podepsání/vydání APK ani migrace nejsou součástí této fáze.
