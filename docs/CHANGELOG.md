# Changelog

All notable changes to OSPChat are recorded here. The format roughly follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
semantic versioning.

## [Unreleased]

### Fixed

- **Peer-list flicker regression in `ospchat-shared:0.1.1` (fixed in
  0.1.2).** 0.1.1 made `MessageClient` call
  `DiscoveryRepository.forgetPeer` on TCP connection failures, and that
  hook on JmDNS desktop ran a *blocking* `JmDNS.list(SERVICE_TYPE)`
  call (up to ~6 s) from the MessageClient coroutine. On Android the
  equivalent hook bounced the entire NSD discovery, which combined
  with `DiscoveryForegroundService.peerSyncJob` (re-fires
  `PeerAvatarSync` + `GroupSyncer` on every snapshot delta) turned a
  single failed background HTTP call into an N-peer × M-call
  re-entry loop, manifesting as several-times-per-second
  appear/disappear churn on the Android peer list. 0.1.2:
  (a) `forgetPeer` is now surgical — Android re-resolves only the
  one peer via the existing resolve queue (no `stopServiceDiscovery`);
  desktop drops the blocking `JmDNS.list` and keeps just
  `requestServiceInfo`. (b) `MessageClient` per-method now takes a
  `rediscover: Boolean = true`; background flows
  (`PeerAvatarSync`, `GroupSyncer`, `PeerInfoNotifier`,
  attachment download) call with `false`, so only user-initiated
  sends can mutate the discovery snapshot on failure.
- **One-way messaging after desktop restart.** Restarting the desktop app
  used to break Android→Desktop sends until the user nudged Android into
  re-resolving the peer (toggle airplane mode, restart the app, etc.).
  Root cause was a two-part problem: (1) `MessageServer` always bound
  `port = 0` so each boot got a fresh ephemeral port, and (2) Android's
  NSD framework doesn't fire `onServiceFound` / `onServiceLost` for a
  port-only change on an existing service name, so its cached resolution
  pointed at the dead port indefinitely. Now: the bound port is
  persisted via `IdentityRepository.lastServerPort` and reused on the
  next start (falls back to ephemeral on `EADDRINUSE`), and
  `MessageClient` wraps every per-peer HTTP call in a one-shot
  rediscover-and-retry: on a TCP connect failure it calls
  `DiscoveryRepository.forgetPeer(uuid)` (which bounces NSD discovery
  / fires a JmDNS re-query), waits up to 3 s for a fresh resolution
  whose host:port differs from the failed address, and retries once.
  Application-level rejections (HTTP non-2xx) are not retried.

### Added

- **macOS Info.plist + signing scaffold (firewall groundwork).** The
  `.dmg` now declares a stable `CFBundleIdentifier`
  (`com.ospchat.desktop`), an `LSApplicationCategoryType`
  (`public.app-category.social-networking`), `NSBonjourServices`
  (`_ospchat._tcp`), and `NSLocalNetworkUsageDescription`. The bundle ID
  gives macOS' application firewall (ALF), Local Network privacy (TCC),
  and LaunchServices a fixed key to scope their decisions to instead of
  jpackage's synthesised identifier — previously each rebuild risked
  invalidating the user's already-clicked-through firewall answer.
  Forward-compat with macOS 15: NSBonjourServices is the system's
  whitelist of mDNS service types an app may discover, and the
  NSLocalNetworkUsageDescription is shown in the Sequoia local-network
  privacy prompt that gates JmDNS's outbound 5353 multicast. Inbound
  TCP (the Ktor `/v1/*` listener) is exempt from that prompt but still
  gated by ALF — which requires a real code signature to remember
  "Allow" across launches. The `macOS { signing { ... } }` block plus
  `macos/entitlements.plist` (network client/server + JIT + unsigned
  executable memory for Skiko) are wired but inert; pass
  `-PmacSigningIdentity="Developer ID Application: …"` to
  `gradle packageDistributionForCurrentOS` once a paid Developer ID
  cert is available and ALF can finally persist its decision. Until
  then the README documents the
  `socketfilterfw --add … --unblockapp` workaround for unsigned
  installs.
- **Branded installer icon across Linux/macOS/Windows.** Mirrors
  `ospchat-android`'s launcher (`#0F172A` background + the white-ish
  chat-bubble vector from
  `app/src/main/res/drawable/ic_launcher_foreground.xml`), recreated as
  `icons/icon.svg` and rendered to the three formats jpackage expects:
  `icon.icns` (macOS, multi-resolution 16…1024), `icon.ico` (Windows,
  multi-resolution 16…256), and `icon.png` (Linux, 512px). The
  `nativeDistributions { linux/macOS/windows { iconFile.set(...) } }`
  block in `build.gradle.kts` wires the matching file per host, so .deb
  / .dmg / .msi all ship with the OSPChat icon instead of the Compose /
  Kotlin default. A `make icons` target regenerates the binaries from
  `icons/icon.svg` (requires `librsvg2-bin`, `icnsutils`,
  ImageMagick).

### Fixed

- **macOS `.dmg` crash on first emoji-bearing screen.** The bundled
  `NotoColorEmoji.ttf` was loaded via Compose's `Font(resource = ...)`,
  which calls `Thread.currentThread().contextClassLoader` and asserts
  non-null. In the jpackage runtime image on macOS the AWT event
  thread's context classloader can be null, so the Kotlin null-check
  threw NPE inside `androidx.compose.ui.text.platform.typefaceResource`
  the moment any `OutlinedTextField` (or any other emoji-styled
  composable) entered the tree. `EmojiFont` now reads the TTF bytes
  through its own `Class.getClassLoader()` and passes them to the
  byte-array `Font(identity, data, ...)` factory, and wraps the lazy
  initializer in `runCatching` so a future load failure logs and falls
  back to `FontFamily.Default` instead of taking down the UI.
