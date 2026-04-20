# Android-Modder

Rechtssichere MVP-Grundlage für einen Android-Modding-Workflow mit Fokus auf **Datei-Import/Export** und **Extension-Struktur**.

## Enthaltene Grundlagen

- Verzeichnis-Konzept (beim ersten Start auswählbar): `{{1}}` (das vom Nutzer gewählte Hauptverzeichnis)
- App-spezifische Save-Ablage: `{{1}}/{{app name}}/`
- `Cheats.json`-Parsing für save-bezogene Metadaten (z. B. Save-Adresse)
- Erweiterungs-Erkennung über `*.extension` (z. B. `MergeDragons.extension`)
- APK-Export/Entpack-Basis (ZIP-sicher, ohne Schutzmaßnahmen zu umgehen)
- `Mods/`-Ordner mit C#-Patch-Interface als Extension-Einstieg

## Wichtige Grenzen

Dieses Projekt enthält **keine** Funktion zum Umgehen von Schutzmechanismen, kein unerlaubtes Entschlüsseln geschützter Inhalte und keine Manipulation fremder APKs. Der Workflow ist auf legale Nutzung mit offiziellen Store-Apps und auf benutzerseitige Datenverwaltung ausgelegt.

## Lokaler Start

```bash
./gradlew test
./gradlew run --args="/pfad/zu/{{1}}"
```
