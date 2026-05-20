# OSPChat — Desktop project notes

## Overview

OSPChat Desktop is a Compose Multiplatform (JVM) client that speaks the
same wire protocol as [`ospchat-android`](../../ospchat-android). Both apps
discover each other on the same LAN via mDNS / DNS-SD (`_ospchat._tcp.`)
and exchange messages peer-to-peer over embedded HTTP servers — no central
server, no internet required.

Almost all non-UI code (DTOs, Room data layer, identity store, mDNS
discovery, embedded Ktor server + client, attachment + avatar stores, every
repository and use case) lives in a sibling [`ospchat-shared`](../../ospchat-shared)
Kotlin Multiplatform module that both clients consume. The desktop module
contributes: the Compose Desktop UI, a manual `AppContainer` DI graph, a
JmDNS-backed implementation of the shared `PeerDiscoveryService`, an
`ImageIO`-backed `ImageCompressor`, and host-aware lifecycle plumbing (tray
icon, sub-second shutdown).

## Scope

In scope:

- Three-tab shell (NavigationRail): **Contacts** / **Groups** / **About**.
- First-run nickname prompt; nickname persisted via DataStore.
- mDNS discovery of LAN peers via JmDNS; reflects live snapshot joined
  with the local Room copy.
- Text messaging (send + receive) with delivery status pipeline.
- Image attachments: file picker → JPEG compression → wire transfer →
  inline rendering via Skia decode.
- Reactions: long-press a bubble, pick an emoji, chips toggle.
- Group chats and broadcast channels with mesh-direct posting +
  catch-up sync on re-discovery.
- Group creation from the desktop UI with member multi-select.
- Avatar UI: deterministic initials avatar per peer + custom-avatar
  picker that propagates via `/v1/notify-refresh`.
- System tray icon (where the desktop environment supports it) with
  Show / Hide / Exit; window-close behaviour adapts (hide-to-tray if a
  tray exists, full exit otherwise).
- Sub-second shutdown despite JmDNS' slow `close()`.
- Native installer per host OS via `compose.desktop.application` → `jpackage`:
  `.deb` on Linux, `.dmg` on macOS, `.msi` on Windows.

Out of scope (deferred):

- TLS / authenticated handshake (still TOFU, LAN-only — same as Android).
- Encryption at rest.
- Message editing / deletion (no tombstones).
- Voice notes / arbitrary file attachments (images only).
- Multi-network discovery (Tailscale, multiple interfaces).
- Window menubar with shortcuts (intentionally removed as redundant once
  tray + About → Exit existed).

## Tech choices

| Decision                | Choice                                                        |
| ----------------------- | ------------------------------------------------------------- |
| UI framework            | Compose Multiplatform 1.7.3 (JVM target)                      |
| Language                | Kotlin 2.0.21                                                 |
| JVM target              | 17 (built with JDK 21, `jvmTarget = JvmTarget.JVM_17`)        |
| DI                      | Manual `AppContainer` — no DI framework on desktop            |
| Routing                 | Sealed `Screen` + tab enum, `mutableStateOf` in `MainRoot`    |
| Persistence             | Room 2.7 KMP via `ospchat-shared` (`OspChatDatabase`, 9 migrations) |
| Identity store          | `androidx.datastore:datastore-preferences-core` (multiplatform) |
| HTTP                    | Ktor 2.3.13 client + server (CIO engine), shared with Android |
| Wire protocol           | OpenAPI 0.8.0, `_ospchat._tcp.` mDNS                          |
| mDNS                    | JmDNS 3.5.9 (desktop actual of shared `PeerDiscoveryService`) |
| Image decode/encode     | `javax.imageio` + `metadata-extractor` (EXIF orientation baked in) + Skia via `org.jetbrains.skia.Image.makeFromEncoded` (display) |
| Concurrency dispatcher  | `Dispatchers.Swing` for the AWT UI thread                     |
| Packaging               | `compose.desktop` → `jpackage` (host-bound: deb/dmg/msi only) |
| Notification surface    | `DesktopMessageNotifier` → Compose `TrayState.sendNotification`; active-chat suppression via shared `ActiveChatTracker` |
| Emoji picker            | Tabbed picker over the Android `emoji2-emojipicker` 1.5.0 dataset (10 categories, ~1,889 base glyphs vendored as CSVs in `resources/emoji/`) |
| Emoji glyph rendering   | Noto Color Emoji (SIL OFL 1.1) vendored at `resources/fonts/NotoColorEmoji.ttf`; routed through `EmojiFont.family` so hosts without a system emoji font still render in color |
| Logging on desktop      | stderr `println` (actual of shared `Log`)                     |
| Build tool              | Gradle 8.10.2 (pinned in CI; system Gradle locally is fine)   |
| Shared-module flow      | GitHub Packages registry (same as `ospchat-android`) — needs `gprUser` / `gprToken` PAT with `read:packages` |
| Application id          | `com.ospchat.desktop` (JVM main: `com.ospchat.desktop.MainKt`) |

