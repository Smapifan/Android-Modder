# Android-Modder

Rechtssichere MVP-Grundlage für einen Android-Modding-Workflow. Die App ist die **Hülle** – sie ist Cheat-, Modding- und Launcher-Tool in einem. Mods und Extensions werden von der Community erstellt; das echte Spiel wird **niemals** gepatcht.

## Konzept: file-basiert, keine Mod-API nötig

Cheats und Mods funktionieren **ohne externe Mod-API** und **ohne das Spiel zu verändern**. Alles läuft über die App-Daten im Workspace:

1. **Export** – kopiert App-Daten vom Gerät in den Workspace
2. **Cheat/Mod anwenden** – ändert Felder in den Save-Dateien (z. B. `coins=500` → `coins=1500`)
3. **Import** – kopiert die bearbeiteten Daten zurück

## Launcher-Zyklus

Android-Modder ist der Launcher. Der Nutzer startet das Spiel nicht direkt, sondern über diese App. **Cheats und Mods werden automatisch angewendet** – kein manuelles Verdrahten nötig:

```
┌─────────────────────────────────────────────────────────────┐
│  1. PRE-LAUNCH                                              │
│     exportAppData()  → /data/data/<pkg>/  (Root)            │
│     exportExternalData() → /sdcard/Android/data/<pkg>/      │
│     ✦ Alle Cheats aus Cheats.json (passend zu pkg) anwenden │
│     ✦ Alle *.mod-Dateien im Workspace (passend zu pkg) anw. │
├─────────────────────────────────────────────────────────────┤
│  2. GAME LAUNCH                                             │
│     am start -n <pkg>/<Activity>                            │
│     Spiel läuft normal in seiner Sandbox, unverändert       │
│     Spiel liest die (jetzt geänderten) Save-Dateien         │
├─────────────────────────────────────────────────────────────┤
│  3. POST-EXIT                                               │
│     importAppData()  → zurück auf das Gerät                 │
└─────────────────────────────────────────────────────────────┘
```

```kotlin
// Cheats aus Cheats.json laden
val cheats = CheatsConfigParser().parse(File("Cheats.json").readText())

// Launcher erstellen – Cheats automatisch injiziert
val launcher = GameLauncherService(cheats = cheats)

// Spiel starten: Export → Cheats+Mods auto → am start → Import
launcher.launch(
    workspace = Path.of("/sdcard/AndroidModder/workspace"),
    config    = GameLaunchConfig(
        packageName   = "com.gram.mergedragons",
        launchCommand = "am start -n com.gram.mergedragons/.MainActivity",
        useRootForData = true
    )
)
// → CoinCount wird automatisch um 1000 erhöht (aus Cheats.json)
// → *.mod-Dateien im Workspace werden automatisch angewendet
// → Spiel startet und liest die geänderten Saves
```

## Speicherzugriff: Root vs. External Storage

| Pfad auf dem Gerät | Root nötig? | Methode |
|---|---|---|
| `/data/data/<pkg>/` | ✅ **Ja** | `exportAppData()` / `importAppData()` |
| `/data/<pkg>/` | ✅ **Ja** | `exportAppData()` / `importAppData()` |
| `/sdcard/Android/data/<pkg>/` | ❌ **Nein** | `exportExternalData()` / `importExternalData()` |

```
Gerät (intern, Root)                    Workspace
────────────────────────────            ────────────────────────────────────────────
/data/data/<pkg>/          ──────►     <workspace>/<app>/internal/data/data/<pkg>/
/data/<pkg>/               ──────►     <workspace>/<app>/internal/data/<pkg>/

Gerät (extern, kein Root)
────────────────────────────
/sdcard/Android/data/<pkg>/ ─────►     <workspace>/<app>/external/<pkg>/
```

## Sample Workspace

Das Repo enthält unter `sample_workspace/` realistische Beispiel-Save-Dateien mit echten Feldnamen:

| Spiel | Package | Save-Datei | Root? |
|---|---|---|---|
| Merge Dragons! | `com.gram.mergedragons` | `files/save.dat` | ✅ |
| Subway Surfers | `com.kiloo.subwaysurf` | `files/playerData.dat` | ✅ |
| Minecraft Bedrock | `com.mojang.minecraftpe` | `options.txt` (ext.) | ❌ |
| Clash of Clans | `com.supercell.clashofclans` | `files/sc.cfg` (local cache) | ✅ |
| Stardew Valley | `net.stardewvalley` | `files/saves/Player_*` | ✅ |

> **Hinweis zu Save-Formaten:** Mobile Games nutzen verschiedene Formate – JSON, XML, SQLite, binäre Daten. Der `CheatApplier` unterstützt derzeit das `key=value`-Format (ein Eintrag pro Zeile). Die Sample-Saves in diesem Repo sind in diesem Format gehalten.

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

## Dev- vs. User-Build

- **USER** (Standard): keine Dev-Tools über CLI
- **DEV**: zusätzliche CLI-Operationen für Export/Unpack

Build-Channel setzen:

```bash
./gradlew run --args="/pfad/zu/workspace --build-channel=dev --dev-export-package=com.gram.mergedragons"
./gradlew run --args="/pfad/zu/workspace --build-channel=dev --dev-unpack-apk=/pfad/app.apk --dev-readable-index"
```

User-Build blockiert Dev-Operationen automatisch.

CI-Workflow: `.github/workflows/apk-build-dev-user.yml`
- Variant `dev` → `com.smapifan.androidmodder.dev`
- Variant `user` → `com.smapifan.androidmodder`
