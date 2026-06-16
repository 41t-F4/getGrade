# getGrade

Java-Tool, das sich auf `https://intranet.tam.ch/bmz` einloggt, das Notenbuch parsed und die Noten als CSV + JSON ablegt.

## Setup

1. Java 17+ und Gradle (oder den mitgelieferten Wrapper verwenden – siehe unten).
2. Credentials konfigurieren:

   ```bash
   cp config.properties.example config.properties
   # config.properties bearbeiten und tam.user / tam.password setzen
   ```

   `config.properties` ist via `.gitignore` ausgeschlossen – Credentials landen nicht im Repo.

## Ausführen

Mit Gradle:

```bash
gradle run
```

Oder ein Fat-Jar bauen und starten:

```bash
gradle jar
java -jar build/libs/getGrade-1.0.0.jar
```

Ergebnis: `grades.csv` und `grades.json` im Arbeitsverzeichnis.

## Wie es funktioniert

1. **GET** `https://intranet.tam.ch/bmz/` – Login-Seite laden, Cookies + Hidden-Form-Felder (`hash`, `loginschool`) extrahieren.
2. **POST** `https://intranet.tam.ch/bmz/` mit `hash`, `loginschool=bmz`, `loginuser`, `loginpassword`.
3. **GET** `https://intranet.tam.ch/bmz/default/gradebook/index` mit Session-Cookies.
4. HTML mit Jsoup parsen, Tabellenzeilen extrahieren (Fach-Header + Noten-Zeilen).
5. CSV (Excel-kompatibel, `;` als Separator) und JSON (gruppiert nach Fach) schreiben.

## Hinweise zum Parsing

Die Erkennung der Spalten basiert auf Heuristiken (was sieht aus wie eine Note 1–6, ein Datum, ein Gewicht). Falls TAM das Layout des Notenbuchs anpasst, muss `parseGrades()` in `GetGrade.java` angepasst werden. Tipp: einmal `gradebook.html` zwischenspeichern und an die Struktur anpassen.