### Why GitHub Packages and not Gradle composite-build

Gradle's `includeBuild("../ospchat-shared")` hits a known **`BuildFusService`
classloader conflict** in Kotlin Gradle Plugin 2.0.x when two KMP builds
share a composite. The shared module's KGP instance and the desktop
module's KGP instance can't share the same fully-shared service.

The chosen workaround is the same one `ospchat-android` uses: consume
`com.ospchat:ospchat-shared:0.1.2` from the GitHub Packages Maven registry
(`https://maven.pkg.github.com/tb0hdan/ospchat-shared`). Even public
packages require an authenticated `GET`, so the build reads either
`gprUser` / `gprToken` Gradle properties or `GITHUB_ACTOR` /
`GITHUB_TOKEN` env vars. Documented in `README.md` under "Run".

This replaced an earlier mavenLocal flow (`gradle publishToMavenLocal`
over in `ospchat-shared/`) once `ospchat-shared` started publishing
releases to GitHub Packages.

## Architecture

```
                  ┌──────────────────────────────────────────────────┐
                  │                  Compose Window                  │
                  │  ┌──────────────┐                                │
                  │  │ NicknameScreen │  (first run only)            │
                  │  └──────┬───────┘                                │
                  │         │                                        │
                  │  ┌──────▼───────────┐    ┌──────────────────┐    │
                  │  │   MainShell      │ ▶  │   ChatScreen     │    │
                  │  │ NavigationRail:  │ ▶  │   GroupChatScreen│    │
                  │  │ Contacts/Groups/ │    │   PeerInfoDialog │    │
                  │  │ About            │    │   CreateGroupDlg │    │
                  │  └──────┬───────────┘    └──────────────────┘    │
                  │         ▼                                        │
                  │   AppController (lifecycle + send / mark-read /  │
                  │     react / create-group / set-avatar / exit)    │
                  └──────────┬───────────────────────────────────────┘
                             │
              ┌──────────────▼─────────────────────────────┐
              │            AppContainer (manual DI)        │
              │                                            │
              │  HttpClient (Ktor CIO)                     │
              │  ospChatDatabase()  ─── Room 2.7 KMP       │
              │  createIdentityDataStore()                 │
              │  FileAttachmentStore / FileAvatarStore     │
              │  ImageIoCompressor                         │
              │  JmDnsPeerDiscovery  ── PeerDiscoveryService│
              │  DiscoveryRepository                       │
              │  IdentityRepository / PeerRepository /     │
              │   ReactionRepository / MessageRepository / │
              │   GroupRepository / GroupMessageRepository │
              │   / GroupSyncer / GroupBroadcaster /       │
              │   PeerAvatarSync / PeerInfoNotifier        │
              │  MessageClient                             │
              │  MessageServer  (Ktor CIO, /v1/*)          │
              │  NoOpMessageNotifier                       │
              └────────────────────────────────────────────┘
                             │
                             ▼
                    com.ospchat:ospchat-shared:0.1.2
                  (via maven.pkg.github.com/tb0hdan/ospchat-shared)
```

`AppContainer` owns every singleton for the process lifetime. `AppController`
sits between the Compose tree and the container, holding lifecycle state
(`running`, `boundPort`) and exposing imperative actions (`start`, `sendText`,
`sendGroupText`, `markPeerRead`, `markGroupRead`, `reactToMessage`,
`createGroup`, `setSelfAvatar`, `clearSelfAvatar`, `shutdown`). Compose
screens collect `Flow`s straight from `container.<repo>.observe…()` via
`collectAsState`.

## Repository layout

