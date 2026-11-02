# Vlastnický postup pro dlouhodobé vydávání

Žádný nový klíč ani heslo dosud nevznikly. Původní klíč vlastník nemá (potvrzeno
2. 11. 2026). Tento postup vyžaduje jeho výslovný souhlas před vytvořením/nahráním
nové identity. Prováděj jej na vlastním důvěryhodném počítači, nikoli Codex Cloud,
GitHub runneru nebo ve sdíleném terminálu. Nikam nevkládej keystore ani hesla do
chatu. Base64 je jen kódování soukromého klíče, nikoli šifrování.

## 1. Kontrola a instalace pipeline

Zkontroluj PR, zejména workflow, `scripts/release/plan.json`, sign.sh a upload.
Kontrolní soubory pipeline musí být na výchozí větvi `main`; zdroj aplikace se
vždy checkoutuje zvlášť podle plného commitu v plan.json. Nepřepisuj webovou
aplikaci na main celým obsahem vývojové větve. PR pro main přidává pouze release
nástroje, workflow, jeho testy a tyto návody. Druhý PR do delivery větve vypne
původní automatické podepisování při push.

Po merge spusť nejdřív workflow s `publish=false`. Každý push/PR testuje bez
signing secrets. `publish=true` je povolen pouze z main a pouze pro přesný
`FIELD_RELEASE_PIPELINE_SHA`. Nastav tento otisk na **výsledný main commit po
merge**, nikoli na původní SHA PR. Každá úprava pipeline vyžaduje nové schválení.

Repozitář musí mít chráněnou main: povinná review změn workflow/nástrojů,
omezený přímý push, žádné force push, žádné obcházení ochrany administrátory.
Přidej odpovědného vlastníka pro workflow a nástroje do CODEOWNERS podle skutečné
správy projektu. Šifrování Secrets nechrání klíč před škodlivým schváleným workflow.

V Settings → Environments vytvoř:

- `field-release`: selected deployment branches/tags = **jen branch main**,
  required reviewers s nezávislým důvěryhodným schvalovatelem a prevent self review.
  Neumožňuj admin bypass. Jen zde budou čtyři podpisové secrets.
- `field-publication`: jen branch main, required review před publikací.
  Toto prostředí neobsahuje keystore ani hesla.

Pokud plán GitHubu nebo jednočlenná správa nezávislou review neumožňuje, tento
přísný postup se zastaví. Neodstraňuj kontroly; zajisti důvěryhodného reviewera
nebo samostatně navrhni a schval lokální podepisování s oddělenou publikací.

Před nahráním nového klíče prohlédni **názvy/metadatové seznamy**, nikoli hodnoty:

```sh
gh secret list --repo rznratn-sys/fortgps-field
gh secret list --repo rznratn-sys/fortgps-field --env field-release
```

V osobním repozitáři nejsou organizační secrets. Přezkoumej i další existující
Environments v Settings a vlastní správce hesel/zálohy. Actions Secrets nelze
zpětně stáhnout jako soukromý keystore. Klíč nesmí být repository secret, protože
by byl dostupný i jiným workflow/větvím. Případné staré repository signing secrets
odstraň až po vlastnické kontrole, bez vypisování hodnot. Tento audit je nesmazal.

## 2. Vytvoření identity až po souhlasu vlastníka

JDK 17 (`keytool`), Python 3.11+ a autentizovaný `gh` CLI na vlastním počítači.
Stáhni a zkontroluj nástroje ze schváleného commitu. Vypni shell tracing a záznam
terminálu; neukládej hesla do historie. Použij dlouhé unikátní heslo ve správci
hesel. Nástroj nepřijímá heslo jako argument a používá PKCS12 s jediným heslem.

```sh
python3 scripts/release/owner_key.py create \
  --keystore /vlastni/soukrome/fortgps-field-release.p12
```

Nástroj se ještě interaktivně zeptá na CREATE a heslo. RSA 4096, platnost 10000
dní, alias `field-release`. Nezaměňuj debug klíč. Nový veřejný SHA-256 si ulož
nezávisle; SHA-1 pro OAuth získáš lokálně:

```sh
keytool -list -v -keystore /vlastni/soukrome/fortgps-field-release.p12 -alias field-release
```

Soukromý soubor není chráněn tím, že má příponu p12: chraň přístup k počítači,
šifruj disk a zálohy. Heslo neukládej vedle nezašifrovaného keystore.

## 3. Nezávislé zálohy a skutečný test obnovy

Uchovej primární keystore, šifrovanou kopii na odpojeném médiu a druhou nezávislou
šifrovanou kopii na jiném místě/účtu. Heslo a postup obnovy ulož do správce hesel
s vlastním nezávislým recovery postupem. GitHub Secrets nejsou záloha klíče.

Z odpojeného média **obnov** kopii do nové privátní složky. Ověř obnovu hesla
ze správce, nikoli jen z paměti. Následující příkaz porovná soubory a certifikát
a vytvoří CSR z obou private-key entries, čímž ověří přístup k soukromému klíči:

