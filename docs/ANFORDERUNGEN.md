# Persönlicher Assistent – Anforderungen (v1)

Stand: 2026-11-23 · Zielgerät: Pixel 8 Pro (Android 15+, 12 GB RAM, Tensor G3)

## 1. Ziel
Ein lokaler, sprachgesteuerter Alltagsassistent als Android-App, der Mails, Termine und Todos
im Blick behält, Antwortentwürfe selbst erstellt und diese erst nach Prüfung versendet.

## 2. Funktionale Anforderungen

### 2.1 Sprachsteuerung
- App registriert sich als **Standard-Assistent** (`VoiceInteractionService`, Rolle `ROLE_ASSISTANT`).
  Auslösung per Power-Taste lang / Assistenten-Geste – **auch bei gesperrtem Handy**.
- Spracherkennung: Android `SpeechRecognizer`, on-device, Deutsch.
- Ausgabe: **Sprache (Android-TTS, Deutsch) + Text-Overlay** über dem Sperrbildschirm.
- Rechte bei **gesperrtem** Gerät: alles **außer Senden** (vorlesen, zusammenfassen, Todos/Termine
  anlegen, Entwürfe diktieren/überarbeiten, Smart Home). **Senden** verlangt Entsperren / Fingerabdruck.

### 2.2 Mail
- Konten: **Gmail** und **ein weiteres IMAP/SMTP-Konto** (Zugangsdaten in den App-Einstellungen).
- Klassifizierung jeder neuen Mail: antwortbedürftig / wichtig / Info / Newsletter-Werbung.
- **Automatischer Antwortentwurf nur, wenn eine Antwort nötig ist.**
- **Schreibstil** wird aus den eigenen gesendeten Mails gelernt (Anrede, Du/Sie, Grußformel, Länge).
- Freigabe: Benachrichtigung „Entwurf an X bereit“ mit **Senden / Bearbeiten / Verwerfen**;
  Senden nur nach biometrischer Bestätigung. Nichts wird ohne Freigabe versendet.

### 2.3 Termine & Todos (über Home Assistant)
- Lesen: alle HA-Kalender (Alex, Familie, Iris, Geburtstage, Feiertage …) und HA-Todo-Listen
  (Aufgaben, Einkaufsliste, Leonding, Gstocket, Wohnmobil).
- Schreiben: neue Todos standardmäßig in **„Aufgaben“** (per Sprache andere Liste wählbar),
  neue Termine in Kalender **„Alex“**.
- Termin-/Todo-Vorschläge aus Mails (Fristen, Einladungen, Rechnungen) – Anlage erst nach Bestätigung.

### 2.4 Smart Home
- Freie Sprachbefehle werden an HA `conversation/process` weitergereicht
  („Rollos schließen“, „Sauna an“ …).

### 2.5 Proaktiv
- **Morgen-Briefing** (Uhrzeit einstellbar): Termine, fällige Todos, wichtige Mails, offene Entwürfe.
- **Erinnerungen** an Termine/Todos/Fristen.
- **Sofortmeldung** nur bei als wichtig eingestuften Mails.

## 3. KI-Architektur (Hybrid)
| Aufgabe | Engine |
|---|---|
| Klassifizierung, Zusammenfassung, Todo/Termin-Extraktion, Intent-Erkennung | **On-device** (Gemini Nano via AICore/ML Kit GenAI; Fallback Gemma 3n via LiteRT-LM) |
| Antwortentwürfe, Stil-Analyse | **Claude API** |
| Datenschutz-Schalter | Ausschlussliste (Absender/Domains), die **nie** an die Cloud geht – dafür lokaler Entwurf |

API-Schlüssel und Tokens liegen verschlüsselt im Android Keystore.

## 4. Infrastruktur
- Home Assistant per **HTTPS über eigene Domain** (statische IP), Zugriff mit Long-Lived-Token.
- Hintergrund: WorkManager (periodischer Abruf) + optional IMAP IDLE für schnelle Meldungen.
- Build: **GitHub Actions baut signierte APK**, Installation per Sideload.

## 5. Technik-Stack
Kotlin · Jetpack Compose · Room · Hilt · WorkManager · Ktor/OkHttp · Jakarta Mail (IMAP/SMTP) ·
minSdk 31 · targetSdk aktuell.

## 6. Umsetzungsphasen
1. **Grundgerüst**: Projekt, CI-Build, Einstellungen (Konten, HA, API-Key), verschlüsselter Speicher.
2. **Sprache**: Standard-Assistent, Sperrbildschirm-Overlay, STT/TTS, Intent-Routing, HA-Smart-Home.
3. **HA-Daten**: Kalender/Todos lesen + schreiben, Sprachabfragen („Was steht morgen an?“).
4. **Mail**: Abruf, Klassifizierung, Benachrichtigungen, Vorlesen.
5. **Entwürfe**: Stil-Lernen, Claude-Entwürfe, Freigabe-Workflow mit Biometrie, Versand.
6. **Proaktiv**: Morgen-Briefing, Erinnerungen, Vorschläge aus Mails.

## 7. Offene Punkte
- Gmail-Zugriff: Gmail-API (OAuth) vs. IMAP mit App-Passwort – siehe Entscheidung.
