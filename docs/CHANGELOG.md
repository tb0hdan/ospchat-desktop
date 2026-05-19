# Changelog

All notable changes to OSPChat are recorded here. The format roughly follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
semantic versioning.

## [Unreleased]

### Fixed

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

