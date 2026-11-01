# Google OAuth a Drive

Používá se současný Google `AuthorizationClient`, nikoli webový OAuth token klient.
Jediný scope je `https://www.googleapis.com/auth/drive.file`. Aplikace nepoužívá
client secret, serverový refresh token ani heslo. Krátkodobé access tokeny zůstávají
jen v paměti; jejich obnovování spravuje Google Play Services.

## Nastavení vlastníkem Google Cloud projektu

1. Použít přednostně stejný Cloud projekt jako pro webový Field 1.1. Zapnout Drive API.
2. V Google Auth Platform → Clients vytvořit klienta typu **Android**.
3. Package name: `cz.fortgps.field`. SHA-1 musí odpovídat **skutečně instalovanému APK**.
   Otisk dodaného podepsaného APK je v soukromém instalačním předání. Lze ho znovu
   zjistit příkazem `apksigner verify --print-certs Field.apk`.
4. U projektu ve stavu Testing ponechat vlastní Google účet v Test users. Při
   opětovném vyžádání souhlasu ho udělit v otevřené aplikaci.
5. V aplikaci → Připojit / nastavit Google Drive zadat e-mail zvoleného účtu a
   odkaz na **stávající FortGPS_Field_Import**. Žádný Client ID ani tajný klíč se
   do aplikace nekopíruje. Android klient se identifikuje balíčkem a certifikátem.
6. Po připojení aplikace ověří e-mail přes Drive `about`, MIME typ a oprávnění zápisu
   cílové složky. Teprve potom povolí frontu.

## Přístup k existující složce

Samotné vložení ID složky neuděluje `drive.file` přístup. Scope zpřístupňuje soubory
vytvořené nebo otevřené aplikací; aplikace je v tomto smyslu Google Cloud projekt.
Proto se nejdřív zkouší stejný projekt, který už složku vytvořil ve Fieldu 1.1.
Pokud Google vrátí 403/404, přístup není doložený. Neřešit to plošným scope `drive`.
Je potřeba výslovně otevřít/udělit původní složku aplikaci přes Google Picker v témže
projektu. Nativní Drive folder picker není v této alfě implementovaný; tato varianta
je konkrétní blokující závislost, pokud sdílený Cloud projekt přístup neposkytne.
Pouhý Android Storage Access Framework výběr složky by neudělil Drive REST oprávnění.
Field nezakládá tichou náhradní složku.

## Chování přenosu

Každá uložená revize má samostatný neměnný JSON, např.
`fortgps-field-UUID-r2.json`. UUID relace zůstává stejné. Původní webové soubory se
nemění. Před novým uploadem proběhne kontrola stejné relace/revize ve složce, pak
`files.generateIds`. ID a cíl se bezpečně uloží do SQLite **před POSTem**. Opakovaný
pokus používá stejné ID. HTTP 409 vyvolá kontrolu původního souboru, nikoli další create.

Potvrzení vyžaduje metadata (správná složka, MIME, nevyhozený soubor) a zpětné čtení
celého JSON se shodnými UTF-8 bajty. Lokální stav se změní na `confirmed` až potom.
Konfliktní obsah se nepřepisuje. Více vzdálených souborů stejné revize vyžaduje kontrolu.

WorkManager periodicky kontroluje frontu (interval 15 min), při uložení a ručním
stisku také zařadí jednorázovou práci s podmínkou připojení. Android může provedení
odložit kvůli Doze, spořiči, Samsung omezením či chybějícímu souhlasu. Po force-stop
obvykle až do dalšího spuštění práci neprovede. Žádná garance okamžitého uploadu.
401 vymaže cache tokenu, fronta ukáže chybu; souhlas vyžadující UI se nespouští z pozadí.
Síťové/429/5xx chyby používají exponenciální retry. Fronta i lokální kopie zůstávají.

## Oficiální dokumentace

- https://developer.android.com/identity/authorization
- https://developers.google.com/android/reference/com/google/android/gms/auth/api/identity/AuthorizationClient
- https://developers.google.com/workspace/drive/api/guides/api-specific-auth
- https://developers.google.com/workspace/drive/api/guides/create-file#use_a_pre-generated_id_to_upload_files

Google Play Services jsou potřeba pro tuto implementaci OAuth, nikoli pro nativní
GPS či lokální sběr. Autorizace a skutečný upload z telefonu vyžadují test vlastníkem
účtu; mock server ani konektor v chatu je nenahrazují.
