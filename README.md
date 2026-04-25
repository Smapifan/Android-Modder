# Android-Modder

Rechtssichere MVP-Grundlage für einen Android-Modding-Workflow. Die App ist die **Hülle** – sie ist Cheat-, Modding- und Launcher-Tool in einem. Mods und Extensions werden von der Community erstellt; das echte Spiel wird **niemals** gepatcht.

## Kernprinzip: Wrapper-Architektur – die APK bleibt unberührt

Android-Modder verändert **niemals** die installierte APK. Es gibt keine Smali-Injektion, kein Repackaging, keinen `pm uninstall` / `pm install`-Zyklus. Die originale Play-Store-Signatur bleibt erhalten.

Cheats und Mods wirken ausschließlich auf **Kopien der Save-Dateien** im lokalen Workspace:

1. **Export** – kopiert App-Daten vom Gerät in den Workspace
2. **Cheat/Mod anwenden** – ändert Felder in den Save-Dateien (z. B. `coins=500` → `coins=1500`)
3. **Import** – kopiert die bearbeiteten Daten zurück auf das Gerät

Das Spiel liest beim nächsten Start die (jetzt geänderten) Save-Dateien und verhält sich entsprechend – ohne dass die APK jemals angefasst wurde.

## Launcher-Zyklus

```
┌─────────────────────────────────────────────────────────────┐
│  1. PRE-LAUNCH                                              │
│     exportExternalData() → /sdcard/Android/data/<pkg>/      │
│     ✦ Alle Cheats aus cheats/*.json (passend zu pkg) anw.   │
│     ✦ Alle ON_LAUNCH *.mod-Dateien im Workspace anwenden    │
├─────────────────────────────────────────────────────────────┤
│  2. GAME LAUNCH                                             │
│     am start -n <pkg>/<Activity>                            │
│     Spiel läuft normal in seiner Sandbox, APK unverändert   │
│     Spiel liest die (jetzt geänderten) Save-Dateien         │
├─────────────────────────────────────────────────────────────┤
│  2b. OVERLAY SESSION (optional)                             │
│     ON_DEMAND-Buttons, ON_AUTOSAVE-Polling (overlay HUD)    │
│     RAM-Cheats über /proc/<pid>/mem (ProcessMemoryService)  │
├─────────────────────────────────────────────────────────────┤
│  3. POST-EXIT (importAfterExit = true)                      │
│     importExternalData() → zurück auf das Gerät             │
└─────────────────────────────────────────────────────────────┘
```

```kotlin
// Launcher erstellen – Cheats automatisch injiziert
val launcher = GameLauncherService(cheats = cheats)

// Spiel starten: Export → Cheats+Mods auto → am start → Import
launcher.launch(
    workspace = Path.of("/sdcard/AndroidModder/workspace"),
    config    = GameLaunchConfig(
        packageName   = "com.gram.mergedragons",
        launchCommand = "am start -n com.gram.mergedragons/.MainActivity"
    )
)
// → CoinCount wird automatisch um 1000 erhöht (aus cheats/*.json)
// → *.mod-Dateien im Workspace werden automatisch angewendet
// → Spiel startet und liest die geänderten Saves
// → APK ist identisch mit dem Original – keine Signaturveränderung
```

## Unterstützte Packages

**Alle Package-Namen** werden unterstützt – es gibt keine Sperrliste. Unbekannte Apps werden generisch behandelt. Der optionale kuratierte Katalog (`AppCatalog.json`) dient nur der UI-Darstellung (Name, Kategorie, Play-Store-Link), nicht als Zugangsbeschränkung.

## Speicherzugriff: Root vs. External Storage

| Pfad auf dem Gerät | Root nötig? | Strategie |
|---|---|---|
| `/data/data/<pkg>/` | ✅ **Ja** | `ROOT` oder `RUN_AS` (debuggable Apps) |
| `/sdcard/Android/data/<pkg>/` | ❌ **Nein** | `EXTERNAL_STORAGE` (Standard) |
| Live-Speicher `/proc/<pid>/mem` | ✅ **Ja** | `PROCESS_MEMORY` |

