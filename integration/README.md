# Field → Spatial (0.1.0)

Nezávislý CLI adaptér, bez zápisu do původního JSON, katalogu nebo produkčního registru.
Používá přímo publikovaný `terrain_catalog.TerrainCatalog.nearby`, tedy existující
R-tree a WGS84 geodetický výpočet. Kód Spatial ani katalog se nekopíruje do tohoto repozitáře.

```sh
python -m pip install -r integration/requirements.txt
# Nainstalovat také závislosti konkrétní publikované edice Spatial.
python integration/field_spatial.py --field private/input.json \
  --spatial-code /path/to/existing/published/code \
  --catalog /path/to/existing/catalog.sqlite \
  --catalog-sha256 SHA256_FROM_PUBLICATION \
  --out private/input.spatial.json
```

Katalog se vybírá výslovně, nelze ho odhadovat podle nejbližšího objektu. Dotaz mimo
jeho existující územní obálku nemá náhradní párování. Česká národní obálka není hranice
státu; u přeshraničních bodů musí katalog vybrat obsluha. ČR filtruje populaci `structures`
před K=10. Francie zachovává pravidla původního katalogu. Vrací nejvýše 10 kandidátů
do 1000 m pro každé `object_survey`; ostatní role se pro objektové párování nepoužijí.

Každý kandidát nese stabilní UID, verzi, vzdálenost, azimut, klasifikaci, reprezentativní
metodu, zdroj a dostupnou výšku terénu. Elipsoidická či neurčená MSL výška zařízení se
neodečítá od Bpv/IGN terénu. `delta_z_m` proto zůstává null. Ani stejná vertikální CRS
by sama nevyřešila rozdíl významu „zařízení na stropě“ a „model terénu“.

Idempotence: při stejných vstupních bajtech a katalogu vznikne shodný sidecar;
existující jiný výstup se nepřepisuje. Automatická identifikace ani slučování nenastává.
Před zpracováním více revizí seskupit podle session_id, zkontrolovat konflikty stejné
revize a vybrat nejvyšší revizi. Otevřené/zrušené relace nemají výsledky párování.

Pro automatizaci je doporučena pravidelná jednorázová kontrola složky existujícím
Colab/Drive runnerem. Identita úlohy = SHA-256 originálního JSON + SHA-256 katalogu +
verze adaptéru; evidovat přijetí a dokončení samostatně. Webhook Drive vyžaduje veřejný
HTTPS endpoint a obnovování odběrů, pro tuto etapu se nenasazuje. Automatický Drive
poller zatím není implementovaný ani aktivovaný; CLI a výstupní kontrakt jsou použitelné
samostatně. Nemění se plánované úlohy uživatele.

Foto/FortVision mohou číst `session_id`, `measurement_id`, časové hranice, důkazy GPS,
potvrzené foto vazby a kandidáty. Časový interval je důkaz k prověření, nikoli identita.
Žádná orientace střílen ani palebné sektory se z těchto bodů neodvozují.
