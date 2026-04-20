# Android-Modder

Rechtssichere MVP-Grundlage für einen Android-Modding-Workflow mit Fokus auf **Datei-Import/Export**, **Extension-Struktur** und einem **kuratierten App-Katalog**. Die App ist die **Hülle** – Mods und Extensions werden von der Community erstellt und bereitgestellt.

## Enthaltene Grundlagen

- Verzeichnis-Konzept (beim ersten Start auswählbar): `<workspace>` (das vom Nutzer gewählte Hauptverzeichnis)
- App-spezifische Save-Ablage: `<workspace>/<app-name>/`
- `Cheats.json`-Parsing für save-bezogene Metadaten (z. B. Save-Adresse)
- Erweiterungs-Erkennung über `*.extension` (z. B. `MergeDragons.extension`)
- APK-Export/Entpack-Basis (ZIP-sicher, ohne Schutzmaßnahmen zu umgehen)
- `Mods/SamplePatch.cs` – Interface-Vorlage für eigene Mods

## Mods & Extensions – Community-Inhalte

Die App liefert **keine** Mods oder Extensions mit. Jeder kann eigene Mods und Extension-Dateien für ein Spiel erstellen. Sie werden einfach in das beim Start gewählte Arbeitsverzeichnis gelegt:

```
<workspace>/
  MergeDragons.extension    ← Extension-Datei (von der Community)
  MyCoolMod.mod             ← Mod-Datei (von der Community)
  MergeDragons/
    savegame.dat            ← Save-Datei
```

Die App erkennt diese Dateien automatisch beim Start über `listExtensions()` und `listMods()`.

### Extension-Interface

```csharp
// Mods/SamplePatch.cs – Interface für eigene Patches
public interface IGamePatch {
    string GameId { get; }
    void Apply(string workspaceRoot);
}
```

## i18n – Mehrsprachigkeit

Die App unterstützt Internationalisierung über Java `ResourceBundle`. Verfügbare Sprachen:

| Datei | Sprache |
|-------|---------|
| `messages_de.properties` | Deutsch |
| `messages_en.properties` | Englisch |

Die Systemsprache wird automatisch erkannt. Fallback ist Englisch.

```kotlin
val i18n = I18nService()                   // Systemsprache
val i18n = I18nService(Locale.ENGLISH)     // erzwungen Englisch
println(i18n.get("app.title"))             // "Android-Modder"
println(i18n.format("app.catalog.title", 14, 9, 10))
```

Neue Sprachen: einfach `messages_<locale>.properties` in `src/main/resources/` ablegen.

## App-Katalog

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

## Wichtige Grenzen

Dieses Projekt enthält **keine** Funktion zum Umgehen von Schutzmechanismen (inkl. Family Link), kein unerlaubtes Entschlüsseln geschützter Inhalte und keine Manipulation fremder APKs. Der Workflow ist auf legale Nutzung mit offiziellen Store-Apps und auf benutzerseitige Datenverwaltung ausgelegt.

## Lokaler Start

```bash
./gradlew test
./gradlew run --args="/pfad/zu/<workspace>"
```
