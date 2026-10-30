# Field 2.0.0-alpha1 — testovací protokol

Datum: 30. 10. 2026. Testy obsahují syntetická data; navíc proběhl soukromý audit
všech šesti skutečných Field 1.1 záznamů a integrační dotazy nad oběma publikovanými
Spatial SQLite. Soukromé souřadnice nejsou součástí tohoto protokolu ani Gitu.

## Automatizované výsledky

JUnit: **24 testů**, 0 chyb: SessionTest 16, DriveTest 6, RoomRestartTest 2.
Python kontrakt: **10 testů**, 0 chyb. Výsledky se vztahují ke zdrojům předaným
v této revizi; přesný commit je uveden v publikačním předání a v historii větve.

| Oblast | Skutečně provedené ověření | Hranice důkazu |
|---|---|---|
| UUID a JSON | Round-trip, jedinečnost 1000 relací, stabilní session/measurement identita | Jednotkové testy |
| Dva body | Nový measurement UUID, first_contact → object_survey, rozestup přes 1 km bez zákazu | Test doménového modelu, ne GPS v terénu |
| Neúplná/zrušená relace | Uzavření bez GPS a zrušení uchovají první měření | Doménové testy |
| Oprava typu | Nová revize, původní UUID a měření zachované | Doménové testy |
| Časové hranice | Dlouhá relace, obrácené časy, neúplné hranice, chybějící timezone | Doména + Python validace |
| Výšky | Null se nepřevádí na nulu, platná nula zůstává nulou, chybějící reference je chyba | Jednotkové testy |
| Offline DB | Více relací, aktivní relace, uzavření a znovuotevření skutečné Room DB, zachování sending/remoteId/folderId | Robolectric API 28, ne fyzické vypnutí telefonu |
| Duplicita v DB | Opakovaný create téhož UUID odmítnut, obě tabulky beze změny | Room/Robolectric |
| Spuštění UI | MainActivity vytvořena bez OAuth/autorizace a polohového měření | Robolectric; bez obrazové QA na skutečném displeji |
| Opakovaný přenos | Existující předem uložené Drive ID se pouze ověří; HTTP 409 také ověří obsah | MockWebServer, ne Google server |
| Výpadek uploadu | Spojení po přijetí požadavku přerušeno; další pokus se stejným ID neprovádí druhý create | MockWebServer |
| Potvrzení | Shoda metadat/cíle a bajtů JSON; rozdílný obsah se nepotvrdí | MockWebServer |
| Neplatná autorizace | HTTP 401 vede k chybě, nikoli potvrzení záznamu | MockWebServer; skutečný souhlas neotestován |
| Migrace 1.1 | Všech 6 originálů načteno, UUID zachované, role legacy_observer, výšky null, round-trip 2.0 | Soukromá lokální fixture; CI bez ní tento soukromý audit neopakuje |
| Foto metadata | Null confidence, neúplná/chybná reference odmítnuta, vazba na správnou relaci | Kontrakt; skutečný Photo Picker čeká na telefon |
| Spatial ČR | Skutečný katalog, kandidáti ≤10 a ≤1000 m, neslučitelné delta-Z null, opakovaný výstup shodný, mimo pokrytí bez fallback | Volání původního publikovaného jádra |
| Spatial Francie | Skutečný katalog, validní kandidátní výstup, opakování shodné | Volání původního publikovaného jádra |
| Ochrana katalogů | SHA-256 české i francouzské DB před a po shodné, obě sidecar schémata validní | Žádné nové importy nebo přepnutí registrů |
| Ochrana webu | index.html, sw.js, manifest.webmanifest beze změny oproti výchozímu commitu | Git diff/hash; nový browser smoke test neproveden |

Záměrné přerušení spojení při prvním běhu odhalilo transparentní retry HTTP klienta.
Bylo vypnuto: opakování vlastní fronta se stejným perzistentním ID. Následný test prošel.
Neúspěšné pokusy se nevykazují jako finální úspěšné testy.

## Build a limity runtime ověření

Sestavují se debug APK, podepsaný release APK a instrumentační test APK. Podpis se
ověřuje samostatně nástrojem apksigner. Android lint nemá chyby; případná doporučení
pro KSP a Kotlin SharedPreferences edit nejsou důkazem funkční vady. Přesné výsledky
posledního čistého buildu a APK hash jsou v artefaktovém předání.

Pokus o plný Android emulátor byl skutečně proveden. Prostředí nemá `/dev/kvm`;
softwarový start se nedostal do použitelného stavu a byl zastaven. Instrumentační
APK byl sestaven, test na plném emulátoru/telefonu nebyl úspěšně spuštěn.
Robolectric se uvádí odděleně, nenahrazuje přiznanou chybějící zkoušku telefonu.

## Připravený test na telefonu

1. Instalovat podepsaný APK. Udělit přesnou polohu, změřit první kontakt a nový
   závěrečný bod po použití běžného fotoaparátu. Ověřit typ, polohu vůči objektu,
   časy, přesnost a dvě různá measurement_id v exportu.
2. V režimu letadlo se zapnutou GPS uložit více relací a samostatný bod. Ukončit
   aplikaci, znovu spustit; záznamy musí zůstat. Zkusit relaci bez druhého fixu.
3. Vypnout systémovou polohu a vyzkoušet nedostupnost, pak opakování. Zamítnout
   oprávnění, potom povolit. Žádné vymyšlené souřadnice nebo nulová náhradní výška.
4. Připojit Android OAuth klienta se správným certifikátem a původní složku. Odeslat
   testovací relaci. Zpětně na Drivu ověřit obsah, UUID, revision a role měření.
5. Přerušit internet během uploadu, obnovit a opakovat; jeden soubor pro revizi,
   stejný obsah, confirmed až po readbacku. Zrušit souhlas a ověřit viditelnou chybu.
6. Potvrdit několik fotografií přes Picker. Ověřit, že původní JPG v telefonu/ZIP
   zůstaly stejné a že případný rozdíl hashe dodaných bajtů není považovaný za shodu.
7. Ověřit čitelnost a ovládání venku, v rukavicích a na Samsungu. Zkontrolovat
   odložené WorkManager přenosy při úsporném režimu.

## Jednoznačný stav

- **Implementováno:** nativní sběr, relace, lokální DB, revize/fronta, OAuth/Drive
  klient, import/export, dobrovolné foto vazby, CLI adaptér existujícího Spatial.
- **Otestováno:** uvedené JUnit/Python testy, reálné read-only katalogové dotazy,
  sestavení a kontrola podpisu.
- **Připraveno, neotestováno na zařízení:** systémová GPS, oprávnění, Photo Picker,
  WorkManager po pádu OS, skutečné přihlášení/upload/readback z Androidu.
- **Jen navrženo:** pravidelný Drive poller, webhook, automatické Foto/FortVision vazby.
- **Blokováno:** nastavení Android OAuth klienta vlastníkem účtu a fyzický telefon;
  případně explicitní drive.file grant původní složky přes Picker v témže projektu.

Všech 11 kritérií první použitelné terénní verze proto zatím nelze prohlásit za
prokázaná. Předávaný stav je skutečně sestavená první alfa určená k instalaci a
dokončení zařízení/OAuth zkoušky, nikoli deklarace hotového produkčního nasazení.
