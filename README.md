# Android-Modder

Rechtssichere MVP-Grundlage für einen Android-Modding-Workflow. Die App ist die **Hülle** – sie ist Cheat-, Modding- und Launcher-Tool in einem. Mods und Extensions werden von der Community erstellt; das echte Spiel wird **niemals** gepatcht.

## Konzept: file-basiert, keine Mod-API nötig

Cheats und Mods funktionieren **ohne externe Mod-API** und **ohne das Spiel zu verändern**. Alles läuft über die Save-Dateien im Workspace:

1. **Export** – kopiert `data/data/<appName>/` und `data/<appName>/` vom Gerät in den Workspace
2. **Cheat/Mod anwenden** – ändert Felder in den Save-Dateien (z. B. `coins=500` → `coins=1500`)
3. **Import** – kopiert die bearbeiteten Daten zurück

```
Gerät                         Workspace
─────────────────────         ─────────────────────────────────────
/data/data/<app>/   ──────►  <workspace>/<app>/data/data/<app>/
/data/<app>/        ──────►  <workspace>/<app>/data/<app>/
```

## Cheats

Cheats sind Wert-Operationen auf benannten Feldern einer Save-Datei:

| Operation | Beispiel | Ergebnis |
|-----------|----------|---------|
| `ADD`      | coins + 1000 | fügt 1 000 Coins hinzu |
| `SUBTRACT` | coins − 1000 | entfernt 1 000 Coins (min. 0) |
| `SET`      | gems = 9999  | setzt Gems auf exakt 9 999 |

`Cheats.json` (von der Community erweiterbar):
```json
[
  { "appName": "MergeDragons", "field": "coins", "operation": "ADD",      "amount": 1000 },
  { "appName": "MergeDragons", "field": "coins", "operation": "SUBTRACT", "amount": 1000 },
  { "appName": "MergeDragons", "field": "gems",  "operation": "SET",      "amount": 9999 }
]
```

```kotlin
// Cheat anwenden – kein Game-Patching, kein Mod-API nötig
val newValue = CheatApplier().apply(appWorkspaceDir, cheat)
// newValue = neue Coins-Anzahl
```

`CheatApplier` sucht das Feld automatisch rekursiv im Workspace – kein fixer Save-Pfad nötig.

## Mods & Extensions – Community-Inhalte

Die App liefert **keine** Mods oder Extensions. Jeder kann eigene `.mod`-Dateien (JSON) für jedes Spiel erstellen – keine API nötig. Sie kommen einfach ins Workspace-Verzeichnis:

```
<workspace>/
  MergeDragons.extension    ← Extension-Datei (Community)
  InfiniteCoins.mod         ← Mod-Datei (Community)
  com.gram.mergedragons/
    data/data/com.gram.mergedragons/   ← exportierte App-Daten
```

### Mod-Dateiformat (`.mod`)

```json
{
  "name": "InfiniteCoins",
  "gameId": "MergeDragons",
  "description": "Adds 10 000 coins and sets gems to 999",
  "patches": [
    { "field": "coins", "operation": "ADD", "amount": 10000 },
    { "field": "gems",  "operation": "SET", "amount": 999   }
  ]
}
```

```kotlin
val mod = ModLoader().load(Path.of("InfiniteCoins.mod"))
ModLoader().applyMod(mod, appWorkspaceDir)
```

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
