# Reprodukovatelné sestavení

Připnuté nástroje: JDK 17, Gradle 8.11.1 (wrapper a SHA-256 distribuce), Android
Gradle Plugin 8.9.1, Kotlin/Compose compiler 2.0.21, compile/target SDK 35,
Android SDK Build Tools 35.0.0. Knihovny jsou v `android/app/build.gradle.kts`.
Jde o sestavitelný připnutý základ, nikoli tvrzení o nejnovějších dostupných verzích.

```sh
export ANDROID_HOME=/path/to/android-sdk
sdkmanager 'platform-tools' 'platforms;android-35' 'build-tools;35.0.0'
cd android
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew :app:assembleDebugAndroidTest
# Připojený telefon/emulátor:
./gradlew :app:connectedDebugAndroidTest
```

`local.properties` s `sdk.dir=...` je lokální alternativou, do Gitu nepatří.
Debug APK vznikne v `app/build/outputs/apk/debug/`. Je určený pro vývoj a má jiný
certifikát než soukromě podepsaný instalační APK. Nestřídat je na telefonu s daty.

## Podepsaný APK pro sideload

```sh
export FIELD_KEYSTORE=/private/path/field2-release.jks
export FIELD_KEY_ALIAS=field2
# FIELD_STORE_PASSWORD a FIELD_KEY_PASSWORD načíst ze soukromé správy tajemství.
./gradlew :app:assembleRelease
apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
```

Bez `FIELD_KEYSTORE` vzniká unsigned release, který není instalační dodávkou.
Nikdy necommitovat keystore, hesla, účty, tokeny ani terénní záznamy. Soukromé
předání podpisové identity je oddělené od veřejného repozitáře a umožní budoucí
aktualizaci téhož balíčku bez ztráty databáze.

Reprodukce znamená sestavení ze stejného zdrojového commitu a připnutých nástrojů.
Bajtová shoda APK nezávisle sestavených v jiném prostředí nebyla prokázaná. Pro
ověření dodaného APK použít přiložený SHA-256 a otisk certifikátu.

## Python kontrakt

```sh
python -m pip install -r integration/requirements.txt
python -m unittest discover -s tests -v
```

Reálné katalogové testy používají původní publikované SQLite a jejich původní kód
z Drivu; nesmí být přidané do veřejného repozitáře. JSON Schema je v `schemas/`,
Room schema v `android/app/schemas/`. Migrace databáze není destruktivní: tato první
verze nemá předchozí Android DB, budoucí změna musí přidat explicitní Room migration.
