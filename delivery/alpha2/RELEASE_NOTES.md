Automatický zápis FortGPS XMP do jediného posledního JPEG fotografické relace.
Původní EXIF GPS fotoaparátu se nepřepisuje. Závěrečné object_survey je samostatná
objektová kotva v XMP. Room migrace 1→2 zachovává data a synchronizační frontu.

**Prerelease pro telefonní pilot. Skutečný Android/Samsung zatím nebyl ověřen.**
34 Kotlin/JVM/Robolectric testů, 11 Python testů a lint prošly. Reálný Test 3:
XMP nezávisle přečetl ExifTool; obraz, původní EXIF i šest ostatních fotografií
zůstaly shodné. Historické fotografie bez EXIF offsetu vyžadují ruční potvrzení,
pokud stará relace neobsahuje uložené časové pásmo.

MediaStore není atomická výměna. Field před zápisem uchová ověřenou dočasnou
zálohu; při přerušení může být potřeba obnova a nové systémové povolení.
První provozní test prováděj na postradatelných JPG. Motion Photo/MPF/extended XMP
a soubory nad 16 MiB se bezpečně odmítají.

Instalační soubor: **FortGPS_Field_2.0.0-alpha2.apk** — release, nikoli debug/test.
Podpis shodný s původním soukromě podepsaným alpha1 release. Pro alpha1 debug z
Actions nemusí být kompatibilní. Před aktualizací exportuj a otevři JSON zálohu.
Při konfliktu podpisů aplikaci neodinstalovávej a nemaž data.

Podrobnosti, přesná omezení a dosud neprovedené testy jsou v přiložených návodech.
