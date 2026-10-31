# Samsung: debug alpha1 → release alpha2

Tento doplněk zpřesňuje původní INSTALL.md pro telefon s debug alpha1 z Actions.
APK, původní manifest a původní dokumenty zůstávají beze změny.

## Rozdílné podpisy

Balíček je v obou případech `cz.fortgps.field`, ale certifikáty se liší:

- Instalovaná debug alpha1: SHA-1 `F6:3B:C2:4C:A5:4D:26:76:0A:02:5C:83:D4:23:B6:A2:80:05:72:D3` (údaj dodaný uživatelem).
- Release alpha2: SHA-1 `B7:C8:49:00:77:67:0B:EC:95:94:BF:4C:F6:6A:B2:09:A1:43:1D:8D`.
- Release alpha2: SHA-256 `ef2ee10dc4833159a50f0b550e8eb4eeac53f21ef47079136b5151990b74fb72`.

Android takovou instalaci nepřijme jako běžnou aktualizaci. Shodný podpis s původní
release alpha1 nezajišťuje kompatibilitu s debug alpha1. Odinstalace odstraní
privátní databázi a nastavení. Pokud je nutné zachovat úplnou lokální přenosovou
historii, ponech alpha1 nainstalovanou; JSON není úplná záloha databáze.

## Nejdřív záloha, bez mazání dat

1. V alpha1 zastav pořizování nových relací. Otevři „Záznamy a záloha (N)“.
   Poznamenej N, UUID a revizi každé relace, počet otevřených relací a stav fronty.
   Otevřenou relaci není nutné rušit; import připouští nejvýše jednu otevřenou.
2. Stiskni „EXPORTOVAT ZÁLOHU JSON“. V systémovém dialogu ulož
   `FortGPS_Field_2_backup.json` do sdílené složky Download / Stažené soubory,
   nikoli do soukromé složky aplikace. Vyčkej na zprávu „Záloha JSON vyexportována“.
3. Pomocí správce souborů udělej druhou kopii mimo telefon, např. do vlastní
   soukromé složky na Drivu nebo do počítače. Nezveřejňuj ji ani nepřikládej k release.
4. Otevři skutečný JSON z uloženého souboru. Musí mít `format` rovno
   `FortGPS Field 2.0 backup` a pole `sessions`. Počet položek musí odpovídat N.
   Porovnej `session_id`, `revision`, `session_status`, měření, poznámky a vazby na
   fotografie s historií v aplikaci. Ověř, že obě kopie mají stejné bajty, ideálně
   SHA-256. Samotný náhled názvu souboru není ověření zálohy.
5. Zapiš si zvlášť e-mail Google účtu a ID / odkaz dosavadní importní složky.
   JSON zkontroluj před jakýmkoli odinstalováním. Při neúplném exportu, chybějících
   záznamech, více otevřených relacích nebo velikosti nad 20 MiB nepokračuj.

Export obsahuje aktuální revizi každé relace, UUID, uložená měření, poznámky a
fotografické reference; obsahuje i neúplné a zrušené relace. Neobsahuje starší revize
z outboxu, jejich pending/sending/error/confirmed stavy, vzdálená ID a potvrzení,
OAuth nastavení či tokeny, plánované práce, samotné fotografie ani privátní soubory.
Fotografie zálohuj zvlášť. Export není forenzní kopie `field2.db`.

## Volitelný přechod až po ověření záloh

Pokud přijímáš ztrátu historické lokální fronty a máš ověřené kopie JSON, můžeš
sám odinstalovat debug alpha1 a nainstalovat release alpha2. Pokud si nejsi jistý,
neodinstalovávej. Tento postup žádné mazání za uživatele neprovádí.

1. Stáhni samostatný APK z release `v2.0.0-alpha2`; ověř jeho SHA-256 podle
   `release-manifest.json` / `SHA256SUMS`. Otevři ho ve správci souborů a případné
   povolení instalace z tohoto zdroje uděl jen pro instalaci; potom je opět vypni.
2. První spuštění proveď offline, bez připojení Drive a před vytvářením nových relací.
   „Záznamy a záloha“ → „Importovat JSON 1.1 / 2.0“ → vyber ověřený JSON.
3. Vyčkej na „Importováno/ověřeno N záznamů“. Ověř počet, původní UUID, revize,
   oba body, poznámky, fotografické reference a případnou otevřenou relaci.
   Udělej nový export a porovnej obsah; alpha2 doplňuje nové výchozí položky, takže
   soubor nemusí mít stejný hash. Původní zálohu ponech.
4. Import používá transakci: při chybě má být celý vrácen zpět. Existující UUID
   s jiným obsahem se nepřepisuje. Při problému ponech obě zálohy a nepřipojuj Drive.

Ověřeno čtením implementace vydávaného commitu: `FieldViewModel.export/import`,
`Legacy.read`, `Repository.create/save` a modelu. Import 2.0 je implementovaný,
obnoví relace a vytvoří novou pending položku pro aktuální revizi každé relace.
Historickou frontu neobnoví. Obnovení na skutečném Samsungu zatím není ověřené.

### Důležité omezení opětovné synchronizace

Alpha2 při serializaci doplňuje nová pole s výchozími hodnotami. Proto může mít
importovaná relace stejné UUID/revizi jako již odeslaná alpha1, ale jiný JSON.
Drive porovnává přesné bajty a takový konflikt má odmítnout, nikoli přepsat.
Nezaměňuj tuto chybu za ztrátu lokálních dat. Nepřipojuj obnovený archiv k produkční
synchronizaci, dokud není jeho kompatibilita ověřena; tato publikační oprava problém
nemění. Žádné soubory na Drivu nemaž ani nepřepisuj jako obcházení konfliktu.

### Google OAuth pro release podpis

V odpovídajícím Google Cloud projektu musí být Android OAuth klient pro
`cz.fortgps.field` a release SHA-1 uvedené výše. Registrace pouze debug SHA-1
nestačí. Zachovej původního klienta i oprávnění ostatních aplikací. Do APK ani do
Fieldu se nevkládá Client Secret. Nastavení účtu a původní složky je nutné znovu
zadat a autorizovat. Existenci správné registrace tento publikační běh neověřuje
ani Google Cloud nenastavuje. Offline sběr a JSON import na OAuth nezávisí.

## Pilot XMP

Nejdřív pouze postradatelné testovací JPEG a nové relace. Alpha1 relacím chybí
časové pásmo; pro historické fotky bez EXIF offsetu automatika vyžaduje ruční
potvrzení. Viz původní INSTALL.md. Tato publikace žádné fotografie neupravuje.
