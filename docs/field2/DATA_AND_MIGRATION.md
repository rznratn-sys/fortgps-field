# Field 2.0: datový kontrakt a migrace

Schéma: [`field-session-2.0.schema.json`](../../schemas/field-session-2.0.schema.json).
Neobsahuje katalogové identity jako potvrzenou identifikaci objektu. Výchozí stav je
`unverified`. Jeden JSON = jedna úplná revize relace. `session_id` je stabilní UUID,
`measurement_id` samostatné UUID. `revision` roste po každé uložené opravě či doplnění.

Room uchovává aktuální serializovanou relaci a odděleně všechny neměnné revize fronty.
Obojí se zapisuje v jediné SQLite transakci, WAL; neexistuje destruktivní fallback
migrace ani automatické mazání. SQL tabulka nezdvojuje Spatial katalog.
Vnitřní relace mezi měřeními a fotografiemi kontroluje validátor před zápisem.

## Významy bodů

| Role | Význam | Objektové párování |
|---|---|---|
| first_contact | Poloha zařízení na začátku fotografování | Nepoužít jako zaměřený objekt |
| object_survey | Nově zaměřená poloha zařízení vůči objektu | Primární terénní kotva k prověření |
| legacy_observer | Původní webový bod, význam nezpřesněný zpětně | Bez automatického objektového párování |

`position_relation`: `on_roof`, `at_entrance`, `safe_nearby`. Žádná z variant netvrdí
geometrický střed. Model umožňuje více měření, běžné UI nabízí první a závěrečné.
Chybějící přesnost/výška/provider jsou null, nikoli nula. Nulová výška může být platná.

Android `Location.altitude` je podle API výška nad referenčním elipsoidem WGS84;
uloží se jako `wgs84_ellipsoid`. Volitelná MSL výška API 34+ je oddělená a označená
`android_msl_unspecified_national_datum`. Ani jedna se nevydává za Bpv, IGN, výšku
podlahy nebo terénu. Zdroj: https://developer.android.com/reference/android/location/Location

`measured_at` pochází z fixu, `created_at` z vytvoření záznamu. Ukládá se i monotónní
čas fixu a příznak mock, pokud jsou dostupné. Monotónní čas není přenositelný mezi
rebooty/zařízeními. Časy výstupu aplikace jsou UTC; lokální EXIF časy bez offsetu
nesmějí být později porovnávané bez doložené časové zóny.

`started_at` = čas prvního fixu, `ended_at` = čas závěrečného fixu, při uzavření bez
GPS/zrušení čas uživatelské akce. Otevřená relace má konec null. Interval přes 2 h
vyvolá upozornění k prověření, nikoli automatické ukončení. Obrácené hranice nebo
nesouhlas času zařízení/fixu se zachovají a označí. Interval s časovým konfliktem
se nesmí používat pro automatické foto přiřazení.

## Fotografie

`FieldPhotoAssociation` nese session_id, SHA-256 a velikost bajtů poskytnutých
Photo Pickerem, původní název (informativní), metodu a stav uživatelského potvrzení.
`association_confidence=null`: číselná pravděpodobnost se nevymýšlí.

SHA-256 funguje po přesunu/přejmenování **týchž bajtů**. Poskytovatel může vrátit
kopii s odstraněnými souřadnicemi či konvertované médium; proto explicitně
`hash_scope=android_picker_delivered_bytes`, `original_identity_verified=false`.
Neshodný hash s původním ZIP/JPG vyžaduje další důkazy, nikoli odmítnutí fotografie
nebo předstírání shody. URI v telefonu není trvalá identita a neexportuje se.
Aplikace nerozebírá a nemění EXIF, neurčuje zde orientaci ani vzdálenost fotoaparátu.
Původní EXIF GPS patří fotoaparátu a zůstává oddělené od object_survey.

## Field 1.1 → 2.0

Android import podporuje původní jednotlivé záznamy i zálohu
`{"format":"FortGPS Field v1","records":[...]}`. Zachová id jako session_id,
souřadnice, přesnost, position_time, recorded_at, poznámku, typ a celý původní JSON.
ID původního měření se odvodí deterministicky z UUID a role pomocí jmenného MD5 UUID
(Java UUID.nameUUIDFromBytes), ne z pořadí importu. Není to hash pro důkaz integrity;
k tomu downstream používá SHA-256 původních bajtů.

Legacy bod má `legacy_observer`, session_mode=legacy, session_status=legacy_unverified.
Začátek/konec fotografické relace zůstane null, protože je web neměřil. Výšky také
null. Migrovaný záznam není zpětně povýšen na object_survey.

Import opakovaného shodného UUID/obsahu je beze změny; jiné údaje pod týmž UUID se
zastaví jako konflikt. Celá dávka se provádí transakčně, nevalidní dávka nevytvoří
částečný import. Původní Field 1.1 JSON na Drivu se nemaže ani nepřepisuje. Nový 2.0
JSON nemusí být čitelný starým webovým parserem; kompatibilita je směrem **importu
starých dat do 2.0**, nikoli falešná záměna nového formátu za starý.