```
Gerät (extern, kein Root)
──────────────────────────────
/sdcard/Android/data/<pkg>/  ──►  <workspace>/<pkg>/external/<pkg>/

Gerät (intern, Root)
──────────────────────────────
/data/data/<pkg>/            ──►  <workspace>/<pkg>/internal/data/data/<pkg>/
```

## Sample Workspace

Das Repo enthält unter `sample_workspace/` realistische Beispiel-Save-Dateien:

| Spiel | Package | Save-Datei | Root? |
|---|---|---|---|
| Merge Dragons! | `com.gram.mergedragons` | `files/save.dat` | ✅ |
| Subway Surfers | `com.kiloo.subwaysurf` | `files/playerData.dat` | ✅ |
| Minecraft Bedrock | `com.mojang.minecraftpe` | `options.txt` (ext.) | ❌ |
| Clash of Clans | `com.supercell.clashofclans` | `files/sc.cfg` (local cache) | ✅ |
| Stardew Valley | `net.stardewvalley` | `files/saves/Player_*` | ✅ |

> **Hinweis zu Save-Formaten:** Mobile Games nutzen verschiedene Formate – JSON, XML, SQLite, binäre Daten. Der `CheatApplier` unterstützt derzeit das `key=value`-Format (ein Eintrag pro Zeile).

## Cheats

Cheats sind Wert-Operationen auf benannten Feldern einer Save-Datei:

| Operation | Beispiel | Ergebnis |
|-----------|----------|---------|
| `ADD`      | coins + 1000 | fügt 1 000 Coins hinzu |
| `SUBTRACT` | coins − 1000 | entfernt 1 000 Coins (min. 0) |
| `SET`      | gems = 9999  | setzt Gems auf exakt 9 999 |

`cheats/<package>.json` (von der Community erweiterbar):
```json
[
  { "appName": "com.gram.mergedragons", "field": "coins", "operation": "ADD", "amount": 1000 },
  { "appName": "com.gram.mergedragons", "field": "gems",  "operation": "SET", "amount": 9999 }
]
```

## RAM-Cheats (ProcessMemoryService)

Für Werte, die nie auf Disk geschrieben werden, steht `ProcessMemoryService` bereit:

```kotlin
// Wert im RAM des laufenden Spiels suchen und patchen
val svc = ProcessMemoryService()
val pid = svc.findPid("com.gram.mergedragons")!!
val result = svc.searchAndPatch(pid, currentValue = 500, newValue = 99999)
// PatchResult.PATCHED(address) → Wert sofort im Spiel sichtbar
```

Voraussetzung: Root oder ptrace-Capability. Die Strategie `PROCESS_MEMORY` im `GameLaunchConfig` aktiviert diesen Pfad automatisch im Launcher.

## Mods & Extensions – Community-Inhalte

Die App liefert **keine** Mods oder Extensions. Jeder kann eigene `.mod`-Dateien (JSON) für jedes Spiel erstellen. Sie kommen einfach ins Workspace-Verzeichnis:

```
<workspace>/
  InfiniteCoins.mod         ← Mod-Datei (Community)
  com.gram.mergedragons/
    external/               ← exportierte externe App-Daten
```

### Mod-Dateiformat (`.mod`)

```json
{
  "name": "InfiniteCoins",
  "gameId": "com.gram.mergedragons",
  "triggerMode": "ON_LAUNCH",
  "description": "Adds 10 000 coins and sets gems to 999",
  "patches": [
    { "field": "coins", "operation": "ADD", "amount": 10000 },
    { "field": "gems",  "operation": "SET", "amount": 999   }
  ]
}
```

| `triggerMode` | Wann |
|---|---|
| `ON_LAUNCH` | Einmalig vor dem Start (Standard) |
| `ON_DEMAND` | Bei Overlay-Button-Tap während das Spiel läuft |
| `ON_AUTOSAVE` | Automatisch alle 30 s während das Spiel läuft |


## Code-Patches (`*.codepatch`)

Zusätzlich zu Save-`*.mod`-Dateien unterstützt Android-Modder JSON Drop-in-Codepatches. Diese werden vor dem Start automatisch geladen und auf dekompilierte/source-Dateien im Workspace angewendet.