```
ospchat-desktop/
├── .editorconfig                              (inherited convention)
├── .github/workflows/
│   ├── ci.yml                                 push/PR: compile + distributable smoke
│   └── release.yml                            tag push: matrix-builds .deb/.dmg/.msi
├── Makefile                                   build / run / dist / package / uber-jar / release / clean
├── README.md                                  user-facing instructions
├── settings.gradle.kts                        mavenLocal in dep-resolution repos
├── build.gradle.kts                           compose.desktop + KMP plugins
├── gradle.properties
├── gradle/libs.versions.toml
├── docs/
│   └── PROJECT_NOTES.md                       (this file)
├── icons/
│   ├── icon.svg                              source (Android launcher recreated)
│   ├── icon.icns                             macOS launcher (multi-res 16..1024)
│   ├── icon.ico                              Windows launcher (multi-res 16..256)
│   └── icon.png                              Linux launcher (512 px)
├── macos/
│   └── entitlements.plist                    hardened-runtime entitlements (loaded only when signing identity supplied)
└── src/desktopMain/
    ├── kotlin/com/ospchat/desktop/
    │   ├── Main.kt                            application{} entry, Tray, Window, AppRoot
    │   ├── AppContainer.kt                    manual DI singletons
    │   ├── AppController.kt                   lifecycle + imperative actions
    │   ├── attachments/
    │   │   └── ExifAwareImageCompressor.kt    EXIF Orientation → AffineTransform pipeline
    │   ├── notifications/
    │   │   └── DesktopMessageNotifier.kt      MessageNotifier → TrayState.sendNotification
    │   └── ui/
    │       ├── Screens.kt                     sealed Screen + Tab enum
    │       ├── MainShell.kt                   NavigationRail + tab dispatch
    │       ├── NicknameScreen.kt              first-run prompt
    │       ├── PeersScreen.kt                 contacts + visible peers, right-click menu
    │       ├── PeerInfoDialog.kt              UUID / status / address + nickname history
    │       ├── ChatScreen.kt                  bubbles, reactions, image picker, emoji composer
    │       ├── GroupsScreen.kt                live group list + FAB
    │       ├── CreateGroupDialog.kt           name / kind / member picker
    │       ├── GroupChatScreen.kt             group bubbles + broadcast send guard + emoji composer
    │       ├── AboutScreen.kt                 nickname / version / port / avatar / exit
    │       ├── Avatar.kt                      initials avatar + file-image fallback
    │       ├── EmojiCatalog.kt                lazy-loaded 10-category emoji data from resources
    │       ├── EmojiFont.kt                   Bundled Noto Color Emoji typeface + emojiAware() AnnotatedString helper
    │       ├── EmojiPicker.kt                 tabbed LazyVerticalGrid picker + dialog wrapper
    │       └── FileImage.kt                   async Skia-decoded local file → Compose ImageBitmap
    └── resources/
        ├── emoji/                             Android-bundled emoji CSVs (Apache 2.0)
        └── fonts/                             Noto Color Emoji TTF (SIL OFL 1.1)
```

## Current status

- 2026-05-20 — **Audio voice calls (phase 1, unreleased).** One-to-one LAN
  voice calls between OSPChat peers, audio only. New phone icon in
  `ChatScreen`'s top bar starts the call; `Screen.InCall` (new variant in
  the sealed `Screen` enum) becomes the active screen and shows peer
  avatar + state ("Calling…" / "Connecting…" / "Connected · m:ss") +
  mute + hangup. Incoming calls render an `IncomingCallDialog` overlay
  in `MainRoot` (above the existing `Screen.Main` dispatch, so it
  appears over any current screen) — accept routes to `Screen.InCall`,
  decline POSTs `/v1/call/hangup`. Both sides honour a 30s no-answer
  ring timeout; second incoming call during an active call is
  auto-rejected with `BUSY`. Media stack:
  `dev.onvoid.webrtc:webrtc-java:0.14.0` (libwebrtc JNI bindings)
  wrapped in `JvmAudioCallSession` / `JvmAudioCallSessionFactory` in
  `media/`. Host OS+arch detection in `build.gradle.kts` picks the
  matching per-platform classifier jar (linux-x86_64, linux-aarch64,
  macos-aarch64, etc.) at build time — matches the existing release
  matrix where each runner ships its own platform's natives.
  `RTCPeerConnection` configured with empty ICE servers — LAN-only,
  host candidates only. Signaling rides existing Ktor HTTP via 4 new
  endpoints (`/v1/call/{offer,answer,ice,hangup}`) introduced in
  `ospchat-shared:0.2.1`; media itself is UDP via libwebrtc.
  `DesktopCallRinger` (implements shared `CallNotifier`) loops a
  synthesized PCM 440 Hz ringtone via `javax.sound.sampled.Clip` (no
  bundled WAV needed). macOS `Info.plist` extended with
  `NSMicrophoneUsageDescription` so the TCC mic prompt renders.
  `mavenLocal()` added to `settings.gradle.kts` for shared-module dev
  cycles. Out of scope phase 1 (deferred): video, call history UI,
  group calls, multiple concurrent calls, retry/reconnect, hold.