```sh
python3 scripts/release/owner_key.py verify-backup \
  --keystore /vlastni/soukrome/fortgps-field-release.p12 \
  --restored /obnoveno/fortgps-field-release.p12 \
  --receipt /vlastni/soukrome/fortgps-recovery.json
```

Potvrdíš BACKUP VERIFIED teprve po skutečné kontrole nezávislosti zálohy a hesla.
Nástroj rozpozná shodný inode a jiné bajty; fyzickou nezávislost média nebo
obnovitelnost účtu musí ověřit vlastník. Receipt obsahuje otisky/čas/alias, nikoli
klíč či heslo; uchovej jej soukromě. Nezašifrovanou obnovenou pracovní kopii po
kontrole bezpečně odstraň podle vlastností svého média. Hlavní zálohy ponech.

## 4. Předání do chráněného Environment

Až po souhlasu vlastníka s nahráním, s platným receipt a po konfiguraci review:

```sh
python3 scripts/release/owner_key.py upload \
  --keystore /vlastni/soukrome/fortgps-field-release.p12 \
  --receipt /vlastni/soukrome/fortgps-recovery.json \
  --pipeline-sha <40-hex-vysledny-main-commit>
```

Nástroj kontroluje ochranu `field-release` (jen main a nezávislý reviewer),
matching receipt a certifikát. Vyžádá samostatné potvrzení UPLOAD. Tajné hodnoty
předává do `gh secret set` přes stdin, nikdy přes argumenty nebo logy.
Nahraje pouze Environment Secrets `FIELD_RELEASE_KEYSTORE_BASE64`,
`FIELD_STORE_PASSWORD`, `FIELD_KEY_PASSWORD`, `FIELD_KEY_ALIAS` a veřejné proměnné:
`FIELD_RELEASE_CERT_SHA256`, `FIELD_KEY_BACKUP_CERT_SHA256`,
`FIELD_KEY_BACKUP_VERIFIED_AT`, `FIELD_RELEASE_PIPELINE_SHA`.
Veřejný schválený SHA-256 zároveň nastaví jako repository variable pro publikaci
a nezávislý readback. Nástroj nespouští vydání. Po částečném selhání naprav
konfiguraci a zopakuj celý upload; neupravuj kontroly, aby prošly.

## 5. Vydání alpha3

Spusť `Field release` na main s `publish=true`. Review prostředí ověřuje zdroj,
výsledný pipeline commit, backup attestation a certifikát. Build/test/lint běží
bez signing secrets. Podpisový job na novém runneru nikdy nespouští Gradle ani
aplikační kód. Dekódovaný klíč je v privátním RUNNER_TEMP, trap jej odstraní.
Žádné cachování signing jobu, dump prostředí ani upload pracovního adresáře.

Publikace nejprve odmítne existující release **i tag**. Vytvoří draft, stáhne
všech šest assetů a ověří bajty, kontrolní součty, balíček/verzi a apksigner.
Teprve pak zveřejní prerelease. Další runner stáhne veřejné soubory bez tokenu
a ověří je znovu. Artefakty se mezi joby předávají přes výstupní ID, včetně opakování pouze
neúspěšného signing jobu; nikdy přes volné hledání v jiném běhu.
Při selhání ponech draft/release k prošetření; pipeline jej
nikdy automaticky nepřepisuje ani nemaže. Oprava/nahrazení assetů vyžaduje nové
konkrétní zdůvodnění a souhlas vlastníka. Do té doby vydání nepovažuj za ověřené.

Předinstalační migrace je v INSTALL.md. Nenahlašuj fyzické Samsung ověření bez
skutečného provedení a evidence.

## 6. Obnova a alpha4

Při ztrátě pracovního počítače obnov stejný p12 a heslo z nezávislých záloh,
zopakuj certifikát/CSR test a porovnej s uloženým veřejným otiskem i alpha3
manifestem. Obnov Secrets stejným upload nástrojem. Negeneruj náhradní klíč jen
proto, že je počítač nový. Klíč nelze obnovit ze samotného certifikátu ani APK.

Pro alpha4 použij **stejný** keystore/alias/certifikát. V samostatně schváleném
zdrojovém commitu změň versionName a zvyš versionCode (např. 20003, pokud dosud
nebyl použit). Uprav plan.json (commit, verze, tag, APK jméno), release notes,
instalační pokyny a očekávání testů identity. Revizi pipeline schval reviewem,
aktualizuj FIELD_RELEASE_PIPELINE_SHA a spusť nejdřív publish=false. Až pak
publish=true. Nový klíč se pro alpha4 nevytváří. Kontrola parseru alpha4 se stejným
certifikátem je automaticky testovaná; skutečná instalace následné verze na
telefonu dosud provedena nebyla.

Opakovatelný postup neznamená zaručenou bitovou shodu buildů. Runner/SDK a JDK
mohou dostávat aktualizace; actions jsou připnuté plnými commity a Gradle ZIP
má ve zdroji SHA-256. Aktualizace závislostí/actions dělej přes review.
