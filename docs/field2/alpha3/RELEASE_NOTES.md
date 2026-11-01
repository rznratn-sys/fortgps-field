# FortGPS Field 2.0.0-alpha3

Opravné vydání po terénním testu 1. listopadu 2026.

- Nová měření pouze ze satelitního GPS_PROVIDER, bez síťového fallbacku.
- Živé WGS84, udávaná přesnost, stáří, provider a historická nejlepší přesnost.
- Libovolně dlouhé čekání a ruční potvrzení konkrétního aktuálního fixu.
- Zastaralý fix nad 10 s, chyba oprávnění a návrat z pozadí vyžadují nový fix.
- Fotografický interval začíná potvrzením prvního fixu a končí stiskem
  závěrečného měření, nikoli koncem čekání na GPS.
- Zachována Room DB v2, UUID, revize, historie a synchronizační fronta.
- Zpřísněná validace JPEG/SEF, původní obnova a ochrana EXIF/obrazu zůstávají.
- Camera_Scene_Info a Gallery_DC_Data zůstávají **bezpečně odmítány**. Jejich
  podporu bez skutečných originálů a testu Samsung Gallery nelze doložit.

Instalovat jako aktualizaci podepsané release alpha2. Před instalací exportovat
JSON zálohu; aplikaci neodinstalovávat. Balíček `cz.fortgps.field`, versionCode
20002, stejný release certifikát. Manifest a SHA256SUMS určují konkrétní APK a
zdrojový commit. Alpha2 zůstává dostupná beze změny.

Jde o vydání k terénnímu testu. Živý GNSS, instalace s daty a MediaStore na skutečném
Samsungu, Samsung Gallery a skutečný OAuth upload vyžadují fyzické ověření.
Podrobné výsledky a jejich meze jsou v TEST_REPORT.md.
