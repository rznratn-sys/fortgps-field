# Instalace / aktualizace alpha2

Použij výhradně samostatný **FortGPS_Field_2.0.0-alpha2.apk** v prerelease.
Testovací APK ani debug artefakt z Actions není instalační dodávka.
Balíček: cz.fortgps.field; versionCode 20001.

1. Ve stávajícím Fieldu vyexportuj zálohu JSON. Ověř, že ji lze otevřít a obsahuje
   očekávané relace a UUID. Při aktualizaci se data mají zachovat; záloha je pojistka.
2. Stáhni samostatný APK a otevři jej. Android má nabídnout aktualizaci.
3. Pokud hlásí nekompatibilní podpis, **neodinstalovávej** aplikaci a nemaž data.
   Nejprve zjisti certifikát nainstalované verze. Alpha1 bylo distribuováno jako
   soukromě podepsaný release i jako GitHub Actions debug build; nejsou zaměnitelné.
4. Po aktualizaci ověř počet relací, původní UUID, otevřenou relaci a frontu Drive.
5. Pro automatické hledání povol všechny fotografie a přístup k původním metadatům
   polohy. Omezený systémový výběr fotografií není úplný archiv a automatiku blokuje.
6. Při první úpravě snímku potvrď standardní systémový souhlas. Bez něj zůstane GPS
   uložená, stav XMP bude vyžadovat oprávnění.

## Pilot na Samsungu

Použij nejdřív postradatelné testovací fotografie. PRVNÍ KONTAKT → fotografie běžným
fotoaparátem v DCIM/Camera → ZAMĚŘIT OBJEKT A UZAVŘÍT. Vyčkej na samostatné potvrzení
XMP s konkrétním názvem. V historii je výsledek, chyba i možnost opakování.
Pořiď také snímek **po uzavření**; ten se nesmí změnit.

Přesuň výsledný JPG a následně jej zabal do ZIP. Při analýze otevři soubor z ZIP,
nikoli upravený náhled sdílený přes galerii. EXIF GPS musí být původní poloha
fotoaparátu; FortGPS XMP musí obsahovat závěrečné object_survey.

Starý Test 3 z alpha1 nemá uložené časové pásmo relace a šest JPG nemá EXIF offset.
Automatika proto nesmí předstírat jistotu. Ručně potvrď 20261031_185149.jpg, pokud
chceš testovat přímo historické soubory. Nové relace alpha2 pásmo zaznamenávají.

## Přerušený zápis

Historie zobrazí zachovanou dočasnou zálohu a nabídne obnovu. Povolení může být po
restartu nutné potvrdit znovu. Dokud není obnova nebo zápis ověřen, nemaž data
aplikace a neodinstalovávej ji. Neočekávaný platný JPEG vyžaduje výslovné potvrzení
obnovy; ta odstraní případné pozdější změny provedené galerií.

MediaStore nenabízí atomickou výměnu cizího JPEG. Při pádu může být viditelný soubor
dočasně neúplný; záloha umožňuje obnovu. Tato vlastnost není ověřena na skutečném
Samsungu, dokud není dokončen přiložený provozní checklist.
