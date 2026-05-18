# OSPChat — Desktop

Compose Multiplatform desktop client for OSPChat. Speaks the same wire
protocol as [`ospchat-android`](https://github.com/tb0hdan/ospchat-android) and discovers Android
peers on the same LAN via mDNS.

## Run

```bash
gradle run
```

The shared Kotlin module [`ospchat-shared`](https://github.com/tb0hdan/ospchat-shared)
is consumed from the GitHub Packages Maven registry — same as
[`ospchat-android`](https://github.com/tb0hdan/ospchat-android). Even for public packages GitHub
requires an authenticated `GET`, so before the first build add a
[Personal Access Token](https://github.com/settings/tokens) with the
**`read:packages`** scope to your user-level `~/.gradle/gradle.properties`:

```properties
gprUser=your-github-username
gprToken=ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

Alternatively export `GITHUB_ACTOR` and `GITHUB_TOKEN` in your shell —
the build reads either source.

## Package

`jpackage` (which Compose Desktop uses under the hood) only produces installers
for the **host OS** — you can't cross-compile a `.dmg` from Linux or an `.msi`
from macOS. To ship all three you need a CI matrix or three machines.

The provided `Makefile` wraps the canonical Gradle tasks and routes to the
right output path for the detected host:

| Command           | Output                                                      |
| ----------------- | ----------------------------------------------------------- |
| `make help`       | List all targets (and show the detected host)               |
| `make run`        | Run from sources                                            |
| `make dist`       | Portable directory with bundled JRE (`build/compose/binaries/main/app/OSPChat/`) |
| `make package`    | Native installer — `.deb` (Linux) / `.dmg` (mac) / `.msi` (win) |
| `make uber-jar`   | Self-contained fat JAR (`build/compose/jars/`)              |
| `make release`    | `package` — the canonical release flow                      |
| `make install-deb`| `sudo dpkg -i` the produced `.deb` (Linux only)             |
| `make clean`      | gradle clean                                                |

Raw gradle:

```bash
gradle packageDistributionForCurrentOS
# outputs:
#   Linux: build/compose/binaries/main/deb/*.deb
#   macOS: build/compose/binaries/main/dmg/*.dmg
#   Win  : build/compose/binaries/main/msi/*.msi
```

## Layout

```
ospchat-desktop/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle/libs.versions.toml
└── src/desktopMain/kotlin/com/ospchat/desktop/
    ├── Main.kt                MainKt entry point + AppRoot composition
    ├── AppContainer.kt        Manual DI for every shared service
    ├── AppController.kt       App lifecycle owner (start server + discovery)
    └── ui/
        ├── Screens.kt         Sealed Screen state
        ├── NicknameScreen.kt  First-run nickname prompt
        ├── PeersScreen.kt     Peer list (online dot, unread count)
        └── ChatScreen.kt      Per-peer chat (text only)
```

## Dependencies

All shared logic — Room data layer, mDNS discovery, embedded Ktor server +
client, identity store, image compressor, file stores — lives in
`com.ospchat:ospchat-shared` (Kotlin Multiplatform). The desktop module adds:

- Compose Multiplatform 1.7.3
- Ktor client CIO engine + content negotiation
- kotlinx-coroutines-swing (so `Dispatchers.Main` resolves to the AWT thread)
- slf4j-nop 2.0.x (silences Ktor's SLF4J binding lookup)
- kotlinx.datetime (for `Clock.System` time stamps in chat UI)

## Current state (scaffold)

- ✅ Launches; first run prompts for nickname (persisted via DataStore)
- ✅ Starts embedded HTTP server on an ephemeral port
- ✅ Advertises `_ospchat._tcp.` via JmDNS
- ✅ Three-tab shell (NavigationRail): **Contacts** / **Groups** / **About**
- ✅ Contacts tab: live peer list (split into saved contacts + visible peers), online dot, unread count, host:port
- ✅ Long-press peer row → menu: Add/Remove from contacts, Info
- ✅ Peer Info dialog: UUID, status, first/last seen, full address + nickname history
- ✅ Tap a peer → chat screen; send + receive text
- ✅ Attach image (📎 in composer) → OS file picker → JPEG-compressed + sent
- ✅ Inbound image attachments render inline (Skia-decoded from local file)
- ✅ Long-press chat bubble → emoji picker; reaction chips under bubble; tap chip to toggle
- ✅ Groups tab: live group list, split chat vs broadcast
- ✅ "New group" FAB → dialog with name + kind toggle + member multi-select
- ✅ Group chat screen: per-bubble sender name, broadcast-channel send guard for non-creators
- ✅ About tab: editable nickname, version, bound port, project link, exit (with confirm)
- ✅ Avatars — deterministic per-UUID initials avatar in peer rows + chat header
- ✅ Custom avatar picker in About — file dialog → SHA-256 hashed → peers notified via `/v1/notify-refresh`
- ✅ System tray icon (where the DE supports it) — Show / Hide / Exit
- ✅ Sub-second shutdown (was ~5 s) — see [Shutdown](#shutdown) below

## Shutdown

Exit goes through a deliberate two-thread shutdown so the UI doesn't hang on JmDNS:

- A **daemon** `ospchat-shutdown` thread runs `MessageServer.stop`, `JmDnsPeerDiscovery.stop`, `HttpClient.close`, and `OspChatDatabase.close` best-effort.
- A **non-daemon** `ospchat-shutdown-killer` thread joins it with an 800 ms deadline and then calls `exitProcess(0)`.

Why: JmDNS' `close()` blocks ~5 s flushing mDNS goodbye records, and its background threads aren't daemon-marked, so without intervention the JVM stays alive for several seconds after the window has been dismissed. With this fix the perceived shutdown is ≤ 800 ms (typically much less). Peers will notice us drop off via their next mDNS query timeout regardless of whether the goodbye packet was flushed.

## Continuous integration

Two workflows under `.github/workflows/`:

- **`ci.yml`** — every push / PR. Runs on a single `ubuntu-latest` runner:
  compiles `ospchat-desktop`, builds the distributable
  (`:createDistributable`) as a smoke check. Native installers are skipped on
  branch / PR runs.
- **`release.yml`** — on tag push matching `v*` (e.g. `git tag v0.1.0 && git push origin v0.1.0`).
  Matrix-builds installers on `ubuntu-latest` (`.deb`), `macos-latest` (`.dmg`),
  and `windows-latest` (`.msi`). Each runner uploads its installer as an artifact;
  a final job downloads all three and publishes a GitHub Release named after the
  tag with all three attached. `generate_release_notes: true` builds the
  changelog from commit messages since the previous tag.

Both workflows authenticate to GitHub Packages with the workflow-provided
`GITHUB_TOKEN` (surfaced as Gradle properties `gprUser` / `gprToken`) so
the build can resolve `com.ospchat:ospchat-shared` from the registry. The
job's `permissions:` block grants `packages: read` for this.

## Window-close behavior

- **Tray supported (KDE, Windows, macOS, GNOME-with-AppIndicator extension, etc.):** the window's **X** button hides to tray; explicit Exit (tray menu / File → Exit / About → Exit) tears the backend down.
- **No system tray (GNOME-Wayland default, plain stumpwm, etc.):** the **X** button is wired to full exit, since "hide to tray" with no tray would strand the user.
