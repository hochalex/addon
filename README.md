# Assistent – persönlicher Sprachassistent für Android

Ein persönlicher Alltagsassistent für das Pixel 8 Pro:

- behält **Mails** (Gmail + ein weiteres IMAP-Konto), **Termine** und **Todos** (Home Assistant) im Blick,
- schreibt **Antwortentwürfe** in deinem Stil, die du nur noch prüfst und freigibst,
- ist **per Sprache steuerbar, auch bei gesperrtem Handy** (Power-Taste lang drücken),
- meldet sich **proaktiv**: Morgen-Briefing, Erinnerungen, wichtige Mails, Todo- und Termin-Vorschläge aus Mails,
- steuert nebenbei das **Smart Home** über Home Assistant.

Die vollständigen Anforderungen stehen in [`docs/ANFORDERUNGEN.md`](docs/ANFORDERUNGEN.md).

## Was wohin geht (Datenschutz)

| Daten | Verarbeitung |
|---|---|
| Spracherkennung | on-device (Android-Spracherkennung, Deutsch) |
| Mail-Analyse (Kategorie, Wichtigkeit, Zusammenfassung, Todos/Termine) | **lokales Modell** (Gemma), falls importiert, sonst Claude API |
| Antwortentwürfe, Stil-Lernen, Sprachagent | Claude API |
| Mails von Absendern auf der **Datenschutzliste** | **nie in der Cloud**: Analyse und Entwürfe nur lokal, für den Sprachagenten unkenntlich gemacht, Vorlesen direkt am Handy |
| Passwörter, Tokens, API-Key | AES-verschlüsselt mit einem Schlüssel aus dem Android Keystore |
| Senden einer Mail | erst nach Fingerabdruck bzw. Entsperren |

## Installation

1. **APK holen:** Jeder Push baut über GitHub Actions eine APK. Lade sie unter *Releases* (`build-N`) direkt am Handy
   herunter und installiere sie. Dafür musst du einmal „Installation aus unbekannten Quellen“ für den Browser erlauben.
2. **Einmalig: festen Signaturschlüssel einrichten.** Ohne ihn lässt sich jede neue Version nur nach einer
   Deinstallation installieren:
   ```bash
   keytool -genkeypair -v -keystore assistent.jks -alias assistent -keyalg RSA -keysize 4096 -validity 36500
   base64 -w0 assistent.jks   # Ausgabe als Secret ASSISTENT_KEYSTORE_BASE64 speichern
   ```
   Im Repo unter *Settings → Secrets and variables → Actions* anlegen: `ASSISTENT_KEYSTORE_BASE64`,
   `ASSISTENT_KEYSTORE_PASSWORD`, `ASSISTENT_KEY_ALIAS` (= `assistent`), `ASSISTENT_KEY_PASSWORD`.
   Die `.jks`-Datei sicher aufbewahren und **nicht** committen.

## Einrichtung in der App (Tab „Einstellungen“)

1. **Gmail:** Unter <https://myaccount.google.com/apppasswords> ein App-Passwort erzeugen (2-Faktor-Anmeldung
   muss aktiv sein). Dann Adresse und App-Passwort eintragen, „Aktiv“ einschalten und „Verbindung testen“ tippen.
2. **Zweites Konto:** IMAP- und SMTP-Server samt Ports vom Anbieter eintragen (meist 993 / 465 oder 587).
3. **Home Assistant:** die HTTPS-URL deiner Domain und einen Long-Lived-Token eintragen (HA → Profil →
   Sicherheit → Langlebige Zugangstoken). „Verbindung testen“ zeigt alle Kalender- und Todo-Entities. Als
   Standard sind `todo.aufgaben` und `calendar.alex` gesetzt; bitte mit der Liste aus dem Test abgleichen.
4. **Claude:** API-Schlüssel von <https://console.anthropic.com> eintragen. Standardmodell ist `claude-opus-5-5`.
   Die Kosten liegen je nach Mailaufkommen grob bei wenigen Euro im Monat.
