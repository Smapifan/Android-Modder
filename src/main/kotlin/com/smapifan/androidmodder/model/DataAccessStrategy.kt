// Enum defining how the launcher reads and writes an app's save data (external storage, run-as, root, memory, or virtual FS).
// Aufzählung, die festlegt, wie der Launcher App-Spielstanddaten liest und schreibt (externer Speicher, run-as, Root, Speicher oder virtuelles FS).

package com.smapifan.androidmodder.model

/**
 * Selects the mechanism used to read and write an app's save data.
 *
 * ## Strategy overview
 *
 * ```
 * ┌──────────────────┬──────────────────────────────────────────────────────┐
 * │ Strategy         │ How it works / when to use it                        │
 * ├──────────────────┼──────────────────────────────────────────────────────┤
 * │ EXTERNAL_STORAGE │ Reads/writes /sdcard/Android/data/<pkg>/             │
 * │                  │ ✅ No root, no special permission.                   │
 * │                  │ Works for any game that saves to external storage.   │
 * ├──────────────────┼──────────────────────────────────────────────────────┤
 * │ RUN_AS           │ Uses `run-as <pkg>` to access /data/data/<pkg>/      │
 * │                  │ ✅ No root required.                                 │
 * │                  │ ⚠  Only works when the target app is marked          │
 * │                  │    android:debuggable="true" (debug / beta builds).  │
 * │                  │ The launcher starts the game normally via `am start`;│
 * │                  │ `run-as` is only used for the data copy steps.       │
 * ├──────────────────┼──────────────────────────────────────────────────────┤
 * │ ROOT             │ Uses `su -c "cp …"` to access /data/data/<pkg>/      │
 * │                  │ ⚠  Requires a rooted device (Magisk / SuperSU).     │
 * │                  │ Works for any app regardless of debuggable flag.     │
 * ├──────────────────┼──────────────────────────────────────────────────────┤
 * │ PROCESS_MEMORY   │ Reads/writes the game's live memory via              │
 * │                  │ /proc/<pid>/mem while the game is running.           │
 * │                  │ ⚠  Requires root (or ptrace capability).            │
 * │                  │ Allows patching values that are held in RAM and      │
 * │                  │ never written to an on-disk save file.               │
 * │                  │ Changes are immediate – the game "sees" new values   │
 * │                  │ without any restart or save cycle.                   │
 * └──────────────────┴──────────────────────────────────────────────────────┘
 * ```
 *
 * ## No-root note
 *
 * The game is **always** started via `am start` in its own normal sandbox with
 * its real user-ID.  The launcher never wraps the game in a container or alters
 * its process environment.  The strategy only affects *how the launcher copies
 * save data* before and after the game runs – the game itself is untouched.
 */
enum class DataAccessStrategy {

    /**
     * Access `/sdcard/Android/data/<pkg>/` – no root or special permission needed.
     *
     * This is the default and the safest option.  Games that persist their state
     * to external storage will pick up workspace changes on the next load/autosave.
     */
    EXTERNAL_STORAGE,

    /**
     * Access `/data/data/<pkg>/` via `run-as <pkg>`.
     *
     * No root is required, but the target app must be **debuggable**
     * (`android:debuggable="true"` in its manifest).  This is common for
     * beta / sideloaded builds.  Production Play Store releases are NOT
     * debuggable and will reject `run-as`.
     *
     * The launcher still calls `am start` to start the game normally; `run-as`
     * is used only for the pre-launch export and post-exit import steps.
     */
    RUN_AS,

    /**
     * Access `/data/data/<pkg>/` via `su -c "cp …"` (root required).
     *
     * Works for any app – including production Play Store builds – on a rooted
     * device.  This corresponds to the legacy `useRootForData = true` flag.
     */
    ROOT,

    /**
     * Patch live process memory via `/proc/<pid>/mem` (root or ptrace required).
     *
     * The launcher injects mod values directly into the running game process so
     * the game sees the changes immediately without a save/reload cycle.  Useful
     * for values that are only ever kept in RAM (e.g. session-level counters).
     *
     * Steps:
     * 1. `am start` launches the game normally.
     * 2. [com.smapifan.androidmodder.service.ProcessMemoryService] locates the PID
     *    and the target memory addresses.
     * 3. Values are written to `/proc/<pid>/mem` via the shell.
     * 4. The game reads its own RAM and "sees" the patched values.
     */
    PROCESS_MEMORY,

    /**
     * Store and access all game data entirely inside Android-Modder's own
     * private files sandbox — **nothing ever leaves the app**.
     *
     * ✅ No root required.
     * ✅ No external-storage permission needed.
     * ✅ Data is never written to `/sdcard/` or any real `/data/data/<pkg>/` path.
     *
     * ## Virtual layout (mirrors real Android paths inside the app sandbox)
     *
     * ```
     * <appFilesRoot>/
     *   data/
     *     data/
     *       <packageName>/      ← virtual /data/data/<packageName>/
     *         files/
     *         shared_prefs/
     *         …
     *     <packageName>/        ← virtual /data/<packageName>/
     * ```
     *
     * The in-app "root browser" ([com.smapifan.androidmodder.service.InAppBrowserService])
     * navigates this tree so users can browse and edit save files without any
     * special permissions.  The virtual paths (`data/data/<pkg>/…`) mirror the
     * real Android path structure, making them instantly recognisable.
     *
     * ## Launch cycle
     *
     * Because all data lives inside the app's own files directory:
     * - **Export**: plain Java file copy from the virtual FS directories to the
     *   workspace — no shell command, no root.
     * - **Import**: plain Java file copy from the workspace back to the virtual FS
     *   directories — no shell command, no root.
     *
     * Use this strategy when:
     * - The device is not rooted.
     * - You do not need to interact with a live game process.
     * - You want a fully self-contained, root-free experience.
     */
    VIRTUAL_FS
}
