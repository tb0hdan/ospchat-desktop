# Changelog

All notable changes to OSPChat are recorded here. The format roughly follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
semantic versioning.

## [Unreleased]

### Changed

- **Outbound message status uses checkmarks instead of plain lowercase
  text.** Both `ChatScreen.MessageBubble` and
  `GroupChatScreen.GroupBubble` previously rendered
  `message.status.name.lowercase()` ("sending", "delivered", "read",
  "failed") in the bubble footer. Now they match Android's symbols:
  `Sending…` (faded textColor) → `✓` (faded) → `✓✓`
  (`MaterialTheme.colorScheme.primary`, for READ in 1:1 only — group
  messages have no per-member read tracking, so the group bubble stops
  at DELIVERED). FAILED renders as `⚠ Not delivered` in error color
  on both. The 1:1 string differs from Android's `"⚠ Tap to retry"`
  because the desktop client has no retry affordance wired yet —
  promising a tap action that doesn't exist would be worse than the
  symbol parity gain. Wiring retry is a separate follow-up.

### Added

- **Fullscreen image preview in chat.** Clicking an image attachment in
  `ChatScreen` opens `FullscreenImageOverlay` — a black-backdrop
  in-window overlay that renders the image with `ContentScale.Fit`.
  Click anywhere or press Escape to dismiss. Mirrors
  `ospchat-android`'s `FullscreenImageDialog` (minus pinch-zoom/pan,
  which doesn't translate to mouse and was deferred). Implemented via
  `Popup(properties = PopupProperties(focusable = true, ...))` rather
  than `Dialog`, so it stays in the same OS window (a `Dialog` on
  Compose Desktop opens a separate window — wrong feel for a
  chat-attachment preview). `focusable = true` also gives us native
  Escape / back-press dismissal without an explicit key handler.

### Fixed

- **Group-chat screen no longer strands the user when the group goes away.**
  When `controller.leaveGroup` runs, its background `broadcastLeave` can
  take several seconds blocking on unreachable peers before
  `applyLocalLeave` finally deletes the row. If the user navigated back
  into the same group during that window, the `observeOne(groupId)` flow
  would eventually emit null and the chat screen rendered a full-area
  `"Group no longer exists"` Box with no Back affordance and no
  NavigationRail (the `Screen.GroupChat` branch in `MainRoot` doesn't
  include the rail), trapping the user with no way out short of
  restarting. The branch now pops `screen = Screen.Main` automatically
  when the group transitions from loaded → null, using a
  `remember(groupId) { mutableStateOf(false) }` "have we ever seen this
  group" flag so the initial pre-load null doesn't trigger a spurious
  pop. As a safety net for the race where the group is already gone
  *before* the Flow's first emission, a 200 ms `delay` filters the
  initial null and pops if it's still null afterward.

### Added

- **Leave-group UI affordance.** The shared `LeaveGroupUseCase`
  (which broadcasts `POST /v1/groups/leave` then runs
  `GroupRepository.applyLocalLeave` to purge the group entity + messages)
  was already published by `ospchat-shared` and consumed by Android, but
  desktop had no UI to invoke it. Two entry points now exist:
  (1) a `MoreVert` kebab in the `GroupChatScreen` top bar opens a
  dropdown with "Leave group", and (2) right-clicking a row in
  `GroupsScreen` opens the same item. Both surfaces are hidden when
  `group.isCreator` — the shared use case explicitly disallows
  creator-leave in v1 ("the UI hides the option") so showing the
  affordance would be a no-op. No confirmation dialog (matches Android
  parity). `AppContainer` now lazy-builds `LeaveGroupUseCase` from the
  existing `groupRepository`, `database.groupDao()`, and
  `groupBroadcaster` providers; `AppController.leaveGroup(groupId)` is
  fire-and-forget on the IO scope. From the chat screen the caller
  pops to `Screen.Main` *before* invoking the controller, so the
  "Group no longer exists" fallback doesn't flash for one frame
  between `applyLocalLeave` deleting the row and the screen state
  catching up.

### Changed

- **Context menus now open on right-click instead of long-press.** The
  contacts/peers list (`PeersScreen.PeerRow`) and chat message bubbles
  (`ChatScreen.MessageBubble`) previously inherited the Android touch
  idiom of `combinedClickable(onLongClick = ...)`, which on desktop
  required the user to hold the *left* mouse button down for ~500 ms.
  Both call sites now use the desktop-only
  `Modifier.onClick(matcher = PointerMatcher.mouse(PointerButton.Secondary))`
  so the Add/Remove/Info dropdown on a peer row and the reactions emoji
  picker on a bubble open on a normal right-click. Left-click on a peer
  row still opens the chat; left-click on a bubble is now a no-op
  (previously also a no-op via `combinedClickable(onClick = {})`).
  Reaction chips' no-op `onLongClick` was dropped in favour of plain
  `clickable`. The stale comment in `PeersScreen` claiming
  `combinedClickable` mapped the secondary button to `onLongClick` on
  desktop was incorrect — Compose's `combinedClickable` only triggers
  `onLongClick` from a held primary button or a real long-press touch
  gesture, never from a secondary mouse click.

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

