# Známá omezení první alfy

- Úspěšná kompilace a emulované testy nenahrazují fyzický Samsung, terénní GPS,
  déšť, rukavice, Samsung úsporný režim ani skutečný Google OAuth consent.
- Autorizaci v Google Cloud nelze odvodit z fungujícího webového klienta. Android
  klient vyžaduje přesný balíček/certifikát. Přístup k původní složce musí být ověřen.
- Vestavěný Google Drive folder picker ani pomocný backend nejsou implementované.
  Existující složka se zadává ID/odkazem a REST přístup se ověřuje. Při 403/404 je
  potřeba dořešit grant v témže projektu. Aplikace neobchází scope.
- WorkManager neposkytuje okamžité ani časově garantované odeslání. Žádná background
  location, žádné nepřetržité sledování. Poloha se měří jen na výslovný stisk.
- Ukládá se první nový dostupný fix po požadavku. Není implementovaný geodetický
  průměr ani filtr zaručující skutečnou přesnost. Velká udávaná nejistota neblokuje bod.
- Poznámka/typ zadané při otevření jsou uložené; následné rozpracované změny formuláře
  se definitivně uloží až s ukončením relace. Samotné hotové záznamy jsou v DB.
- Neměnné revize zabírají místo i po potvrzeném uploadu. Čištění se automaticky
  neprovádí. Export zálohuje aktuální relace, ne kompletní historickou outbox tabulku.
- Dva nezávislé telefony současně upravující stejnou obnovenou relaci nemají řešení
  konfliktů ani distribuovaný zámek; serverová duplicita se musí prověřit.
- Po Photo Pickeru se počítá hash v otevřené aplikaci, pro velkou dávku to může
  chvíli trvat. Neexistuje automatický MediaStore scan časového intervalu ani
  automatická analýza EXIF. Originály JPG se nikdy nepřepisují.
- Hash může popisovat poskytovatelem dodanou/redigovanou kopii, ne bajty originálu.
  Přesný obsahový match s FortGPS Foto musí být ověřen až při importu.
- Spatial CLI funguje odděleně. Automatický Drive poller, webhook, přepnutí registru,
  automatické potvrzení identit a FortVision interpretace nejsou součástí této alfy.
- České pokrytí používá existující národní výpočetní obálku, která není státní hranice.
  Katalog musí vybrat obsluha; mimo doložené oblasti není náhradní párování.
- Nejsou mapy, DEM v telefonu, palebné sektory, výpočet sklonů či přesná orientace staveb.
