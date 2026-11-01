# Field 2.0.0-alpha3: instalace a kontrola na Samsungu

## Instalace

1. V alpha2 nejprve exportuj JSON zálohu a ulož ji mimo aplikaci.
2. Stáhni samostatný release APK `FortGPS_Field_2.0.0-alpha3.apk` z prerelease
   `v2.0.0-alpha3` v repozitáři rznratn-sys/fortgps-field.
3. Ověř SHA-256 podle release-manifest.json / SHA256SUMS.
4. Instaluj jako **aktualizaci** `cz.fortgps.field`, neodinstalovávej alpha2.
   versionCode je 20002, předchozí release alpha2 používá 20001.
5. Certifikát má SHA-1 `B7:C8:49:00:77:67:0B:EC:95:94:BF:4C:F6:6A:B2:09:A1:43:1D:8D`,
   SHA-256 `ef2ee10dc4833159a50f0b550e8eb4eeac53f21ef47079136b5151990b74fb72`.
   Pokud Android hlásí neslučitelný podpis, zastav instalaci. Neodinstalovávej
   aplikaci s daty kvůli obejití této chyby. Debug sestavení má jinou identitu.
6. Ověř počet relací a několik historických UUID, časů, providerů, přesností,
   XMP stavů a frontu. Historické network body zůstávají původní.

OAuth není předpokladem offline měření. Stávající nastavení Drivu se nemění.
Bez dokončené OAuth konfigurace nelze očekávat funkční vzdálený upload.

## Krátký terénní test

- Povol přesnou polohu telefonu, mobilní síť nech normálně zapnutou.
- PRVNÍ KONTAKT: musí se otevřít GNSS obrazovka, čekat na GPS a nic sama neuložit.
- Zkontroluj WGS84, provider `gps`, udávanou přesnost a stáří. Počkej na několik
  aktualizací; ulož konkrétní fix. Teprve nyní začíná fotografický interval.
- Fotografuj běžným fotoaparátem. Pak stiskni ZAMĚŘIT OBJEKT A UZAVŘÍT.
  **Fotografický interval tím končí; během čekání na závěrečný GPS již nefotografuj
  pro tuto relaci.** Závěrečný fix potvrď ručně.
- Ověř výsledek XMP v historii. U Camera_Scene_Info a Gallery_DC_Data očekávej
  vysvětlené ochranné odmítnutí, nikoli opravenou kompatibilitu Samsung Gallery.
- Vyzkoušej samostatný bod a novou JSON zálohu. Všechny nové fixy mají provider gps.

## Testy potřebné na skutečném telefonu

1. Start bez signálu a dlouhé čekání bez automatického uložení.
2. Špatný první fix, zpřesnění, poté zhoršení a přesun: vždy se ukládá právě
   zobrazená poloha, nikoli historický nejlepší fix.
3. Výpadek signálu: po překročení 10 s nelze uložit; po návratu signálu nový fix ano.
4. Vypnutí polohy a odebrání přesného oprávnění: chybu musí být vidět, žádný fallback network.
5. Pozadí a otočení obrazovky: po návratu nový fix, stav otevřené relace zachován.
6. Zrušení prvního a závěrečného měření, dvojí klepnutí na potvrzení.
7. Ukončení procesu při čekání i během ukládání. Potvrzené transakce mají přežít,
   nepotvrzený záměr se zahodí; při závěrečném čekání má relace zůstat otevřená.
8. Dvě sousední fotografické relace, dlouhé závěrečné čekání, žádná záměna snímků.
9. MediaStore souhlas i zamítnutí, obnovení originálu, přerušení zápisu a málo místa.
10. Po samostatném dokončení OAuth: offline fronta, návrat sítě, ověřené potvrzení
    stejného vzdáleného ID bez duplicit.

Testovat na postradatelných kopiích fotografií a s uloženou zálohou. Automatické
simulace nejsou terénní ověření. Instalace aktualizace na fyzickém Samsungu,
Samsung Gallery a živý Drive upload nejsou tímto vydáním předem prokázané.