- 2026-05-18 — Scaffolded the Gradle project (Compose Multiplatform 1.7.3,
  Kotlin 2.0.21, JVM 17 bytecode). Established the `AppContainer` + manual
  DI pattern; first window opened.
- 2026-05-18 — `AppController` lifecycle: starts MessageServer + JmDNS
  advertise once a nickname is set; persists newly-seen peers via
  `PeerRepository.recordSeen`.
- 2026-05-18 — Three-tab NavigationRail shell (Contacts / Groups / About)
  with peer list, chat screen, and About (nickname edit, version, bound
  port, exit). Replaced an earlier flat layout.
- 2026-05-18 — Group support: clicking a row navigates to `GroupChatScreen`;
  broadcast channels gate the input for non-creators. FAB on the Groups
  tab opens `CreateGroupDialog` with name + kind toggle + member multi-select.
- 2026-05-18 — Long-press peer rows for Add/Remove from contacts + Info
  dialog; `PeerInfoDialog` surfaces UUID, status, full address + nickname
  history from the Room `peer_addresses` / `peer_nicknames` tables.
- 2026-05-18 — Image attachments: AWT `FileDialog` picks an image, bytes
  flow through the shared `ImageCompressor` (`ImageIoCompressor` actual)
  and `AttachmentStore`. Inbound bubbles render inline via `FileImage`
  (Skia decode on `Dispatchers.IO`).
- 2026-05-18 — Reactions: long-press bubble → `EmojiPickerDialog` (12 emoji),
  reaction chips display under bubbles, tap toggles.
