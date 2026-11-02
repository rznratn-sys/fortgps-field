# FortGPS Field 2.0.0-alpha3

Zdrojový commit: `f04dba7c2147bf1041482e6c2e872325c5a4633e`.
Balíček `cz.fortgps.field`, versionCode `20002`.

Živé GNSS přes GPS_PROVIDER, ruční potvrzení aktuálního fixu, průběžná přesnost,
ochrana fixu staršího než 10 s, fotografické relace a automatický poslední JPEG.
Stávající XMP zápis do originálu, Room v2, offline režim a Drive jsou zachovány.
Alpha3 nepřidává mapy ani katalogy.

Camera_Scene_Info a Gallery_DC_Data zůstávají bezpečně odmítnuté. Podpora dalších
Samsung SEF variant ani Samsung Gallery není potvrzena.

**Nová podpisová identita vyžaduje migraci přes export a import.** Původní klíč
není dostupný. Certifikát a checksum konkrétního APK určuje release-manifest.json.
Před odinstalací alpha2 čti celý INSTALL.md: JSON nepřenáší outbox historii,
privátní JPEG journal/zálohy, fotografie ani OAuth. Před obnovením sync je nutná
kontrola možných duplicit a dostupnosti starých souborů přes drive.file.

Výsledky daného build běhu jsou v TEST_REPORT.md. Fyzická instalace na Samsung,
živý GNSS, Samsung Gallery, migrace telefonu ani produkční Drive upload nebyly
prokázány automatickými testy. Bez obnovitelné zálohy klíče se vydání nesmí spustit.
