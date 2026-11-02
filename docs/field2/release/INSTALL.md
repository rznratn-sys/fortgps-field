# Alpha2 → alpha3: změna podpisu a ochrana dat

Tento návod nahrazuje předpoklad shodného certifikátu ve starším
`docs/field2/alpha3/INSTALL.md`. Při přípravě 2. 11. 2026 alpha3 nebyla vydaná. Nový otisk bude určen
vlastníkem a uveden ve skutečném release-manifest.json, nikoli odhadnutý předem.

Balíček `cz.fortgps.field`, alpha3 `2.0.0-alpha3`, versionCode `20002`.
Starý alpha2 certifikát SHA-1:
`B7:C8:49:00:77:67:0B:EC:95:94:BF:4C:F6:6A:B2:09:A1:43:1D:8D`.
Soukromý klíč podle vlastníka není dostupný. S novým certifikátem nelze provést
běžnou aktualizaci. Obnovitelnou rotaci bez původního klíče zde nemáme.

## Před jakoukoli odinstalací

1. Zastav nové záznamy, v nastavení Drivu zvol „Pozastavit automatické odesílání“ a ulož počet relací,
   jejich UUID/revize, otevřenou relaci, XMP stavy a stav přenosové fronty.
   Pokud je XMP `writing`, `permission_required` nebo rozpracované obnovení JPEG,
   **neodinstalovávej**: nejdřív dokonči nebo obnov původní JPEG a nezávisle jej
   zkontroluj. Samotný export session payloadu tento journal neobsahuje.
2. Exportuj JSON přes aplikaci na místo mimo její privátní úložiště. Načti jej
   na počítači, ulož dvě nezávislé kopie a SHA-256. Originální fotografie včetně
   metadat zálohuj samostatně, bez konverze přes sdílecí aplikace.
3. Na počítači s Python 3.11+ a `jsonschema==4.25.1` spusť proti schématu
   ze zdrojového commitu alpha3:

   ```sh
   git fetch origin f04dba7c2147bf1041482e6c2e872325c5a4633e
   git show f04dba7c2147bf1041482e6c2e872325c5a4633e:schemas/field-session-2.0.schema.json > /bezpecna/cesta/field-session-schema.json
   python3 scripts/release/check_backup.py /bezpecna/cesta/alpha2.json \
     --schema /bezpecna/cesta/field-session-schema.json
   ```

   Výstup obsahuje počty a otisk, nikoli souřadnice. Srovnej počty s telefonem,
   ručně otevři několik historických relací a ověř UUID, revize, měření, časy a
   XMP. Nástroj kontroluje schéma, UUID, cizí vazby, duplicity, více otevřených
   relací a limit importu 20 MiB. Nekontroluje celou Kotlin doménovou validaci
   ani skutečnou obnovu telefonu. Nesoulad znamená STOP, nikoli mazání dat.
4. Posuď ztráty uvedené níže. Pokud potřebuješ kompletní outbox nebo journal,
   JSON nestačí a pokračování není bezpečně doloženo. Ponech alpha2 nainstalovanou.
5. Teprve s ověřenými kopiemi mimo aplikaci a přijatými omezeními můžeš
   odinstalovat alpha2. Android odstraní její soukromou DB a nastavení.

## Co JSON přenese a co nepřenese

Exportuje se aktuální payload každé relace: UUID, aktuální revize, měření,
fotoasociace a uložený `xmp_result`, pokud existuje. **Neexportuje se**:

- Celá DB, předchozí revize payloadů a historie outboxu; jeho `remoteId`,
  `folderId`, potvrzení a rozpracované přenosy.
- Tabulka `xmp_operations`, privátní zálohy JPEG, cesty k obnově, MediaStore
  oprávnění a rozpracované operace. Import je znovu nevytvoří.
- JPEG soubory, OAuth přihlášení, preference Drivu a systémová oprávnění.

Stav `verified` v importovaném JSON je historický údaj, nikoli nová kontrola
skutečného JPEG. Nepodporované `Camera_Scene_Info`/`Gallery_DC_Data` zůstávají
odmítnuté. Nemaž SEF ani neupravuj EXIF kvůli obejití ochrany.

## Instalace a kontrola importu

1. Použij až skutečně publikovaný prerelease `v2.0.0-alpha3`. Stáhni všech šest
   assetů. `sha256sum -c SHA256SUMS` ověřuje APK, manifest i všechny tři návody.
   Porovnej certifikát v manifestu s nezávisle zaznamenaným otiskem vlastníka;
   samotný checksum vedle APK neprokazuje důvěryhodný původ.
2. Ověř APK Android Build Tools 35:
   `apksigner verify --verbose --print-certs FortGPS_Field_2.0.0-alpha3.apk`.
   SHA-256 musí odpovídat schválenému certifikátu. `aapt dump badging` musí
   potvrdit balíček/verzi výše, nikoli debug/test sestavení.
3. Instaluj alpha3, zůstaň offline a nepřipojuj Drive. Importuj ověřený JSON.
   Import zachová UUID a aktuální revize, ale založí novou pending položku
   outboxu pro každou nově importovanou relaci.
4. Exportuj nový JSON a na počítači porovnej:

   ```sh
   python3 scripts/release/check_backup.py /bezpecna/cesta/alpha2.json \
     --schema /bezpecna/cesta/field-session-schema.json \
     --compare /bezpecna/cesta/alpha3-po-importu.json
   ```

   Porovnává celé payloady podle UUID; toleruje pouze nová nullable pole alpha3.
   Ověř v UI UUID, vazby měření/fotek, revize, otevřenou relaci a XMP stavy.
   Při rozdílu ponech sync vypnutý, uchovej oba exporty a nic nepřepisuj na Drivu.
5. Obnov oprávnění k poloze/fotografiím samostatně. Proveď terénní testy podle
   zdrojového `docs/field2/alpha3/INSTALL.md` (část terénního testu).

## Drive: kontrola před opětovnou synchronizací

OAuth Android klient musí odpovídat `cz.fortgps.field` a **novému SHA-1**
certifikátu. Ten vlastník zjistí lokálně `keytool -list -v` a v Google Cloud
Console nastaví správný Android OAuth klient. Hesla/keystore se Googlu neposílají.
Offline záznamy na OAuth nezávisí.

Před připojením porovnej cílovou složku a účet s alpha2. Pro každé importované
UUID/revizi zjisti, zda již existuje `fortgps-field-<UUID>-r<revize>.json`, jeho
Drive ID a obsah. Uchovej soupis vzdálených ID; nic automaticky nemaž.
Klient používá `drive.file`: po změně OAuth identity nemusí vidět dříve vytvořené
soubory, i když je vlastník vidí ve webovém Drivu. Neviditelnost proto **není**
důkaz nepřítomnosti. Připojení v UI může sync ihned zapnout; neprováděj je před
kontrolou dostupnosti starých souborů pro nový OAuth klient.

Při přístupu ke stejným souborům kód hledá UUID/revizi v původní složce,
více výsledků odmítne a existující obsah ověřuje bajtově. Nová serializace
nullable polí může vyvolat konflikt, přestože relace významově odpovídá.
Při jiném účtu/složce nebo nedostatečné viditelnosti může vzniknout duplicita.
Pokud nelze oprávnění a shodu bezpečně ověřit, ponech produkční sync vypnutý.
Případné vyzkoušení prováděj s postradatelnými daty v oddělené testovací složce.

Migrace přes změnu podpisu ani živý upload nebyly ověřeny na fyzickém Samsungu.
Úspěšný JSON preflight tuto zkoušku nenahrazuje.
