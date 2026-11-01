# Field 2.0.0-alpha3: GNSS a fotografický interval

## Změna oproti alpha2

`LocationCapture` registruje pouze `LocationManager.GPS_PROVIDER`. Síťový a fused
provider ani poslední známou polohu nečte. Vedle zdroje kontroluje ukládací politika
provider `gps`, monotónní čas, platné souřadnice a horizontální přesnost. Vysoká
přesnost sama o sobě satelitní původ nedokazuje. Mock fixy odmítá. Mobilní data
ani asistované GNSS není třeba vypínat.

První kontakt, závěrečné zaměření a samostatný bod otevírají stejnou měřicí
obrazovku. Neexistuje timeout ani automatické potvrzení. Uživatel potvrzuje
konkrétní zobrazené UUID fixu. Jestli mezitím přijde nový fix, potvrzení starého
UUID je odmítnuto a uživatel zkontroluje nové údaje. Ukládá se celý neměnný
`FieldMeasurement`, včetně času GNSS, provideru, monotónního času, výšky WGS84,
vertikální přesnosti a případně Android MSL. MSL není český Bpv ani francouzský NGF.

## Stavový model a UI

- Čekání bez prvního fixu: žádné souřadnice a zakázané uložení.
- První platný fix: aktuální WGS84, udávaná přesnost, skutečné stáří, provider a čas.
- Další fixy: průběžné měření; vždy poslední fix v monotónním pořadí.
- Nejlepší historická přesnost: pouze samostatná informativní hodnota, nikdy náhradní poloha.
- Ztráta signálu hlášená GNSS službou: fix ihned zneplatněn.
- Bez aktualizace přes 10 sekund: zastaralý fix a zákaz uložení.
- Zakázaná poloha / odebrané přesné oprávnění / chyba služby: zákaz uložení,
  konkrétní chyba a možnost opětovného připojení GNSS.

Přesnost nad 50 metrů vyvolá upozornění; žádná minimální požadovaná přesnost se
nevynucuje. Je nutná přítomná konečná nezáporná hodnota přesnosti. Stáří se počítá
z `elapsedRealtimeNanos`, nikoli z měnitelných nástěnných hodin. UI se obnovuje
po 500 ms, ale potvrzení vždy znovu kontroluje aktuální monotónní čas a oprávnění.

**10 sekund je aplikační bezpečnostní politika**, nikoli univerzální fyzikální
mez. Při přesunu stále vzniká chyba závislá na rychlosti a době od posledního fixu.
Uživatel má měřit v klidu. Odhad přesnosti Androidu není nezávisle ověřená chyba.

## Životní cyklus a atomické uložení

`onStop` odpojí LocationManager i GNSS callback. Návrat do popředí zahájí nový
odběr s novou monotónní hranicí: předchozí fix se nedá uložit ani tehdy, pokud je
mladší než 10 s. ViewModel zachová záměr a informační nejlepší přesnost při otočení;
po novém odběru vyžaduje nový fix. Chybu oprávnění nebo služby lze znovu připojit.

Nepotvrzený záměr je pouze v paměti. Po ukončení procesu se zahodí. Již potvrzená
první poloha a otevřená relace zůstávají v Room. Zrušení prvního měření nevytvoří
prázdnou relaci; zrušení závěrečného měření nemění otevřenou relaci ani revize.
Před uložením se synchronně uzamkne potvrzení proti dvojímu klepnutí. Repository
ukládá relaci a outbox v jediné Room transakci. Dřívější XMP obnova a outbox
zůstávají zachované. Ostatní úpravy relací a import jsou při měření blokovány.

## Přesné hranice fotografování

Nové relace používají volitelnou politiku `manual_gnss_v1`:

1. `started_at`: systémový UTC čas **potvrzení prvního fixu**. Čas GNSS zůstává
   nezávisle v `measurement.measured_at`; není přepsán časem potvrzení.
2. `ended_at`: systémový UTC čas **stisku ZAMĚŘIT OBJEKT A UZAVŘÍT**, před čekáním
   na závěrečné GNSS. Do databáze se uloží až spolu s potvrzeným závěrečným fixem.
3. `updated_at`: čas uložení výsledku. Závěrečný GNSS může být pozdější než
   `ended_at`; jde o záměrnou a zdokumentovanou sémantiku.
4. `photo_started_elapsed_nanos` a `photo_ended_elapsed_nanos` nezávisle kontrolují
   délku fotografického intervalu. Hodiny GNSS se ověřují proti příslušné hranici
   s tolerancí 2 s. Změna hodin/boot epochy ruší automatický výběr.
5. Časová zóna zařízení se zaznamená při každé příslušné fotografické hranici.
   Europe/Prague není vložená univerzální konstanta.

Dlouhé čekání před prvním potvrzením ani po stisku závěrečného měření není součástí
fotografického intervalu. Během závěrečného čekání již nefotografovat pro danou
relaci. Pokud je měření zrušeno, hranice se zahodí a fotografování v otevřené relaci
může pokračovat. Celosekundový EXIF na hranici zůstává nejednoznačný a vyžaduje
ruční výběr. Relace zahájená alpha2 a uzavřená alpha3 je označena
`mixed_alpha2_alpha3` a automatický výběr odmítne; ruční výběr v původním intervalu
zůstává dostupný.

## Datová kompatibilita

Room zůstává verze 2 se stejným schématem a existující nedestruktivní migrací 1→2.
Tři volitelné vlastnosti JSON jsou přidané do stávajícího kontraktu 2.0. Staré
řádky, outbox, XMP operace a historické síťové polohy se nepřepisují. Import stejné
relace porovnává dekódovaný obsah včetně výchozích hodnot; neodmítá starý JSON jen
kvůli chybějícím novým nulovým vlastnostem. Odlišný obsah se stejným UUID zůstává
konfliktem. Starší aplikace s přísným dekodérem nemusí číst nový export alpha3;
zpětný downgrade APK ani exportu není podporován.

Google Drive/OAuth a vzdálené ID, pořadí revizí, retry a potvrzení obsahu jsou beze
změn. Dokončená OAuth konfigurace se nepředpokládá. Produkční složky se nemění.

## Primární technické podklady

- Android LocationManager: https://developer.android.com/reference/android/location/LocationManager#GPS_PROVIDER
- Android monotónní čas fixu: https://developer.android.com/reference/android/location/Location#getElapsedRealtimeNanos()

Tyto zdroje popisují API, nikoli prokazatelnou přesnost konkrétního Samsungu.