```json
{
  "name": "IncreaseDragonStarChance",
  "gameId": "com.gram.mergedragons",
  "targetFiles": ["com.gram.mergedragons/DragonRanch.Shared.cs"],
  "patches": [
    {
      "identifier": "K_CHANCE_OF_DRAGON_STAR",
      "newValue": "0.5",
      "expectedOldValue": "0.05"
    }
  ]
}
```

Wenn `targetFiles` leer ist, scannt der Loader standardmäßig den App-Workspace (`<workspace>/<gameId>/`) und nutzt optional `extensions` als Filter.

## CLI-Flags

```bash
# Codepatches für ein Package ausführen
./gradlew run --args="/pfad/workspace --patch-code --package=com.gram.mergedragons"

# RAM-Scan für int-Wert
./gradlew run --args="/pfad/workspace --ram-scan --package=com.gram.mergedragons --value=500"

# RAM-Analyse (Regionen + Multi-Type Treffer)
./gradlew run --args="/pfad/workspace --ram-analyze --package=com.gram.mergedragons --value=500"
```

## i18n – Mehrsprachigkeit

Die App unterstützt Internationalisierung über Java `ResourceBundle`. Verfügbare Sprachen:

| Datei | Sprache |
|-------|---------|
| `messages_de.properties` | Deutsch |
| `messages_en.properties` | Englisch |

```kotlin
val i18n = I18nService()                   // Systemsprache
val i18n = I18nService(Locale.ENGLISH)     // erzwungen Englisch
println(i18n.get("app.title"))             // "Android-Modder"
```

## App-Katalog

Der optionale Katalog (`AppCatalog.json`) ist eine informelle Liste bekannter Apps. Er dient der UI-Darstellung (Name, Label, Icon-Pfad, Kategorie, Play-Store-Link) und **schränkt keine Nutzung ein**.

```kotlin
val catalogService = AppCatalogService()
// Bekannte App aus Katalog oder generischen Eintrag erzeugen
val entry = catalogService.findOrGeneric(allApps, "com.unknown.game")
// → AppEntry(name="com.unknown.game", label="com.unknown.game", category="Unknown")
```

## Lokaler Start / APK-Build

```bash
# Debug-APK bauen
./gradlew assembleDebug

# Unit-Tests (Android Local Unit Tests)
./gradlew testDebugUnitTest

# APK liegt danach unter:
# build/outputs/apk/debug/app-debug.apk
```

## Build-Varianten (Android)

- **Debug**: für Entwicklung und lokale Tests (`assembleDebug`)
- **Release**: signierbare Produktions-Variante (`assembleRelease` + eigenes Signing)

```bash
./gradlew assembleDebug
./gradlew assembleRelease
```


## Sichere Branchnamen (wichtig für GitHub/Codex)

Wenn ein automatisch erzeugter Branch-Name zu lang ist oder Sonderzeichen wie `%2C` enthält,
kann das bei manchen Tools/Integrationen Probleme machen.

Nutze dafür den Helper:

```bash
./scripts/make-safe-branch-name.sh "Add .codepatch support, Android app build"
# -> codex/add-codepatch-support-android-app-build
```

Dann den Branch damit anlegen:

```bash
git checkout -b "$(./scripts/make-safe-branch-name.sh "<Titel>")"
```


## CI-Stabilität (GitHub Actions)

Der Workflow nutzt stabile Wrapper-Skripte unter `.github/scripts/`:

- `.github/scripts/ci-run-tests.sh` – erkennt automatisch `testDebugUnitTest` oder `test`
- `.github/scripts/ci-build-artifacts.sh` – baut bevorzugt APK (`assembleDebug`) mit Fallback
- `.github/scripts/gradle-retry.sh` – führt Gradle-Befehle mit Retry/Backoff aus

Damit werden temporäre Gradle-/Netz-Fehler in CI robuster abgefangen.

## Wichtige Grenzen

Dieses Projekt enthält **keine** Funktion zum Umgehen von Schutzmechanismen, kein APK-Patching und keine Manipulation fremder APKs. Der Workflow ist auf legale Nutzung mit offiziellen Store-Apps und auf benutzerseitige Datenverwaltung ausgelegt.

