# FortGPS Field

Terénní dokumentace opevnění. Field sbírá důkazy, Foto propojuje fotografie,
Spatial počítá geografické vztahy a FortVision pomáhá s interpretací.

## Field 1.1 — webová provozní záloha

Původní `index.html`, `manifest.webmanifest` a `sw.js` jsou zachované.
[Spustit webovou verzi](https://rznratn-sys.github.io/fortgps-field/).
Webová localStorage data se automaticky do Androidu nepřenášejí; lze exportovat a importovat JSON.

## Field 2.0.0-alpha1 — nativní Android

Kotlin/Compose, nativní GPS, offline Room databáze, fotografické relace,
samostatné body, bezpečné neúplné relace, opravy a neměnná synchronizační fronta.
Google Drive autorizace používá Android klienta a scope drive.file. Fotografie
pořizuje běžný fotoaparát; dobrovolný Photo Picker uloží jen samostatné vazby.

Zdrojový projekt: [`android/`](android/). Schéma: [`schemas/`](schemas/).
Nezávislá integrace do existujícího Spatial: [`integration/`](integration/).
Podpisové klíče, skutečné JSON a soukromá geografická data v repozitáři nejsou.

- [Instalace a použití](docs/field2/INSTALL.md)
- [OAuth a přístup k původní složce](docs/field2/OAUTH.md)
- [Sestavení](docs/field2/BUILD.md)
- [Datový kontrakt a migrace 1.1 → 2.0](docs/field2/DATA_AND_MIGRATION.md)
- [Architektura](docs/field2/ARCHITECTURE.md)
- [Výchozí audit](docs/field2/AUDIT.md)
- [Testovací protokol](docs/field2/TEST_REPORT.md)
- [Známá omezení](docs/field2/LIMITATIONS.md)

Jde o první alfu k ověření na telefonu. Instalovatelný podepsaný APK je předáván
odděleně; GitHub Actions vytváří vývojový debug APK s jiným certifikátem.
Úspěšný build není tvrzení o ověřeném terénním GPS nebo skutečném OAuth uploadu.
