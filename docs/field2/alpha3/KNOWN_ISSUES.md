# Alpha3: známá omezení

- Camera_Scene_Info a Gallery_DC_Data zůstávají odmítány: chybí skutečné originály,
  rozbor payloadů a ověření Samsung Gallery. GPS relace se přesto zachová.
- Žádná automatická hranice přesnosti: uživatel může vědomě uložit nepřesný GNSS.
  Horizontální přesnost je odhad Androidu; nepředstavuje ověřenou skutečnou chybu.
- Fix může být až 10 s starý. Při pohybu může být časová chyba významná. Měř v klidu.
- Po každém návratu do popředí (včetně otočení s restartem Activity) je třeba nový fix.
- Nepotvrzená měřicí obrazovka nepřežívá ukončení procesu; potvrzená otevřená relace ano.
- Zrušení závěrečného měření zahodí provizorní konec intervalu. Při dalším pokusu
  se konec intervalu stanoví nově. V rámci otevřené relace lze pokračovat v focení.
- Relace zahájená alpha2 a dokončená alpha3 vyžaduje ruční výběr fotografie.
- Změna hodin, boot epochy, nejednoznačný EXIF nebo chybějící EXIF ruší automatický výběr.
- Room je verze 2; JSON má aditivní pole. Starší přísné čtečky mohou nový export odmítnout.
- Zůstávají předchozí ochrany/omezení pro Motion Photo, MPF, extended XMP a velké JPEG.
- MediaStore nepodporuje atomický přepis celé fotografie. Původní durable backup
  a obnova z alpha2 zůstávají nutné a nesmějí být odstraněny před ověřením výsledku.
- Živý GNSS Samsungu, skutečný upgrade s daty, změny oprávnění při zápisu, plný disk,
  přerušení procesu v reálném MediaStore a upload do skutečného Drivu nejsou
  nahrazeny Robolectric simulací. Vyžadují ruční kontrolu podle INSTALL.md.