5. **Lokales Modell (empfohlen):** z.B. `gemma-3n-E2B-it-int4.task` von Hugging Face
   (`litert-community`, Lizenz akzeptieren) aufs Handy laden und mit „Modell importieren“ auswählen.
6. **Datenschutzliste:** Absender oder Domains eintragen, deren Mails nie an die Cloud gehen sollen.
7. **Speichern**, dann „Stil jetzt lernen“ tippen.
8. **Als Standard-Assistent festlegen:** Einstellungen → Apps → Standard-Apps → *Digitaler Assistent* →
   „Assistent“. Danach unter Einstellungen → System → Gesten → *Ein/Aus-Taste gedrückt halten* den
   digitalen Assistenten wählen.
9. **Offline-Spracherkennung:** Ist kein deutsches On-Device-Sprachpaket vorhanden, stößt die App den Download an
   und weicht bis dahin auf Googles Online-Erkennung aus.

## Bedienung per Sprache

Power-Taste lang drücken (auch auf dem Sperrbildschirm), dann z.B.:

- „Was steht heute an?“ / „Briefing“
- „Welche Termine habe ich am Freitag?“
- „Trag morgen um 15 Uhr Zahnarzt ein.“
- „Setz Milch auf die Einkaufsliste.“ / „Erinnere mich, die Versicherung anzurufen.“
- „Hab ich wichtige Mails?“ / „Lies mir die Mail von Iris vor.“
- „Antworte Iris, dass Freitag passt.“ → „Mach es kürzer.“ → „Senden.“ (das Senden verlangt Fingerabdruck oder Entsperren)
- „Schreib Thomas, dass ich mich verspäte.“
- „Rollos schließen“, „Sauna an“, „Musik in der Küche“
- „Danke“ beendet den Dialog.

## Ablauf im Hintergrund

- Etwa alle 15 Minuten (das Minimum von Android): neue Mails abrufen und analysieren. Bei Bedarf entsteht ein
  Entwurf, du bekommst eine Benachrichtigung mit **Senden / Bearbeiten / Verwerfen**, und erkannte Todos oder
  Termine werden als Vorschlag mit **Anlegen / Ignorieren** gemeldet.
- Termin-Erinnerungen kommen X Minuten vor Beginn, fällige Todos werden gemeldet.
- Das Morgen-Briefing kommt zur eingestellten Uhrzeit als Benachrichtigung mit „Vorlesen“.
- Einmal pro Woche wird der Schreibstil aus deinen gesendeten Mails aktualisiert.

## Entwicklung

```bash
./gradlew testDebugUnitTest lintDebug assembleRelease
```

Kotlin, Jetpack Compose, Room, WorkManager, offizielles Anthropic-Java-SDK, Jakarta/Android-Mail,
MediaPipe LLM Inference. minSdk 33, nur arm64.

| Paket | Inhalt |
|---|---|
| `voice/` | Standard-Assistent (VoiceInteractionService), Sprach-Activity über dem Sperrbildschirm, STT/TTS, Claude-Agent mit Werkzeugen, lokaler Fallback, Freigabe per Biometrie |
| `mail/` | IMAP-Abruf, SMTP-Versand, Entwurfs-Service |
| `ai/` | Claude-Client, lokales Gemma-Modell, Hybrid-Logik, Prompts |
| `ha/` | Home-Assistant-REST-Client (Kalender, Todos, Gesprächsagent) |
| `work/` | Hintergrund-Jobs: Mail-Sync, Erinnerungen, Briefing, Stil-Lernen |
| `notify/` | Benachrichtigungen und ihre Aktionen |
| `data/` | Einstellungen, verschlüsselter Speicher, Room-Datenbank |
| `ui/` | Übersicht, Entwürfe, Einstellungen, Entwurfsprüfung |
