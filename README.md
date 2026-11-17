# MiriquidiHub 📱

Eine native Android-App mit praktischen Diagnose- und Hilfswerkzeugen für Android-Geräte, gebündelt an einem Ort.

![Android](https://img.shields.io/badge/Android-24%2B-green.svg)
![Kotlin](https://img.shields.io/badge/Kotlin-100%25-purple.svg)
![License](https://img.shields.io/badge/License-GPL%20v3-blue.svg)

## 📋 Über die App

MiriquidiHub bündelt Werkzeuge zur Diagnose und Dokumentation von Android-Geräten:

- 🏠 **Hub Startseite** - Schneller Zugriff auf alle Werkzeuge
- 🔍 **Geräte-Report** - Hardware/Software Spezifikationen auslesen und als PDF exportieren
- 🛠️ **Hardware-Selbsttest** - Geführte und automatische Tests der Gerätehardware
- 📶 **Netzwerk-Diagnose** - Verbindung, DNS und Erreichbarkeit öffentlicher Testhosts prüfen
- 📸 **Screenshots** - Einfacher Screenshot und Long Screenshot (Scrollaufnahme)
- 💾 **Speicherbelegung** - Analysetool für die Speicherbelegung und App-Caches
- 📍 **Ortung** - Standortfreigabe über einen eigenen WebDAV-Speicher, optional Ende-zu-Ende-verschlüsselt
- 🧰 **Werkzeuge** - Akku-Monitor, Live-Monitor, Sensor-Explorer, Sicherheitscheck, Berechtigungsübersicht, App-Inventar, WLAN-Analyse, Fehlerbericht-Assistent, Protokoll-Export, Berichtsvergleich
- ⚡ **Schnellzugriff** - Kacheln für die Schnelleinstellungen, Status-Widget, App-Shortcuts


## ✨ Features

- ✅ **Ohne Google-Dienste** - Keine Abhängigkeit von Google Play Services, kein Tracking
- 🌑 **True Black Dark Mode** - Optimiert für OLED-Displays (Schwarz/Grün Design)
- 📄 **PDF Export** - Erstellung von Support-Berichten inkl. Notizen und Foto-Anhängen
- 📋 **Spec Copy** - Schnelles Kopieren von Geräte-Informationen in die Zwischenablage
- 🛠️ **Hardware-Selbsttest** - 20 Tests (Touchscreen, Display, Lautsprecher, Mikrofon, Sensoren, Akku, GPS, Bluetooth- und WLAN-Suche u. a.) mit Übernahme der Ergebnisse in den Geräte-Report
- 📶 **Netzwerk-Diagnose** - WLAN-/Mobilfunk-Details sowie DNS-, TCP- und HTTPS-Tests mit Hinweisen zur Fehlerursache, IPv6, DNS-Server-Vergleich, Durchsatztest, eigene Testhosts und Verlauf
- 🗂️ **Berichtsarchiv** - Gespeicherte Berichte als ZIP exportieren und importieren, zwei Berichte vergleichen
- 🌍 **Mehrsprachig** - App-Oberfläche in Deutsch und Englisch (umschaltbar in der App)


## 🛠️ Technologie-Stack

- **Sprache:** Kotlin
- **Min SDK:** 24 (Android 7.0)
- **Target SDK:** 36 (Android 16 DP)
- **Build-System:** Gradle (KTS)
- **UI:** Material 3 mit ViewBinding
- **Asynchronität:** Kotlin Coroutines

## 📦 Installation

### Aus den Releases

1. Lade die neueste APK aus den [Releases](https://github.com/tux4us/MiriquidiHub/releases) herunter
2. Aktiviere "Installation aus unbekannten Quellen" in den Android-Einstellungen
3. Installiere die APK

### Selbst kompilieren
```bash
# Repository klonen
git clone https://github.com/tux4us/MiriquidiHub.git
cd MiriquidiHub

# In Android Studio öffnen und Build ausführen
```

## 🏗️ Projekt-Struktur
```
app/src/main/
├── java/io/github/tux4us/miriquidihub/
│   ├── StartActivity.kt         # Hub-Einstieg
│   ├── DeviceReportActivity.kt  # System-Specs & PDF Export
│   ├── HardwareTestActivity.kt  # Hardware-Selbsttest (Oberfläche und Ablauf)
│   ├── HardwareTester.kt        # Testlogik: Sensoren, Akku, Audio, Vibration, GPS
│   ├── HardwareTestModel.kt     # Testliste, Status und lokale Ergebnisspeicherung
│   ├── TouchGridView.kt         # Vollbild-Raster für den Touchscreen-Test
│   ├── NetworkDiagnosticActivity.kt # Netzwerk-Diagnose (Oberfläche)
│   ├── NetworkDiagnostics.kt    # Netzwerk-Diagnose (Verbindung, DNS, TCP, HTTPS)
│   └── ...                      # weitere Activities, Services und Adapter
├── res/
│   ├── layout/                  # Material 3 XML-Layouts
│   ├── menu/                    # Toolbar-Definitionen
│   ├── values/                  # Strings & Light Theme
│   ├── values-night/            # True Black Theme (#000000)
│   └── xml/                     # Backup & Network Security Config
└── AndroidManifest.xml
```

## 🎨 Features im Detail

### Hub
- Startseite mit direktem Zugriff auf alle Werkzeuge.
- Umschaltbares Design (hell/dunkel) und Sprache (Deutsch/Englisch) über das Menü.

### Geräte-Report
- Liest Hersteller, Modell, Hardware, Android-Version und Build-Fingerprint aus.
- Erlaubt das Hinzufügen von Notizen und Galerie-Fotos für Support-Anfragen.
- Übernimmt vorhandene Ergebnisse des Hardware-Selbsttests als eigenen Abschnitt `HARDWARE-SELBSTTEST` in Text- und PDF-Bericht.
- Exportiert einen formatierten PDF-Bericht nach `/Documents/MiriquidiHub/`.
- Optimierte Share-Funktion für Messenger und E-Mail (Auto-Caption Support).

### Hardware-Selbsttest
Der Selbsttest prüft die Gerätehardware und eignet sich zur Eingrenzung von Fehlern vor einer Support-Anfrage.

**Automatische Tests**
- **Funkmodule und Ausstattung:** WLAN, Bluetooth, Bluetooth LE, NFC, GPS, Mobilfunk, Fingerabdrucksensor, USB-Host, Kameras und Blitz (vorhanden / ein- bzw. ausgeschaltet).
- **Sensoren:** Beschleunigungssensor, Gyroskop, Magnetometer, Annäherungs-, Licht- und Drucksensor. Geprüft wird, ob vorhandene Sensoren tatsächlich Messwerte liefern.
- **Akku:** Ladestand, Zustand, Temperatur, Spannung, Ladezustand, Anschlussart und (ab Android 14) Ladezyklen.

**Tests mit Nutzerinteraktion** (zusätzlich zu den unten genannten: erweiterte Display-Muster, Kompass, Lichtsensor, Annäherungssensor, WLAN-Suche, Bluetooth-Suche, Audio-Klinke, biometrischer Sensor)
- **Touchscreen:** Vollbild-Raster; erkennt nicht reagierende Bereiche und zählt Multitouch-Punkte.
- **Display-Farben:** Rot, Grün, Blau, Weiß und Schwarz zur Erkennung defekter Pixel und Verfärbungen.
- **Lautsprecher:** Testton über den Medienkanal.
- **Mikrofon:** Pegelmessung über drei Sekunden.
- **Vibration:** Zwei Vibrationsimpulse.
- **Lautstärketasten:** Erkennung beider Tasten, mit Möglichkeit, eine Taste als defekt zu melden.
- **Kamera:** Auflistung aller Kameras (Ausrichtung, Auflösung, Blitz) und Testfoto über die System-Kamera-App.
- **Ladeanschluss:** Erkennung eines Ladekabels oder einer Ladematte innerhalb von 30 Sekunden.
- **GPS-Fix:** Zeit bis zum ersten Fix, Genauigkeit und Satellitenanzahl (Zeitlimit 60 Sekunden, am besten im Freien).

**Ergebnisse und Datenschutz**
- Die Ergebnisse werden ausschließlich lokal gespeichert und können kopiert, geteilt oder zurückgesetzt werden.
- Mikrofonaufnahmen werden nur im Arbeitsspeicher auf den Pegel ausgewertet und nicht gespeichert.
- Vom GPS-Test werden keine Koordinaten übernommen, nur Zeit, Genauigkeit und Satellitenanzahl.
- Das Testfoto der Kamera wird von der System-Kamera-App aufgenommen. MiriquidiHub deklariert die Berechtigung `CAMERA` nicht und speichert das Foto nicht.
- Berechtigungen für Mikrofon (`RECORD_AUDIO`) und Standort (`ACCESS_FINE_LOCATION`) werden erst beim jeweiligen Test zur Laufzeit angefragt. `VIBRATE` ist eine normale Berechtigung ohne Abfrage.

### Netzwerk-Diagnose
Die Diagnose zeigt Verbindungsstatus und Konfiguration und prüft die Erreichbarkeit öffentlicher Testhosts.

- **Verbindung:** Flugmodus, Verbindungsart (WLAN, Mobilfunk, Ethernet, Bluetooth, VPN), von Android bestätigter Internetzugang, Captive Portal, getaktete Verbindung, Datensparmodus und Bandbreite (Systemschätzung).
- **Netzwerkkonfiguration:** Schnittstelle, IP-Protokolle (IPv4/IPv6), DNS-Server, Privater DNS, MTU und HTTP-Proxy.
- **WLAN:** Signalstärke, Frequenzband, Verbindungsgeschwindigkeit und WLAN-Standard.
- **Mobilfunk:** SIM-Status, Betreiber und Roaming. Mit der optionalen Berechtigung `READ_PHONE_STATE` zusätzlich Netztyp (2G/3G/4G/5G), Signalstärke und Status der mobilen Daten.
- **Erreichbarkeit:** Für `f-droid.org`, `github.com`, `wikipedia.org` und `mozilla.org` werden DNS-Auflösung, TCP-Verbindungsaufbau auf Port 443 (drei Messungen mit min/Ø/max und Verlust) sowie eine HTTPS-Abfrage (Statuscode, Protokoll, TLS-Version) durchgeführt.
- **Hinweise:** Regelbasierte Textempfehlungen, z. B. bei Captive Portal, ausgefallenem DNS, TLS-Fehlern, ausgeschalteten mobilen Daten oder Verbindungsabbrüchen.
- Das Ergebnis lässt sich als Klartext kopieren oder teilen (z. B. für Support-Anfragen).

**Hinweise zu Datenschutz und Grenzen**
- Standardmäßig kontaktieren die Tests die oben genannten Hosts. Die Liste ist in der App änderbar (Menü „Testhosts bearbeiten“); eingetragen werden nur Hostnamen. Es werden keine Google-Dienste verwendet.
- Der DNS-Server-Vergleich fragt ausschließlich die vom System für das aktive Netzwerk konfigurierten DNS-Server ab (Antwortzeit und Ergebnis je Server).
- Der Durchsatztest lädt von einer selbst eingegebenen HTTPS-Adresse; es gibt keine voreingestellte Adresse. Das Ergebnis ist ein Richtwert und hängt von Server und Strecke ab.
- Der Verlauf früherer Läufe wird ausschließlich lokal gespeichert und kann gelöscht werden.
- SSID, BSSID und eigene IP-Adressen werden weder angezeigt noch geteilt; von den IP-Adressen wird nur die Protokollfamilie (IPv4/IPv6) ausgewertet.
- Ein ICMP-Ping ist ohne Root nicht zuverlässig möglich. Die Latenz wird daher als Dauer des TCP-Verbindungsaufbaus gemessen.
- Der VoLTE-/VoWiFi-Status ist für normale Apps nicht auslesbar und daher nicht Teil der Diagnose.
- `READ_PHONE_STATE` wird nur auf Nutzeraktion zur Laufzeit angefragt; ohne die Berechtigung bleiben die übrigen Prüfungen nutzbar. Rufnummer und Gerätekennungen werden nicht gelesen.

### Speicher
- **Belegung:** Aufteilung des Gerätespeichers und App-Caches. Ein Tipp auf eine App öffnet deren Systemeinstellungen.
- **Größte Dateien, Duplikate und Filter:** Duplikate werden über gleiche Dateigröße, einen Teil-Hash und abschließend den vollständigen SHA-256-Hash erkannt.
- **Papierkorb:** Gelöschte Dateien werden zunächst in den Ordner `.MiriquidiTrash` im gemeinsamen Speicher verschoben (ohne Kopieren, mit `.nomedia`) und können wiederhergestellt werden; endgültig gelöscht wird erst beim Leeren.
- **Verlauf:** Lokal gespeicherte Belegung über die Zeit.
- Erfordert `MANAGE_EXTERNAL_STORAGE` und `PACKAGE_USAGE_STATS`; beide werden über die Systemeinstellungen erteilt.

### Screenshots
- Einfacher Screenshot mit optionaler Verzögerung (Countdown); die Aufnahme startet erst nach Ablauf der Verzögerung.
- Long Screenshot über den Bedienungshilfen-Dienst (automatisches Scrollen).
- **Editor:** Bereiche vollständig schwärzen (deckendes Schwarz, nicht umkehrbar) und zuschneiden, mit Rückgängig. Pixelung ist bewusst nicht enthalten, da sie sich teilweise rekonstruieren lässt.
- Auf Android 13 und neuer wird für den Hinweis „zum Bearbeiten tippen“ die Berechtigung `POST_NOTIFICATIONS` benötigt.

### Ortung
- Position wird in eine Datei auf dem eigenen WebDAV-Speicher geschrieben; Intervall (30 s bis 5 min) und Zeitfenster (auch über Mitternacht) sind einstellbar. Außerhalb des Zeitfensters wird die eigene Datei auf dem Server gelöscht.
- **Verschlüsselung (optional):** AES-256-GCM, Schlüssel per PBKDF2-HMAC-SHA256 (100.000 Iterationen) aus einer Passphrase. Ohne dieselbe Passphrase auf dem Empfängergerät werden Positionen nicht lesbar angezeigt.
- WebDAV-Zugangsdaten und Passphrase werden mit dem Android Keystore verschlüsselt gespeichert.

### Werkzeuge
Die Karte „Werkzeuge“ auf der Startseite öffnet eine Übersicht aller folgenden Funktionen.

- **Akku-Monitor:** Ladestand und Temperatur als Verlauf, Verbrauch und Ladegeschwindigkeit in Prozentpunkten pro Stunde. Aufgezeichnet wird nur, solange der Bildschirm geöffnet ist; es gibt keinen Hintergrunddienst.
- **Live-Monitor:** Arbeitsspeicher, Download/Upload (TrafficStats, gesamtes Gerät) und thermischer Zustand, Messung alle 2 Sekunden bei sichtbarem Bildschirm. Der CPU-Takt ist für Apps nicht lesbar und wird daher nicht angezeigt.
- **Sensor-Explorer:** Alle Sensoren mit Typ, Hersteller, Maximalwert, Auflösung und Stromverbrauch sowie Live-Werten.
- **Sicherheitscheck:** Patchstand, Verified Boot, Bootloader-Status, SELinux, Bildschirmsperre, Bildschirm-Timeout, USB-Debugging, Entwickleroptionen und aktive Bedienungshilfen-Dienste. Es werden nur Einstellungen gelesen; nicht lesbare Werte werden nicht bewertet. Das Ergebnis ist ein Hinweis und keine vollständige Sicherheitsanalyse.
- **Berechtigungsübersicht:** Zeigt je sensibler Laufzeit-Berechtigung, welche Apps sie erhalten haben, mit Sprung in die App-Einstellungen.
- **App-Inventar:** Version, Zielversion, Installationsquelle und Kurzform des Signatur-Fingerabdrucks (SHA-256) je App.
- **WLAN-Analyse:** Kanalbelegung im 2,4- und 5-GHz-Band mit Kanalempfehlung und Liste der sichtbaren Netze. Kopierter oder geteilter Text enthält nur die Kanalstatistik, keine SSIDs oder BSSIDs. Android begrenzt die Häufigkeit von WLAN-Suchen.
- **Fehlerbericht-Assistent:** Erzeugt einen Markdown-Fehlerbericht (Beschreibung, Schritte, erwartetes und tatsächliches Verhalten, optional Geräte-, Hardware- und Sicherheitsangaben) für GitHub oder GitLab. Es wird nichts gesendet.
- **Protokoll-Export:** Ohne Root gibt Android Apps nur die eigenen Protokolleinträge frei. Filter nach Pegel, Export als Datei, Kopieren und Teilen.
- **Berichtsvergleich:** Vergleicht zwei gespeicherte Geräteberichte und zeigt geänderte, neue und entfernte Werte. Veränderliche Werte wie Akkustand oder Laufzeit weichen immer ab.

### Schnellzugriff
- **Kacheln** für die Schnelleinstellungen: Netzwerk-Diagnose, Screenshot, Sicherheitscheck.
- **Status-Widget:** Akkustand, freier Speicher und Arbeitsspeicher.
- **Shortcuts** (langer Druck auf das App-Symbol): Netzwerk-Diagnose, Screenshot, Sicherheitscheck.

### Berichtsarchiv
- Der Berichtsverlauf kann als ZIP-Datei exportiert (`reports.json` und `images/`) und wieder importiert werden. Beim Import werden Berichte anhand ihrer ID zusammengeführt, vorhandene Berichte bleiben erhalten.
- Das Archiv enthält die angehängten Fotos. Der Text- bzw. Markdown-Export enthält sie nicht.

## 🔐 Berechtigungen

| Berechtigung | Zweck |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` | Netzwerk-Diagnose, Karte, WebDAV |
| `READ_PHONE_STATE` (optional) | Netztyp und Signalstärke |
| `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` | GPS-Test, Ortung, WLAN-Suche |
| `NEARBY_WIFI_DEVICES` (`neverForLocation`), `BLUETOOTH_SCAN` (`neverForLocation`), `BLUETOOTH`/`BLUETOOTH_ADMIN` (bis Android 11) | WLAN- und Bluetooth-Suche |
| `RECORD_AUDIO` | Mikrofontest (Auswertung nur im Arbeitsspeicher) |
| `USE_BIOMETRIC` | Test des biometrischen Sensors |
| `VIBRATE` | Vibrationstest |
| `POST_NOTIFICATIONS` | Hinweise bei Screenshot und Standortfreigabe |
| `FOREGROUND_SERVICE` (`LOCATION`, `MEDIA_PROJECTION`) | Standortfreigabe, Screenshot |
| `SYSTEM_ALERT_WINDOW` | Schaltfläche für Screenshots |
| Bedienungshilfen-Dienst | Scrollen beim Long Screenshot |
| `QUERY_ALL_PACKAGES` | App-Auswahl, Cache-Analyse, Berechtigungsübersicht, App-Inventar |
| `MANAGE_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE` (bis Android 9), Nutzungsstatistik | Speicher- und Cache-Analyse, Duplikate, Papierkorb |

Mehrere dieser Berechtigungen sind für Google Play eingeschränkt. MiriquidiHub wird über F-Droid und GitHub verteilt, nicht über Google Play. Funktionen, die Root benötigen (z. B. Traceroute, Zugriff auf das vollständige Systemprotokoll), sind bewusst nicht enthalten.

## ❤️ Entwicklung unterstützen

MiriquidiHub ist freie Software und bleibt frei von Werbung und Tracking. Wer die Entwicklung freiwillig unterstützen möchte, kann das über [Buy Me a Coffee](https://buymeacoffee.com/tux4us) tun. In der App gibt es dafür die Karte „Entwickler unterstützen“ auf der Startseite sowie eine Schaltfläche im Dialog „Appinfo“. Der Dialog öffnet sich nur auf Nutzeraktion; die Spende läuft ausschließlich über den Browser, die App enthält kein Zahlungs-SDK.

## 🤝 Beitragen

Der Quellcode steht unter der GNU General Public License v3.0 (siehe Lizenz). Fehlerberichte und Verbesserungsvorschläge sind über [Issues](https://github.com/tux4us/MiriquidiHub/issues) willkommen. Code-Beiträge (Pull Requests) bitte vorher in einem Issue abstimmen. Eingereichte Beiträge werden unter derselben Lizenz (GPL-3.0) veröffentlicht.

## 📝 Lizenz

Dieses Projekt steht unter der **GNU General Public License v3.0** (GPL-3.0). Der Quellcode darf unter den Bedingungen der GPL-3.0 verwendet, verändert und weitergegeben werden; abgeleitete Werke müssen ebenfalls unter der GPL-3.0 mit Quellcode veröffentlicht werden. Der Lizenztext steht in [LICENSE](LICENSE).

Verwendete Bibliotheken von Dritten stehen unter ihren eigenen Lizenzen.

## 🙏 Danksagungen

- [tux4us](https://github.com/tux4us) für die Entwicklung.

## 📧 Kontakt

Bei Fragen oder Problemen:
- Öffne ein [Issue](https://github.com/tux4us/MiriquidiHub/issues)
- Kontaktiere mich über [tux4us@online.de]
