# Architektura Field 2.0

Kotlin, Compose, Android LocationManager, Room/SQLite, WorkManager,
Google AuthorizationClient a Drive REST přes OkHttp.

UI → FieldViewModel → SessionEngine/Repository → jedna Room transakce uloží
aktuální session payload a neměnný outbox payload. FieldMeasurement a
FieldPhotoAssociation jsou typované součásti session JSON; konzistence UUID a hodnot
se kontroluje před zápisem. Databáze nemá kopii Spatial ani fotografické binární soubory.

SyncWorker si vezme uložený snapshot, před POSTem persistuje vzdálené ID a cíl,
ověří serverový obsah a samostatně zapíše confirmed. Singleton mutex chrání současný
periodický a jednorázový worker v jediném procesu. Po pádu jsou i stavy sending
znovu způsobilé ke zpracování se stejným vzdáleným ID. Android aplikace nemá druhý proces.

SessionEngine je čistý Kotlin bez Androidu pro testování časů, UUID a stavových
přechodů. DriveClient má injektovatelný HTTP klient/base URL pro test přenosových
poruch. Mock test není vydáván za skutečnou Google autorizaci nebo serverovou zkoušku.

Přihlášení je opt-in v nastavení a neblokuje místní práci. Nepoužívá se background
location, fotoaparát v aplikaci, WebView, privilegovaný storage ani služba typu GIS.

Výpočetní adaptér Field → Spatial je mimo Android. Čte původní JSON, volá původní
SpatialCatalog a zapisuje oddělený sidecar. Foto a FortVision zůstávají samostatné.

## Změnové hranice

- Původní index.html, manifest.webmanifest a sw.js se nemění.
- Původní Field JSON, fotografie a katalogy jsou jen čteny.
- Produkční registry Foto/Spatial se neaktivují ani neupravují.
- Veřejný Git obsahuje pouze kód, dokumentaci, schémata a syntetické testy.
- Podpisová identita, skutečné GPS, účty, JSON a katalogy zůstávají v soukromém předání.
