# BlindSpot — JS Endpoint Coverage Finder

A Burp Suite extension that surfaces the endpoints in JS your testing never
touched. Deprecated and forgotten APIs are where the gold is — referenced once
in a JS bundle and never hit during normal browsing.

It runs as a **live passive** capture (no manual "scan" button). As you browse,
two side-by-side tables update in real time:

| Panel | Contents |
|-------|----------|
| **Left — Visited Paths** | Paths you actually requested through the proxy |
| **Right — Discovered Endpoints** | Every **unique** endpoint found (JS-referenced + observed), colored by visited state. With **Show Path** on, a second column lists all JS file(s) each endpoint was found in. |

Color coding on the right panel:

- 🟢 **Green** — discovered *and* visited
- 🔴 **Red** — naturally discovered (via JS scanner or active traffic) but **never visited**
- 🟡 **Yellow** — ignored path (via right-click context menu "Toggle Ignore")
- 🔵 **Blue** — unvisited imported endpoint (loaded from external sources/wordlist)

Rows flip color live as you browse to them (turning green) or toggle ignore status.

Each panel has its own **Search** box that filters the visible rows live
(case-insensitive substring, across both columns) without changing the stored
data. Column headers are also click-sortable. The two panels are separated by a
**draggable divider** (with one-touch collapse arrows) so you can give either
side more room.

## Why passive (not on-demand scanning)

Deprecated endpoints are often referenced once in JS and never requested again.
An on-demand "Run Scan" button would risk missing the exact JS response that
contained the reference if it isn't loaded at scan time. Live passive capture
catches these reliably.

## Prerequisite: configure Burp Target → Scope

> **Important.** BlindSpot gates all capture on Burp's configured scope. Before
> relying on it, set **Target → Scope** to your target host(s).

When the master toggle is **ENABLED: ON**, only traffic *inside* Burp's
Target → Scope is captured. This drops third-party noise (CDNs, analytics,
fonts, trackers) that would otherwise bloat the store — it does **not** exclude
any path *within* your target, including obscure/deprecated ones.

If scope is not configured, in-scope checks may capture little or nothing.

## Usage

1. Load the extension (see Build below) — it adds a **BlindSpot** suite tab.
2. Configure **Target → Scope** for your target.
3. Click the toggle so it reads **ENABLED: ON**.
4. Browse the target normally. Endpoints populate as traffic flows.
5. Use the **Host** dropdown to switch which host's coverage you're viewing
   (one host at a time).
6. Hunt the **red** rows on the right panel.

### Controls

| Control | Behavior |
|---------|----------|
| **ENABLED: ON/OFF** | Master switch. ON = capture in-scope traffic only. |
| **Host** dropdown | Selects which host's map is shown. New hosts appear automatically; the first one auto-selects. |
| **Exclude** | **Table-view filter only — everything in scope is still captured and persisted.** Comma-separated entries to hide. Dotted entries match as **file extensions** (suffix), e.g. `.json` hides `/x.json` but **not** `/api/jsonify`. Non-dotted entries match as **substrings**, e.g. `/static/` or `analytics` hides anywhere in the path. Editing the field re-applies the filter to the tables live; clearing it reveals everything, so you never miss an endpoint. |
| **Show Path** | Shows/hides the **Found In (JS Source)** column on the right table — the JavaScript file(s) each endpoint was extracted from. |
| **Discovered** | Filters the right table: **Show All** (every discovered endpoint), **Unvisited** (only the red rows — endpoints never hit), **Ignored** (yellow rows), or **Wordlist** (blue rows — unvisited imported endpoints). Visited paths already appear in the left pane. Under **Unvisited** and **Wordlist**, a row drops off live the moment you visit it. |
| **Load URLs from other sources** | Load list of API endpoints from other URL miner tools (e.g. Katana). File format must contain one entry per line, optionally separating the path and sources with a space. Specifying the JS source path(s) is not strictly necessary, but is recommended for better tracking and provenance mapping in the UI:<br><br>`/api/v1`<br>`/api/v2/d    javascriptsourcefilepath.js, javascriptsourcefilepath2.js`<br><br>Already known/visited paths are skipped; new ones show up in **Blue** on the right table. |
| **Scan Existing Traffic** | Scans the traffic already in Burp's **Proxy → HTTP history** using the current scope and the same scan pipeline as live capture, and backfills the store. Use it when BlindSpot was loaded *after* you'd already been browsing, so you don't have to re-walk the app. Runs in the background; shows how many items were processed. |
| **Clear** | Clears the **selected** host's data (or everything if no host is selected) and repaints. |
| **Export** | Writes the selected host's `visited` / `missing` diff to a JSON file. |

## Persistence

Discoveries are checkpointed into Burp's **project-scoped** persistent storage,
so accumulated findings survive an extension reload or a Burp crash. State is
segmented per host and written on a debounced background thread (every few
seconds when changed, and flushed on unload) to avoid I/O thrash during
high-volume capture.

The store is keyed as `host → { path → visited }`, which keeps long,
multi-target engagements clean: clear one host without disturbing others, and
export per host for reporting.

## Build

Requires JDK 21 (the Gradle toolchain will provision one).

```bash
./gradlew jar
```

The packaged extension is written to:

```
app/build/libs/app-1.0.2.jar
```

The Kotlin standard library is merged into this JAR so it loads standalone.

### Install in Burp

**Extensions → Installed → Add** → Extension type **Java** → select
`app/build/libs/app-1.0.2.jar`.

## Project layout

```
app/src/main/kotlin/com/blindspot/
├── BlindSpotExtension.kt          # entry point: registers handlers, loads state
├── LiveProxyHandler.kt            # thin proxy hooks → CaptureEngine
└── modules/
    ├── engine/
    │   ├── UrlModule.kt           # path normalization
    │   ├── StorageModule.kt       # per-host store + persistence
    │   ├── JsScannerModule.kt     # JS endpoint extraction + classification
    │   └── CaptureEngine.kt       # shared capture pipeline (live + history scan)
    └── ui/
        ├── UiController.kt        # view composition + EDT marshaling
        ├── ControlPanelComponent.kt  # toolbar, host selector, filters, export
        └── TableComponent.kt      # coverage tables, search, color renderer
```

## Requirements

- Burp Suite (Montoya API `2024.7`)
- JDK 21

## License

Released under the [MIT License](LICENSE). © 2026 bineeg.
