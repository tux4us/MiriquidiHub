# MiriquidiHub 📱

Eine native Android-App mit praktischen Diagnose- und Hilfswerkzeugen für Android-Geräte, gebündelt an einem Ort.

![Android](https://img.shields.io/badge/Android-24%2B-green.svg)
![Kotlin](https://img.shields.io/badge/Kotlin-100%25-purple.svg)
![License](https://img.shields.io/badge/License-Read--Only-lightgrey.svg)

## 📋 Über die App

MiriquidiHub bündelt Werkzeuge zur Diagnose und Dokumentation von Android-Geräten:

- 🏠 **Hub Startseite** - Schneller Zugriff auf alle Werkzeuge
- 🔍 **Geräte-Report** - Hardware/Software Spezifikationen auslesen und als PDF exportieren
- 🛠️ **Hardware-Selbsttest** - Geführte und automatische Tests der Gerätehardware
- 📶 **Netzwerk-Diagnose** - Verbindung, DNS und Erreichbarkeit öffentlicher Testhosts prüfen
- 📸 **Screenshots** - Einfacher Screenshot und Long Screenshot (Scrollaufnahme)
- 💾 **Speicherbelegung** - Analysetool für die Speicherbelegung und App-Caches
- 📍 **Ortung** - Standortfreigabe über einen eigenen WebDAV-Speicher


## ✨ Features

- ✅ **Ohne Google-Dienste** - Keine Abhängigkeit von Google Play Services, kein Tracking
- 🌑 **True Black Dark Mode** - Optimiert für OLED-Displays (Schwarz/Grün Design)
- 📄 **PDF Export** - Erstellung von Support-Berichten inkl. Notizen und Foto-Anhängen
- 📋 **Spec Copy** - Schnelles Kopieren von Geräte-Informationen in die Zwischenablage
- 🛠️ **Hardware-Selbsttest** - 12 Tests (Touchscreen, Display, Lautsprecher, Mikrofon, Sensoren, Akku, GPS u. a.) mit Übernahme der Ergebnisse in den Geräte-Report
- 📶 **Netzwerk-Diagnose** - WLAN-/Mobilfunk-Details sowie DNS-, TCP- und HTTPS-Tests mit Hinweisen zur Fehlerursache
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

**Tests mit Nutzerinteraktion**
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
- Die Tests kontaktieren ausschließlich die oben genannten Hosts. Es werden keine Google-Dienste und keine zusätzlichen Bibliotheken verwendet.
- SSID, BSSID und eigene IP-Adressen werden weder angezeigt noch geteilt; von den IP-Adressen wird nur die Protokollfamilie (IPv4/IPv6) ausgewertet.
- Ein ICMP-Ping ist ohne Root nicht zuverlässig möglich. Die Latenz wird daher als Dauer des TCP-Verbindungsaufbaus gemessen.
- Der VoLTE-/VoWiFi-Status ist für normale Apps nicht auslesbar und daher nicht Teil der Diagnose.
- `READ_PHONE_STATE` wird nur auf Nutzeraktion zur Laufzeit angefragt; ohne die Berechtigung bleiben die übrigen Prüfungen nutzbar. Rufnummer und Gerätekennungen werden nicht gelesen.

## 🤝 Beitragen

Der Quellcode ist einsehbar, aber nicht zur Weiterverwendung freigegeben (siehe Lizenz). Fehlerberichte und Verbesserungsvorschläge sind über [Issues](https://github.com/tux4us/MiriquidiHub/issues) willkommen. Code-Beiträge (Pull Requests) bitte vorher in einem Issue abstimmen.

## 📝 Lizenz

Dieses Projekt steht unter der **MiriquidiHub Read-Only License**: Der Quellcode darf eingesehen werden, die offiziellen Releases dürfen privat genutzt werden. Kopieren, Verändern, Weitergeben und kommerzielle Nutzung sind nicht gestattet. Details: [LICENSE](LICENSE).

Verwendete Bibliotheken von Dritten stehen unter ihren eigenen Lizenzen.

## 🙏 Danksagungen

- [tux4us](https://github.com/tux4us) für die Entwicklung.

## 📧 Kontakt

Bei Fragen oder Problemen:
- Öffne ein [Issue](https://github.com/tux4us/MiriquidiHub/issues)
- Kontaktiere mich über [tux4us@online.de]
