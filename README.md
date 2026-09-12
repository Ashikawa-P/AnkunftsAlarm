# AnkunftsAlarm 2.2

AnkunftsAlarm ist eine native Android-App für Fahrten mit Bus und Bahn. Das Ziel wird unabhängig von einer Adresse direkt auf einer Karte gewählt. Danach wird ein Radius zwischen 100 und 3000 Metern festgelegt. Erst mit „Alarm starten“ beginnt die Standortüberwachung.

Während der Alarm aktiv ist, läuft ein Android-Foreground-Service mit sichtbarer Systembenachrichtigung weiter. Nutzer können wählen, ob die App ausschließlich den satellitenbasierten GNSS-Anbieter, ausschließlich die Netzwerkortung über WLAN und Mobilfunkmasten oder standardmäßig beide Quellen verwendet. Jeder Standort wird über die Haversine-Formel mit dem gespeicherten Ziel verglichen. Erst zwei frische Messungen hintereinander innerhalb des Radius lösen den Alarm aus, damit ein einzelner Positionssprung nicht genügt. Danach beendet die App die Überwachung und erzeugt eine hoch priorisierte Alarmbenachrichtigung. Alarmton und Vibration können unabhängig voneinander ein- oder ausgeschaltet werden; der sichtbare Hinweis bleibt immer aktiv.

## Voraussetzungen und Start

Das Projekt setzt Android 13 beziehungsweise API 33 als Mindestversion voraus, kompiliert gegen API 36 und benötigt JDK 17. Es kann direkt als Ordner in Android Studio geöffnet werden. Der Gradle-Build verwendet keine externe UI- oder Kartenbibliothek.

Beim ersten Start wird das Ziel durch Antippen der Karte oder durch die Suche nach einer Adresse beziehungsweise einem Ort ausgewählt und bestätigt. Die Adresssuche verwendet den asynchronen Geocoder des Android-Geräts und übernimmt aus dem Treffer nur Breiten- und Längengrad. Ein blauer Punkt zeigt den eigenen Standort; die Schaltfläche „Mein Standort“ zentriert die Karte darauf. Die anschließende Ansicht zeigt die Karte verkleinert, den Zielkreis, den Radiusregler, die Standortquellenauswahl sowie getrennte Schalter für Alarmton und Vibration. Die gewählte Standortquelle gilt sowohl für den blauen Kartenpunkt als auch für den Hintergrundalarm. Die Standortberechtigung wird für den eigenen Kartenpunkt benötigt, die Benachrichtigungsberechtigung erst beim Start des Alarms.

Die Karte lädt Rasterkacheln über HTTPS von OpenStreetMap und hält bereits geladene Kacheln in einem stabilen Speicher- und Dateicache. Fehlgeschlagene Kacheln werden mit einer Wartezeit erneut angefragt, damit leere Felder nicht in jedem Zeichenzyklus flackern. Ein API-Schlüssel ist nicht erforderlich. Wenn gerade keine Internetverbindung besteht, bleibt die Kartenfläche als verschiebbares Raster nutzbar; neue Kartenkacheln und die Adresssuche können dann jedoch nicht erscheinen.

## Reproduzierbarer Build

In Android Studio kann die normale Debug-Konfiguration des Moduls `app` gebaut werden. Alternativ erzeugt `build-local.sh` mit den Android Build Tools 36.1.0 und der Plattform 36 eine signierte Debug-APK ohne Gradle-Abhängigkeiten. Dafür muss `ANDROID_HOME` auf ein Android SDK mit diesen Komponenten zeigen.

```bash
ANDROID_HOME=/pfad/zum/android-sdk bash build-local.sh
bash test-local.sh
```

Die APK liegt anschließend unter `app/build/outputs/apk/debug/AnkunftsAlarm-v2.2.0-debug.apk`.

## Aufbau und Android-Grenzen

`MainActivity` steuert Ziel- und Adresssuche, aktuelle Kartenposition, Standortquelle, Radius, Alarmoptionen, Berechtigungen und Servicebefehle. `MapTileView` zeichnet Karte, Zielmarker, aktuellen Standort und metrischen Radius. `AppState` speichert Ziel, Standortquelle, Alarmoptionen und Alarmstatus. `LocationMonitorService` abonniert nur die gewählten Standortanbieter, bestätigt die Ankunft mit zwei frischen Innenmessungen und wählt je nach Alarmoption den passenden Benachrichtigungskanal. `DistanceCalculator` und `ArrivalConfirmation` enthalten die unabhängig getestete Entfernungs- und Auslöseberechnung.

Android kann Standortwerte in Tunneln, Gebäuden oder bei deaktivierten Standortdiensten verzögert liefern. Auch die Verfügbarkeit und Qualität der Adresssuche hängt vom auf dem Gerät eingerichteten Geocoder-Dienst ab. Ein Radius von 500 Metern ist deshalb als praxisnaher Standard gesetzt. „Stopp erzwingen“, deaktivierte Standortdienste oder aggressive herstellerspezifische Akkuverwaltung können auch einen Foreground-Service beenden. Ab Android 14 kann die Vollbildanzeige separat gesperrt sein; die normale hoch priorisierte Benachrichtigung bleibt davon unberührt, sofern Benachrichtigungen erlaubt sind. Systemseitige Änderungen an Benachrichtigungskanälen können die in der App gewählten Ton- und Vibrationseinstellungen übersteuern.