- 2026-05-18 — Avatars: deterministic initials avatar (per-UUID 16-color
  palette, same hash as Android so peers' bubble colours match cross-client).
  Custom avatar picker in About (SHA-256, `AvatarStore.writeSelf`, peers
  notified via `PeerInfoNotifier.broadcastRefresh`).
- 2026-05-18 — System tray + adaptive close: `isTraySupported`-gated `Tray`
  with Show / Hide / Exit; window-close X hides to tray on KDE/Mac/Win,
  full-exits on GNOME-Wayland (no tray indicator).
- 2026-05-18 — Sub-second shutdown: `AppController.shutdown` spawns a
  daemon cleanup thread + a non-daemon killer that joins with 800 ms
  deadline then `exitProcess(0)`. Avoids the ~5 s JmDNS `close()` block.
  Window menubar removed as redundant with tray + About → Exit.
- 2026-05-18 — Makefile (`help`, `info`, `build`, `run`, `dist`, `package`,
  `uber-jar`, `release`, `all`, `install-deb`, `clean`). `make package`
  produces `ospchat_1.0.0_amd64.deb` (~100 MB w/ bundled JRE).
- 2026-05-18 — CI workflow (`ci.yml`): push/PR smoke on Linux runner —
  compiles desktop and builds the distributable. Release workflow
  (`release.yml`): tag-push matrix on Linux/macOS/Windows, each builds its
  native installer + uploads as artifact; final job attaches all three
  to a GitHub Release with auto-generated changelog. Both workflows
  authenticate to GitHub Packages via the runner's `GITHUB_TOKEN` to
  resolve `ospchat-shared`.
- 2026-05-18 — Switched shared-module resolution from mavenLocal to the
  released `com.ospchat:ospchat-shared:0.1.0` artifact on GitHub Packages
  (same as `ospchat-android`). Drops the `gradle publishToMavenLocal`
  per-edit step and the sibling-checkout dance from CI. See
  "Why GitHub Packages..." above.
- 2026-05-19 — Bundled Noto Color Emoji (SIL OFL 1.1, ~10 MB) under
  `resources/fonts/`. Exposed as `EmojiFont.family` and applied to
  picker cells, reaction chips, both 😊 composer buttons, the message
  composer (`OutlinedTextField.textStyle` — Skia per-glyph fallback
  handles Latin), and message bodies (via `emojiAware()`, which builds
  an `AnnotatedString` and wraps emoji-codepoint runs in a `SpanStyle`
  targeting the bundled typeface). Fixes monochrome contour rendering
  on hosts (notably most Linux distros) whose system FontMgr has no
  color emoji font.
- 2026-05-19 — Build pipeline fixes. (1) Installer `packageVersion`
  derivation now preserves minor/patch from `VERSION`: `0.1.3` ships as
  `1.1.3` instead of the previous flat `1.0.0` shim, so installer
  filenames track real releases. (2) macOS release matrix split into two
  runners — `macos-13` (Intel, x86_64 .dmg) and `macos-latest` (Apple
  Silicon, arm64 .dmg). Both attached to each GitHub Release as
  `OSPChat-<version>-x86_64.dmg` / `OSPChat-<version>-arm64.dmg` (the
  workflow passes `-PmacArch=...` so `build.gradle.kts` differentiates
  the `packageName`).
- 2026-05-19 — Bumped CI/release workflow actions to Node 24 majors
  ahead of GitHub's Node-20 retirement (forced default 2026-06-02,
  removal 2026-09-16). `actions/checkout`, `actions/setup-java`,
  `actions/upload-artifact`, `actions/download-artifact`,
  `actions/cache` now at `@v5`; `softprops/action-gh-release` at
  `@v3`. `gradle/actions/setup-gradle` pinned to `@v5` — `@v6`
  relicensed the caching component as proprietary
  (https://gradle.com/legal/terms-of-use/), so the upgrade was
  deliberately held back. Input contracts unchanged for how this
  repo calls each action.
- 2026-05-19 — Fixed startup crash in the bundled JRE on (at least)
  Intel macOS: `NoClassDefFoundError: sun/misc/Unsafe` from
  `androidx.datastore.preferences.protobuf` (protobuf-lite). `sun.misc.Unsafe`
  lives in the `jdk.unsupported` JDK module; jdeps can't pick up
  `sun.misc.*` references, so jlink dropped the module from the
  bundled runtime. `build.gradle.kts` now declares
  `modules("jdk.unsupported")` in `nativeDistributions` so the
  jpackage runtime image ships it.
- 2026-05-19 — macOS Info.plist hardening + Developer ID signing
  scaffold. Root cause: macOS' application firewall (ALF) keys allow/deny
  decisions on a binary's Designated Requirement, which only exists for
  code signed with a stable identity, so an unsigned `.dmg` re-prompts on
  every relaunch and the Ktor `0.0.0.0:ephemeral` listener stays blocked
  until the user clicks through. We can't produce a Developer-ID
  signature without a paid Apple cert (and self-signed / ad-hoc-signed
  builds have no DR for another Mac to recognise), but the surrounding
  plumbing is now in place: `bundleID = "com.ospchat.desktop"`,
  `appCategory = "public.app-category.social-networking"`,
  `infoPlist.extraKeysRawXml` declares `NSBonjourServices = ["_ospchat._tcp"]`
  and `NSLocalNetworkUsageDescription` (macOS 15 Sequoia gates outbound
  mDNS multicast on the Local Network privacy prompt — inbound TCP is
  exempt), and the `macOS { signing { ... } }` block plus
  `macos/entitlements.plist` (network client/server, JIT, unsigned
  executable memory for Skiko) activate when `-PmacSigningIdentity=...`
  is passed. README documents the
  `sudo /usr/libexec/ApplicationFirewall/socketfilterfw --add ...
  --unblockapp ...` workaround unsigned-install users need until the
  cert lands.
- 2026-05-19 — Branded the desktop installer with the Android launcher
  icon. `icons/icon.svg` recreates the Android adaptive icon
  (`#0F172A` background + the chat-bubble vector from
  `ospchat-android`'s `ic_launcher_foreground.xml`, fill `#E2E8F0`) in a
  108-unit viewBox with a 24-radius rounded square so every host gets a
  self-masked squircle without depending on platform icon masking.
  `make icons` renders it to the three formats jpackage consumes:
  `icon.icns` (multi-res 16…1024 via `png2icns`), `icon.ico` (multi-res
  16…256 via ImageMagick), and `icon.png` (Linux, 512 px). The
  `nativeDistributions` block sets `iconFile` per platform; verified
  locally by checking `build/compose/binaries/main/app/OSPChat/lib/OSPChat.png`
  is byte-identical to `icons/icon.png`.
- 2026-05-19 — Fixed macOS `.dmg` startup crash the moment any
  emoji-bearing composable entered the tree (`OutlinedTextField` in
  `ChatScreen`/`GroupChatScreen`, the 😊 composer button, etc.). Root
  cause: `EmojiFont.family` used Compose's `Font(resource = ...)`, whose
  `typefaceResource` does `Intrinsics.checkNotNull(Thread.currentThread()
  .contextClassLoader)`. The jpackage runtime image on macOS leaves the
  AWT event thread's context classloader null, so the assert threw NPE
  *before* the implementation's secondary `class.getResourceAsStream`
  fallback ever ran. Linux and `gradle run` were unaffected because both
  set a non-null context classloader. `EmojiFont` now reads the TTF
  bytes via `EmojiFont::class.java.classLoader.getResourceAsStream(...)`
  and feeds them to the byte-array
  `androidx.compose.ui.text.platform.Font(identity, data, weight, style)`
  factory, completely avoiding Compose's resource path. The lazy is
  additionally wrapped in `runCatching` so any future failure (missing
  resource, Skia rejection of a swapped-in font, etc.) logs once and
  falls back to `FontFamily.Default` rather than crashing the UI.
- 2026-05-19 — Followup to the port-stability fix: `ospchat-shared:0.1.1`
  introduced a peer-list flicker / feedback-loop regression that 0.1.2
  fixes. Two changes in shared: (1) `JmDnsPeerDiscovery.forgetPeer`
  drops the blocking `JmDNS.list(SERVICE_TYPE)` call (kept only async
  `requestServiceInfo`) so the MessageClient coroutine isn't blocked
  ~6 s on the connection-failure path. (2) `MessageClient` per-call
  `rediscover: Boolean = true`; background flows pass `false` so a
  failed background sync doesn't mutate the snapshot. Wire compat
  unchanged.
- 2026-05-19 — Fixed one-way messaging after desktop restart. Root cause:
  `MessageServer` bound `port = 0` every boot (kernel-assigned ephemeral),
  and Android NSD doesn't fire `onServiceFound` / `onServiceLost` for a
  port-only change on an existing service name — Android's cached
  resolution stayed pointed at the dead port. Two-part fix in
  `ospchat-shared`: (1) `MessageServer.start(uuid, nickname, preferredPort)`
  now tries the previously-bound port (persisted by
  `IdentityRepository.lastServerPort`) and falls back to ephemeral on
  bind failure; `AppController` reads + writes the pref around the
  bind. (2) `MessageClient` wraps every per-peer call in a one-shot
  rediscover-and-retry — on TCP connect failure (`ConnectException`,
  `SocketTimeout`, "connection refused" etc.), calls
  `DiscoveryRepository.forgetPeer(uuid)` (which on JmDNS drops the cache
  entry + fires `requestServiceInfo` + `list(SERVICE_TYPE)`, and on
  Android NSD bounces discovery to force re-resolution), waits ≤3 s
  for a fresh resolution with a different host:port, then retries
  once. Application-level rejections (HTTP non-2xx) are not retried.
  No OpenAPI changes (wire compatible).
- 2026-05-20 — Outbound message status now uses checkmarks (Android
  parity). `ChatScreen.MessageBubble` and
  `GroupChatScreen.GroupBubble` previously displayed
  `message.status.name.lowercase()` as plain text. Both now render
  Android's symbol map: `Sending…` / `✓` / `✓✓` / `⚠ Not delivered`
  for `Message.Status`, and `Sending…` / `✓` / `⚠ Not delivered`
  for `GroupMessage.Status` (groups have no READ state). Colors:
  faded `textColor` for SENDING/DELIVERED,
  `MaterialTheme.colorScheme.primary` for READ (matches Android
  exactly — Android also uses `primary` on a `primaryContainer`
  bubble; intentionally subtle), `MaterialTheme.colorScheme.error`
  for FAILED. Desktop's FAILED string differs from Android's
  `"⚠ Tap to retry"` (we use `"⚠ Not delivered"` — accurate
  copy of Android's group-screen string) because there's no retry
  callback wired through `AppController` yet.
- 2026-05-20 — Fullscreen image preview parity with Android. New
  `ui/FullscreenImageOverlay.kt` decodes the file via Skia (same path
  as `FileImage`) and renders inside `Popup(PopupProperties(focusable
  = true))` with a black-backdrop, `ContentScale.Fit`, and click-to-
  dismiss. `Popup` was chosen over `Dialog` because Compose Desktop's
  `Dialog` opens a separate OS window — wrong for a chat-attachment
  preview. `focusable = true` makes the popup grab keyboard focus and
  routes Escape to `onDismissRequest`, so no explicit key handler is
  needed. `MessageBubble` gained an `onImageTap: (String) -> Unit`
  parameter; the existing `FileImage(...)` call now passes
  `modifier = Modifier.clickable { onImageTap(path) }`. Pinch-zoom /
  pan (which Android has via `rememberTransformableState`) was
  deliberately deferred — the mouse-wheel-zoom UX is a separate
  affordance from the touch one.
- 2026-05-20 — Hardened the `Screen.GroupChat` branch in `MainRoot`
  against a deleted group. Triggered by the leave-group flow:
  `broadcastLeave` can block several seconds on unreachable peers
  before `applyLocalLeave` finally deletes the row, and if the user
  re-entered the same group from the list during that window, the
  chat branch used to render a full-area `"Group no longer exists"`
  Box. That branch doesn't include the NavigationRail, so the user
  was trapped. Now: `LaunchedEffect(groupSnapshot, groupId)` flips a
  `remember(groupId) { mutableStateOf(false) }` `hasLoaded` flag on the
  first non-null emission and pops `screen = Screen.Main` on any
  subsequent null. A 200 ms `delay` guard handles the race where the
  group was already gone before the Flow ever emitted (no `hasLoaded`
  set, no synchronous pop): if still null after the delay, pop. While
  null, the branch renders nothing rather than the old error Box.
- 2026-05-20 — Wired the shared `LeaveGroupUseCase` into the desktop UI.
  `AppContainer.leaveGroupUseCase` (lazy, built from existing
  `groupRepository` / `groupDao` / `groupBroadcaster` providers) +
  `AppController.leaveGroup(groupId)` (fire-and-forget on the IO scope).
  Two UI entry points, both hidden when `group.isCreator` (the shared
  use case silently no-ops for creators per "the UI hides the option"):
  (1) `MoreVert` overflow in `GroupChatScreen`'s top row → `DropdownMenu`
  with "Leave group"; (2) right-click on `GroupsScreen.GroupRow` →
  same `DropdownMenu`. From the chat-screen path, `Main.kt` pops
  `screen = Screen.Main` *before* invoking the controller so the
  "Group no longer exists" fallback doesn't flash between
  `applyLocalLeave`'s row delete and the screen state catching up;
  from the groups-list path no pop is needed because the row simply
  disappears when `observeAll()` re-emits without it. No confirmation
  dialog (Android parity). Creator-side Add/Remove members + a desktop
  GroupInfoDialog were explicitly deferred — creators have no row
  context menu at all for now.
- 2026-05-20 — **Group chat reactions.** Right-click a `GroupBubble`
  opens the emoji picker; the picked glyph becomes the user's reaction
  on that message. Chips render inside the bubble under the body.
  Display rule per spec: 1–2 reacters with the same emoji show tiny
  initials avatars (18 dp, oldest-first by `reactedAt`); 3+ shows the
  count. Click toggles — own reaction is removed, otherwise added with
  that emoji. Reuses the existing `reactions` Room table — no
  migration; `(message_id, from_uuid)` PK works for group messages too
  since message ids are globally-unique UUIDs. New DAO query
  `ReactionDao.observeForGroup(groupId)` joins through
  `group_messages.group_id`. Delivery is mesh fan-out via the new
  `ReactionRepository.reactToGroup(...)` (mirrors
  `GroupMessageRepository.send`). Wire: `POST /v1/reactions` gains a
  nullable `groupId`; receivers validate the sender against group
  membership when it's set (`MessageRoutes` falls back to the existing
  DM check otherwise). Catch-up: `GroupSyncPayloadDto` gains a
  `reactions` list — `GroupSyncer.buildResponse` now packs every
  current reaction for the group, and `applyPayload` upserts them via
  `ReactionRepository.applyReaction`. OpenAPI bumped to 0.9.0.
- 2026-05-20 — Replaced Android-style long-press with desktop right-click
  for the two context menus that had used `combinedClickable(onLongClick = ...)`:
  the peer/contacts row (`PeersScreen.PeerRow`, Add/Remove/Info dropdown)
  and the chat message bubble (`ChatScreen.MessageBubble`, reactions
  emoji picker). Both now use the desktop-only
  `Modifier.onClick(matcher = PointerMatcher.mouse(PointerButton.Secondary), ...)`
  alongside the existing `clickable(onClick = ...)` for the primary tap.
  The reaction-chip wrapper, which had a no-op `onLongClick = {}`, was
  simplified to plain `clickable`. The stale comment in `PeersScreen`
  claiming `combinedClickable` mapped the secondary mouse button to
  `onLongClick` on desktop was incorrect — Compose's `combinedClickable`
  only fires `onLongClick` from a held primary button or a true
  long-press touch gesture, never from a right-click — so previously
  desktop users had to hold the *left* button down to open these menus.
- 2026-05-19 — Feature parity with Android: notifications, EXIF, full
  emoji picker. `DesktopMessageNotifier` posts inbound DMs / group messages
  via Compose `TrayState.sendNotification` and suppresses when the matching
  chat is on-screen (shared `ActiveChatTracker`, fed by `DisposableEffect`
  on `ChatScreen` / `GroupChatScreen`). `ExifAwareImageCompressor`
  (metadata-extractor + `AffineTransform`) replaces `ImageIoCompressor`
  for desktop attachments, baking rotation into the JPEG so phone-shot
  images render upright on peers. `EmojiPicker` is a tabbed
  `LazyVerticalGrid` over the Android-bundled emoji set (10 CSVs vendored
  byte-for-byte from `androidx.emoji2:emoji2-emojipicker:1.5.0` under
  `resources/emoji/`, Apache 2.0); used both for reactions (long-press a
  bubble) and inline insertion (😊 button in the composer of both
  `ChatScreen` and `GroupChatScreen`). Drops the old 12-emoji
  `EMOJI_CHOICES` list. Skin-tone / gender variant popups deferred.

## Known limitations

- **Tray support is platform-dependent.** GNOME-Wayland without
  AppIndicator extensions has no tray; we fall back to "X closes the app"
  but lose the show/hide affordance. The same fallback skips tray
  notifications (the notifier's `sender` callback stays unbound and
  inbound messages are dropped silently — the message itself is still
  persisted to Room).
- **No DND respect in notifications.** Desktop notifier suppresses only
  when the matching chat is on-screen; there's no portable Linux/Mac/Win
  API for system-wide Do-Not-Disturb. (Android additionally checks
  `NotificationManager.currentInterruptionFilter`.)
- **No notification-tap routing.** Clicking a tray notification on
  desktop is informational — it doesn't open the originating chat.
  Android deep-links via `ospchat://` intents.
- **Self-avatar UI is in About only.** No drag-and-drop, no crop UI; the
  picker accepts JPEG / PNG / WEBP and the shared compressor scales to
  256 px on the longest edge.
- **Emoji picker shows base glyphs only.** Variants (skin tone × gender,
  ZWJ family combinations) are parsed from the CSVs and kept on the
  in-memory `Emoji` model, but the picker UI doesn't yet surface them
  via long-press popup like Android. ~1,889 base glyphs across 10
  categories are pickable.
- **Composite-build vs GitHub Packages:** see "Why GitHub Packages..."
  above. Means a published `ospchat-shared` release is required to pick up
  shared-module changes (no `mavenLocal` short-circuit).
- **No tests in this module yet.** Shared has 25 tests; desktop has 0.
  The shape of the desktop UI is largely UI-only and Compose UI tests on
  desktop are a known footgun (Skiko + headless).
- **Installer `packageVersion` is rewritten** when `VERSION` starts with
  `0.`: jpackage rejects MAJOR=0, so `0.1.3` becomes `1.1.3` purely for
  the installer metadata. The runtime `BuildInfo.VERSION` (shown in About)
  still reads the real value verbatim.

## Suggested next steps

1. **Emoji variant popup.** Long-press an emoji in the picker → show the
   skin-tone × gender variants from `Emoji.variants`. Data is already
   parsed; only the UI affordance is missing.
2. **Notification-tap routing.** Surface a `SharedFlow<NotificationTap>`
   from `DesktopMessageNotifier`, and have `MainRoot` switch `screen` to
   the matching chat on emit. Brings the desktop closer to Android's
   deep-link UX.
3. **Compose Desktop UI tests.** A minimal smoke covering nickname-prompt
   → peer-list dispatch would catch regressions in `AppController`
   wiring without needing a real Skiko renderer (test the controller
   directly, then a couple of UI tests via `runComposeUiTest`).
4. **Drag-and-drop image attach** into ChatScreen (AWT drop target →
   `controller.sendImageAttachment`).
5. **Crash-safe shutdown for in-flight DB transactions.** Today's 800 ms
   killer can interrupt a Room write if it lands at the wrong moment.
   Add a `database.runInTransaction { }` guard around message-sending
   batches if we ever start writing larger groups.
6. **`gradle/wrapper/`** committed — currently the Makefile depends on a
   system Gradle. Bootstrap a wrapper to match `ospchat-android`'s
   convention.
