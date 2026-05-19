# Changelog

All notable changes to OSPChat are recorded here. The format roughly follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
semantic versioning.

## [Unreleased]

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

