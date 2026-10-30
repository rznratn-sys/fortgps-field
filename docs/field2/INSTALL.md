# Instalace Field 2.0.0-alpha1

Nativní Android aplikace pro Android 8.0+ (API 26+), balíček `cz.fortgps.field`.
Webový Field 1.1 zůstává samostatnou zálohou. Nepoužívá WebView ani PWA.

1. Stáhnout podepsaný APK dodaný s touto verzí. Na Samsungu povolit instalaci z
   daného zdroje, např. správce souborů. Google Play není potřeba.
2. Spustit FortGPS Field 2. Povolit polohu při používání; pro lepší zaměření zvolit
   přesnou polohu. Při přibližné poloze aplikace záznam neblokuje.
3. Nejprve provést krátký test venku: PRVNÍ KONTAKT → běžný fotoaparát → zpět do
   Fieldu → typ a poloha → ZAMĚŘIT OBJEKT A UZAVŘÍT.
4. V „Záznamy a záloha“ zkontrolovat oba body a exportovat zálohu JSON.
5. Drive připojit podle [OAUTH.md](OAUTH.md). Offline sběr na něm nezávisí.

První stisk bez oprávnění otevře systémovou žádost. Po udělení stisknout měření znovu.
Měření čeká nejvýše 35 s na nový fix od zahájení požadavku. Neexistuje podmínka
minimální přesnosti; uloží se skutečně dostupná udávaná přesnost. Když poloha není
dostupná, nevznikne vymyšlený bod. Již otevřená relace zůstane uložená.

Začátek a konec vymezují fotografickou relaci, nikoli stoprocentní identifikaci
fotografií. První kontakt je poloha fotografa; druhý bod geografická kotva objektu,
nikoli automaticky jeho střed. Rozestup bodů nemá pevný limit.

Pokud nelze bezpečně přistoupit, zvolit „Uzavřít bez bezpečného zaměření“. Relace
zůstane jako neúplné pozorování. Zrušení také zachová původní měření. Opravy typu a
poznámky v historii vytvoří novou revizi se stejným UUID relace.

Přes historii lze dobrovolně potvrdit fotografie v systémovém Photo Pickeru.
Fotografie se neuploadují a neotevírají pro zápis. Ukládá se SHA-256 dodaných bajtů,
velikost a původní název, nikoli absolutní cesta. Na starším Androidu použije AndroidX
systémový výběr dokumentů. Nevyžaduje se oprávnění ke všem fotografiím.

Před odinstalací, vymazáním dat aplikace nebo resetem telefonu udělat export JSON.
OS obvykle při těchto akcích smaže privátní databázi. Aplikace sama záznamy nemaže.
Záloha obsahuje aktuální revize všech relací včetně zrušených; přenosová historie a
OAuth tokeny se do ní neexportují. Import obnoví frontu, na Drivu hledá stejnou relaci
a revizi a ověřuje obsah před potvrzením. Souběžné používání stejné obnovené relace
na více zařízeních vyžaduje kontrolu konfliktů; nejde o víceuživatelský editor.
