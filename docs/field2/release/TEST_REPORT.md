# Příprava lokálního podpisu na Pi — stav testování

Aplikace se nemění: zdroj alpha3 f04dba7c2147bf1041482e6c2e872325c5a4633e.
Lokálně prošlo 40 Python testů (14 původních + 26 release/handoff testů), Python
compile, Bash syntax a YAML. Nová skutečná kryptografická zkouška na x86-64 a
ARM64 runneru se ověřuje samostatně; výsledky budou doplněny z CI.

Testovací klíč je výslovně dočasný TEST ONLY, vytvořený pouze v dočasném adresáři.
Žádná produkční identita, nezávislá produkční záloha, konfigurace Pi/Věc,
instalace telefonu, migrace ani publikace nebyly provedeny. Testovací APK a klíče
se nesmějí uploadovat jako artifacts; přežívá pouze veřejná testovací zpráva.

Předchozí CI aplikace potvrzuje 53 passed / 3 private-fixture skips z 56 JUnit,
0 chyb a 29 varování v obou lintech. Nový unsigned build tento výsledek zopakuje.
Skutečné Raspberry Pi OS/hardware a projekt Věc nejsou dostupné. ARM64 Ubuntu
runner je test CPU/JVM kompatibility, nikoli ověření fyzického Pi.
