# Android-Modder

Rechtssichere MVP-Grundlage für einen Android-Modding-Workflow mit Fokus auf **Datei-Import/Export**, **Extension-Struktur** und einem **kuratierten App-Katalog**.

## Enthaltene Grundlagen

- Verzeichnis-Konzept (beim ersten Start auswählbar): `{{1}}` (das vom Nutzer gewählte Hauptverzeichnis)
- App-spezifische Save-Ablage: `{{1}}/{{app name}}/`
- `Cheats.json`-Parsing für save-bezogene Metadaten (z. B. Save-Adresse)
- Erweiterungs-Erkennung über `*.extension` (z. B. `MergeDragons.extension`)
- APK-Export/Entpack-Basis (ZIP-sicher, ohne Schutzmaßnahmen zu umgehen)
- `Mods/`-Ordner mit C#-Patch-Interface als Extension-Einstieg

## App-Katalog (neu)

Der kuratierte Katalog (`AppCatalog.json`) enthält altersgerechte Apps aus dem Play Store:

| Kategorie | App | Mindestalter |
|-----------|-----|-------------|
| Spiele | Merge Dragons! | 0+ |
| GApps | YouTube Kids | 0+ |
| GApps | Google Maps | 0+ |
| GApps | Google Chrome | 0+ |
| GApps | Google Drive | 0+ |
| GApps | Google Fotos | 0+ |
| GApps | Google Kalender | 0+ |
| GApps | Gmail | 6+ |
| GApps | YouTube | 12+ |
| Spiele | Minecraft | 6+ |
| Spiele | Subway Surfers | 0+ |
| Spiele | Clash of Clans | 12+ |
| Bildung | Duolingo | 0+ |
| Bildung | Khan Academy Kids | 0+ |

### Wie die Installation funktioniert

`AppCatalogService.playStoreUrl(entry)` liefert einen offiziellen Play-Store-Link:

```
https://play.google.com/store/apps/details?id=com.gram.mergedragons
```

Auf Android wird dieser per `Intent` geöffnet – Family Link greift wie gewohnt und kann die Installation genehmigen oder ablehnen. **Kein Schutz wird umgangen.**

```kotlin
val catalogService = AppCatalogService()
val allApps = catalogService.parse(catalogJson)

// Nur altersgerechte Apps anzeigen (z. B. für 10-Jährige)
val visibleApps = catalogService.filterByAge(allApps, userAge = 10)

// Play-Store-Link öffnen – Family Link bleibt aktiv
val url = catalogService.playStoreUrl(visibleApps.first())
```

## Wichtige Grenzen

Dieses Projekt enthält **keine** Funktion zum Umgehen von Schutzmechanismen (inkl. Family Link), kein unerlaubtes Entschlüsseln geschützter Inhalte und keine Manipulation fremder APKs. Der Workflow ist auf legale Nutzung mit offiziellen Store-Apps und auf benutzerseitige Datenverwaltung ausgelegt.

## Lokaler Start

```bash
./gradlew test
./gradlew run --args="/pfad/zu/{{1}}"
```