- **Bundled JRE now includes `jdk.unsupported`.** Intel macOS `.dmg`
  (and any installer built with the jpackage runtime image) crashed at
  startup with `NoClassDefFoundError: sun/misc/Unsafe`. The class lives
  in the `jdk.unsupported` module and is reached from
  `androidx.datastore.preferences.protobuf` (protobuf-lite). `jdeps`
  can't detect `sun.misc.*` usages, so jlink omitted the module from
  the bundled runtime. `build.gradle.kts` now declares
  `modules("jdk.unsupported")` in `nativeDistributions` so every
  installer ships the module.

### Changed

- **CI/release workflow actions bumped to Node 24 majors.** GitHub started
  warning that the Node 20 runtime is being retired (forced default
  switch on 2026-06-02, removal on 2026-09-16). `actions/checkout`,
  `actions/setup-java`, `actions/upload-artifact`,
  `actions/download-artifact`, and `actions/cache` were bumped from
  `@v4` -> `@v5`; `softprops/action-gh-release` from `@v2` -> `@v3`.
  `gradle/actions/setup-gradle` was bumped from `@v4` -> `@v5` and
  deliberately pinned there — `@v6` relicensed the caching component
  (`gradle-actions-caching`) as proprietary, so using it would
  implicitly accept https://gradle.com/legal/terms-of-use/. None of
  the upgrades change input contracts for how this repo calls them.
- **macOS release matrix now ships both architectures.** Added a
  `macos-13` (Intel) entry alongside `macos-latest` (Apple Silicon) in
  `.github/workflows/release.yml`. Each macOS runner passes
  `-PmacArch=x86_64|arm64` so the produced installer is named
  `OSPChat-<version>-<arch>.dmg`; both are attached to the GitHub
  Release. Previously only an Apple Silicon `.dmg` was published.
- **Installer `packageVersion` preserves minor/patch.** `build.gradle.kts`
  rewrites a `0.x.y` `VERSION` to `1.x.y` (e.g. `0.1.3` -> `1.1.3`)
  instead of the previous flat `1.0.0` shim, so installer artifact
  filenames track real releases. The runtime `BuildInfo.VERSION` shown
  in About still reads the real value verbatim.

### Added

- **Tray notifications for inbound messages.** `DesktopMessageNotifier`
  replaces the `NoOp` stub and posts via Compose's
  `TrayState.sendNotification` for both direct and group chats. The
  notifier suppresses delivery when the matching chat is on-screen,
  tracked through the shared `ActiveChatTracker` (fed by
  `DisposableEffect` on `ChatScreen` / `GroupChatScreen`). No-op
  fallback when the host has no tray (e.g. GNOME-Wayland without
  AppIndicator).
- **EXIF rotation for desktop image attachments.** New
  `ExifAwareImageCompressor` reads the EXIF Orientation tag via
  `com.drewnoakes:metadata-extractor:2.19.0` and bakes the rotation
  into the JPEG via `AffineTransform`, matching `AndroidImageCompressor`.
  Phone-shot images picked from a desktop now render upright on peers.
- **Full emoji picker.** Tabbed `EmojiPicker` over the Android-bundled
  emoji set (`androidx.emoji2:emoji2-emojipicker:1.5.0`, ~1,889 base
  glyphs across 10 categories vendored byte-for-byte under
  `src/desktopMain/resources/emoji/`, Apache 2.0). Used both for
  message reactions (long-press a bubble) and inline composer insertion
  (😊 button in `ChatScreen` and `GroupChatScreen`).
- **Bundled color emoji font.** Noto Color Emoji (SIL OFL 1.1, ~10 MB)
  vendored under `src/desktopMain/resources/fonts/NotoColorEmoji.ttf`,
  exposed through `EmojiFont.family` and applied to the picker cells,
  reaction chips, composer text field, and message bodies (via an
  `emojiAware` AnnotatedString helper that wraps emoji codepoint runs).
  Fixes monochrome contour rendering on hosts without a system emoji
  font (most Linux distros).

### Changed

- `AppContainer.imageCompressor` now uses `ExifAwareImageCompressor`
  instead of the shared `ImageIoCompressor`.
- `AppController` exposes `onPeerChatVisible / onPeerChatHidden /
  onGroupChatVisible / onGroupChatHidden` for active-chat tracking;
  `ChatScreen` / `GroupChatScreen` gained `onVisible / onHidden`
  callbacks wired from `MainRoot`.
- Dropped the static 12-emoji `EMOJI_CHOICES` reaction list in favour
  of the full picker.
- Resolve `com.ospchat:ospchat-shared:0.1.0` from the GitHub Packages Maven
  registry instead of mavenLocal — matches the `ospchat-android` consumer
  setup. Set `gprUser` / `gprToken` (PAT with `read:packages`) in
  `~/.gradle/gradle.properties`, or export `GITHUB_ACTOR` / `GITHUB_TOKEN`.
- `make shared-publish` and the `release` / `all` target dependencies on it
  removed; CI workflows no longer checkout `ospchat-shared` as a sibling
  and instead authenticate to GitHub Packages with the runner-provided
  `GITHUB_TOKEN`.

