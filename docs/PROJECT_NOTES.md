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
- Cross-network discovery (Tailscale, WireGuard overlays). Multi-NIC
  LAN discovery (Ethernet + Wi-Fi on the same host) is **in scope** as
  of phase 1 of the multi-network bridging plan — see "Suggested next
  steps" item 7. Cross-network (VPN-bridged) discovery remains deferred
  until phase 4.
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
    │       ├── CallStatusBar.kt               global active-call banner (peer + status/duration + hangup)
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

- 2026-05-26 — **unreleased**: **Phase 5 multi-network bridging (PR 3:
  relayed call signaling).** A1→D→A2 smoke test exposed that PR 2's
  TURN media relay alone couldn't bridge cross-network calls — the
  `/v1/call/offer` POST went direct to A1's `host=""` (gossip-only)
  peer entry and failed with `Connection refused` before any media
  negotiation. Fix: extend the same `toUuid` / `via` / `hopTtl`
  bridging fields phase 4 added to the message DTOs onto the 4 call
  DTOs. (1) All four `Call{Offer,Answer,Ice,Hangup}Dto`s gained
  nullable `toUuid`, `via`, `hopTtl`. Each `signaturePayload(signedAt)`
  appends `toUuid` only when non-null — append-only over the PR-2
  payload, so phase-3 peers and phase-5 peers verify each other's
  signatures byte-identically when no bridging is requested. (2) All
  four call route handlers in `MessageRoutes` now run the same
  `relayDecision` switch the phase-4 message routes use: Forward
  invokes `messageClient.sendCall<X>(decision.target, dto.copy(via=…,
  hopTtl=…))`; Refused/ConsumeLocal mirror phase 4 exactly. (3)
  `CallRepository` gains a `peerRouter: PeerRouter?` constructor
  param + `routeFor(targetUuid, fallbackPeer)` helper. `startCall(peer)`
  resolves a route via `peerRouter.routeTo(peer.uuid)` BEFORE creating
  the session and stamps `toUuid` on the offer DTO. `applyOffer`
  stores `originatorUuid = dto.fromUuid` in `PendingOffer`;
  `acceptCall` and `hangUp` re-route via
  `peerRouter.routeTo(originatorUuid)` so replies follow the same
  bridge path. `bindSession` carries `remoteUuid` + `toUuid` into
  `ActiveCall` so the ICE forwarder stamps `toUuid` on every outbound
  `CallIceDto`. (4) DI wiring updated on both platforms: desktop
  `AppContainer.callRepository`, Android Hilt `provideCallRepository`
  both pass `peerRouter`. (5) OpenAPI bumped 0.13.0 → 0.14.0;
  description blurb now spells out that phase-3 (media) + phase-5
  (signaling) are *both* required for cross-network voice. All 175
  shared tests still pass; both consumer compilations green. **Next
  smoke test:** re-run A1→D→A2 to verify the signaling now hops
  through the desktop bridge and media relays via TURN.
- 2026-05-26 — **unreleased**: **Phase 3 multi-network bridging
  (PR 1.5 + PR 2: consumer wiring + wire protocol).** (1)
  `AppContainer.turnServer = OspChatTurnServer()`; `AppController.start`
  starts it when `relayEnabled=true`; `AppContainer.shutdown` stops it.
  (2) All 4 call DTOs (`Call{Offer,Answer,Ice,Hangup}Dto`) gained
  nullable `signedAt` + `signature`; new signed `RelayCredRequestDto`
  / `RelayCredResponseDto`; 6 new `SignatureDomain` constants
  (`ospchat-v3/calls/*`); `MessageClient` signs all 6 and exposes
  `getRelayCred(bridge, request)`. (3) New `POST /v1/call/relay-cred`
  route in `MessageRoutes` — issues TURN credentials via
  `TurnCredentialService.issueAll(fromUuid)` when the bridge has
  `relayEnabled=true` and the TURN server is running; signs the
  response with the node's signing keypair. 503 `relay_unavailable`
  / 403 `relay_denied` for the failure modes. (4)
  `CallRepository.fetchRelayIceServers(selfUuid)` — speculative prefetch
  from the first peer in `RelayBridgeRegistry` before
  `sessionFactory.create()`. Failure → empty list = LAN-only
  (graceful degradation, no regression). (5)
  `AudioCallSessionFactory.create(iceServers: List<IceServerConfig>)`
  threaded into both `JvmAudioCallSession` and `AndroidAudioCallSession`;
  `RTCConfiguration.iceServers` populated from the list. (6)
  `MessageServer` accepts `turnCredentialService` parameter and threads
  it + the `signingKeyPairProvider` into `installMessageRoutes`. (7)
  `AppContainer.callRepository` wired with `relayBridgeRegistry`;
  `messageServer` wired with `turnCredentialService = turnServer`. (8)
  About-screen toggle copy updated: "Relay for contacts (messages +
  voice)" — explains TURN's E2E-encrypted property. (9) OpenAPI bumped
  0.12.0 → 0.13.0; new `/v1/call/relay-cred` path + `RelayCredRequest` /
  `RelayCredResponse` schemas; signing fields added to all 4 call
  schemas; new error codes `relay_denied` / `relay_unavailable`
  documented. All 175 shared tests pass; both consumer compilations
  green. **Phase 3 complete end-to-end.** Real-LAN smoke-test is the
  natural next step (call between two desktops with a third acting as
  bridge).
- 2026-05-26 — **unreleased**: **Phase 3 multi-network bridging
  (PR 1: embedded TURN protocol foundation, shared-only).** Pure
  Kotlin RFC 5766 subset landed in `ospchat-shared` under
  `com.ospchat.shared.turn.*`. (1) STUN/TURN codec in commonMain:
  `StunMessage`, `StunAttribute` (sealed: USERNAME, REALM, NONCE,
  MESSAGE-INTEGRITY, FINGERPRINT, ERROR-CODE, UNKNOWN-ATTRIBUTES,
  XOR-MAPPED/PEER/RELAYED-ADDRESS, LIFETIME, REQUESTED-TRANSPORT,
  CHANNEL-NUMBER, DATA, DONT-FRAGMENT), `StunCodec` with
  `encodeWithMacAndFingerprint` / `verifyMessageIntegrity`, plus a
  pure-Kotlin CRC32 for FINGERPRINT. (2) `ChannelData` framing for
  the alternative RFC 5766 §11.5 short-form. (3) Allocation state
  in `TurnAllocation` + pure handlers in `TurnProtocol` that emit a
  list of `TurnAction`s for the platform server to execute —
  fully test-isolatable. (4) TURN-REST-API credentials
  (`username = "<expirySec>:<uuid>"`,
  `credential = HMAC-SHA1(per-process-secret, username)`),
  TTL 5 min, in `TurnCredentials` + `Base64Mini`. (5)
  `TurnCredentialService` commonMain interface. (6)
  `OspChatTurnServer` (duplicated identically in `desktopMain` +
  `androidMain` per the existing bouncycastle pattern) wraps
  `java.net.DatagramSocket` + coroutines; one main socket on port
  3478 (falling back to ephemeral) + one relayed socket per
  allocation; sweeper coroutine prunes expired allocations every
  30 s. Binds on every UP non-loopback IPv4 interface, matching
  `JmDnsPeerDiscovery.pickLocalAddresses`. (7) `HmacSha1`
  expect/actual primitive added because BouncyCastle isn't on the
  commonMain classpath but STUN MESSAGE-INTEGRITY needs HMAC-SHA1.
  Tests: 33 new (`StunCodecTest` 16, `TurnProtocolTest` 17) cover
  codec round-trip, type encoding, XOR-MAPPED-ADDRESS masking, MAC
  generate/verify/tamper-detect, FINGERPRINT CRC, 401 challenge /
  nonce flow, every handler's happy path + each failure mode,
  credential staleness, ChannelData ↔ STUN demux, Base64 round-trip.
  **No wire-protocol change yet** — OpenAPI still 0.12.0; no new
  routes, no new DTOs, no signed call DTOs. PR 2 (deferred):
  `/v1/call/relay-cred` route, signed `RelayCredRequest/ResponseDto`,
  signing the 4 existing call DTOs (`Call{Offer,Answer,Ice,Hangup}Dto`
  — currently the only body-bearing endpoints still unsigned per
  phase 2b notes), `CallRepository.fetchRelayIceServers()`, threading
  `iceServers: List<IceServerConfig>` through `AudioCallSessionFactory.create()`.
  **Consumer wiring (PR 1.5) deferred** until shared is published —
  desktop `AppContainer.turnServer = OspChatTurnServer()` + start in
  `AppController` when `relayEnabled=true` + stop in `shutdown()`;
  android Hilt provider + `DiscoveryForegroundService` lifecycle.
  ice4j evaluation: confirmed ice4j is an ICE *client* library, no
  embedded TURN server class — custom implementation was the only
  pure-JVM path that runs on both desktop and Android. jitsi/turnserver
  exists but isn't on Maven Central and is unmaintained; coturn is
  desktop-only (native binary).
- 2026-05-25 — **unreleased**: **Phase 4 consumer-side shared
  foundation.** (1) `MessageRoutes.verifiedPeerOrRespond` consults
  the gossip cache as a fallback when the inbound `fromUuid` isn't in
  direct discovery — synthesizes a phantom [Peer] (with sentinel
  `GOSSIP_PHANTOM_HOST = ""`) so signature verification + repository
  receive both work for relayed messages. (2) New `RelayBridgeRegistry`
  (in-memory) tracks which directly-discovered peers advertised
  `relayEnabled=true`. (3) New `PeerRouter.routeTo(targetUuid)`
  resolves to direct, bridged, or unreachable — direct wins; bridged
  requires a relay-enabled-AND-reachable bridge that vouches via
  gossip. (4) `MessageRepository.sendToUuid(targetUuid, body, ...)`
  is the new outbound entry point that handles direct and bridged
  uniformly; sets `toUuid` on the DTO when routing. (5)
  `MessageRepository.receive` auto-creates a `PeerEntity` row for
  gossip-only senders so the conversation surfaces in the UI without
  manual setup. Attachment download is skipped for phantom senders
  (`peer.host == GOSSIP_PHANTOM_HOST`) — relayed attachment fetch
  deferred. Tests: 7 new `PeerRouterTest` cases. **Per-platform
  consumer wiring (DI graph, Settings opt-in toggle, UI for gossiped
  peers) is the next step** — desktop's `AppContainer` and the
  `PeersScreen` / Settings UI changes haven't landed yet.
- 2026-05-25 — **unreleased**: **Phase 4 multi-network bridging
  (server-side foundation).** The wire format for message-level relay
  through multi-homed peers shipped in `ospchat-shared`. (1) Seven
  signed DTOs gained nullable `toUuid` (signed, append-only payload
  extension that's byte-compatible with phase 2b when null), `via`
  (intermediates append), and `hopTtl` (intermediates decrement).
  (2) `/v1/info` returns `peers: List<GossipedPeerDto>` (uuid +
  nickname + pubkey for everyone the responder sees, capped at 64) +
  `relayEnabled: Boolean`. (3) `MessageRoutes` has a new
  `relayDecision` helper and forwards when `toUuid != self.uuid`,
  with hop-TTL / loop-detection / opt-in checks and new error codes
  `relay_refused` / `relay_unroutable`. Source-IP check is skipped
  for signed requests — identity is the signature, not the source IP.
  (4) New `GossipedPeerStore` (in-memory, TOFU pubkey pinned) is
  populated from each `/v1/info` fetch via `PeerAvatarSync`. (5)
  `IdentityRepository.relayEnabledFlow` + `setRelayEnabled` persist
  the user-facing opt-in flag. (6) OpenAPI 0.12.0 documents
  everything; `docs/SECURITY.md` F10 captures the relay trust model
  (signatures protect identity + body, intermediates can drop /
  observe metadata / gossip false peers in the pre-pin race window).
  Tests: 17 new (9 backwards-compat invariants, 8 gossip store).
  **Consumer-side bridge routing (selecting a bridge, setting toUuid
  on outbound, routing through the bridge instead of direct) is NOT
  in this PR** — wire format + server-side forwarding land first so
  the foundation can be smoke-tested before the per-platform UI /
  send-pipeline changes are wired.
- 2026-05-25 — **unreleased**: **Phase 2b consumer wiring verified.**
  After consumers picked up `ospchat-shared:0.2.8` and the
  `AppController` preload edit, desktop startup logs
  `pk=<first-16-of-b64> persistedPins=<count>` confirming both the
  per-install Ed25519 pubkey is populated (phase 2a) and the
  persistent TOFU pin map was warmed from Room before
  `peerDiscovery.start()` (phase 2b). F9 hijack defence is now
  load-bearing across restarts. The signed-DTO path runs in
  tolerate-unsigned rollout mode, so DM + group + call flows
  continue unchanged for peers on any 2b-or-prior build.
- 2026-05-25 — **unreleased**: **Phase 2b multi-network bridging**
  (signed DTOs + persistent pubkey pinning) landed in `ospchat-shared`.
  Seven DTOs gained nullable `signedAt` / `signature` fields:
  `IncomingMessageDto`, `ReadReceiptDto`, `ReactionDto`,
  `GroupSnapshotDto`, `GroupMessageDto`, `GroupSyncRequestDto`,
  `GroupLeaveDto`. Each has a `signaturePayload(signedAt): ByteArray`
  extension that hashes the body via a new
  `com.ospchat.shared.crypto.SignaturePayloadBuilder` — length-prefixed
  binary concatenation with a per-DTO domain prefix (no JSON
  canonicalisation, no cross-DTO replay). `MessageClient` signs every
  outbound DTO (idempotent: if `signature` is already set, e.g.
  mesh-fan-out forwarding, it's left intact). `MessageRoutes`
  verifies every signed inbound DTO against the sender's pinned
  pubkey with a ±5-minute replay window — two new error codes
  `signature_invalid` and `signature_replay`. Persistent pinning via
  Room migration v10 → v11 adds `peers.pub_key TEXT NULL`;
  `PeerDao.loadPinnedPubkeys()` warms the discovery service's
  in-memory pin map at boot via the new
  `PeerDiscoveryService.preloadPinnedPubkeys(Map<String,String>)`.
  `protectedInsert` consults the persistent pin even when no live
  peer entry exists yet — closes the post-restart mDNS race that
  phase 2a's in-memory-only pin couldn't cover.
  `docs/SECURITY.md` F9 marked **FULLY MITIGATED**. OpenAPI bumped
  to 0.11.0. Phase 2b ships in **tolerate-unsigned mode** — receivers
  log WARN and accept unsigned DTOs so pre-2b peers still
  inter-operate during the rollout window. A follow-up release flips
  to reject-on-absent. Tests: 24 new (11
  `SignaturePayloadBuilderTest`, 9 `DtoSignatureTest`, 4 phase-2b
  pin cases in `PeerCapTest` — total now 23). Migration count test
  updated from 9 to 10. Call signaling and binary fetches remain
  unsigned; they move under signing in phase 3.
- 2026-05-25 — **unreleased**: **Phase 1 + 2a verified on real LAN.**
  Bidirectional voice calls (Android ↔ Desktop) and 1:1 text chat
  exercised end-to-end after consumers were bumped to
  `ospchat-shared:0.2.8`. Asymmetric-discovery bug (Android couldn't
  see desktop because the legacy single-interface `pickLocalAddress()`
  bound to a network the Android peer wasn't on) is gone. No F9
  pkh-mismatch false positives — multi-NIC peers present the same
  pubkey on every interface, so phase 2a's hijack guard merges
  legitimate alternates without churn. Smoke covered: Linups desktop
  bound to multiple NICs; Thorus (Android) on the 192.168.4.x subnet;
  Linux laptop on the 10.0.0.x subnet. All three discover each other;
  all pairwise calls and 1:1 chat work.
- 2026-05-25 — **unreleased**: **Phase 2a multi-network bridging**
  (identity infrastructure) landed in `ospchat-shared`. (1) New
  `com.ospchat.shared.crypto.SigningCrypto` (expect/actual over BC's
  lightweight Ed25519 API; `bcprov-jdk18on:1.78.1` added to both
  `desktopMain` and `androidMain` source sets) generates and verifies
  Ed25519 keypairs. (2) `IdentityRepository.ensureSigningKeyPair()`
  generates the per-install keypair on first run, persists the seed
  (b64) in DataStore, returns the same pair on subsequent calls.
  (3) `JmDnsPeerDiscovery.start` and `NsdPeerDiscovery.start` gained
  a `publicKeyB64: String?` parameter; when non-null they advertise
  `pk=<b64>` in the mDNS TXT record. (4) `Peer` gained
  `publicKey: String?`; `protectedInsert` gained the pubkey-pinning
  matrix — first-seen pubkey is pinned per UUID, subsequent
  mismatches return new `DROPPED_PKH_MISMATCH`. **F9 restored** for
  the in-session window (`docs/SECURITY.md` F9). Phase 1's
  candidate-list relaxation no longer leaves an attack surface against
  honestly-multi-NIC peers — they all advertise the same `pk`.
  (5) `InfoDto.publicKey` + `ServerIdentity.publicKeyB64` +
  `MessageServer.start(publicKeyB64)` thread the key through `/v1/info`.
  No DTO signatures yet (that's phase 2b — persistent pinning + signed
  payloads). Tests: SigningCryptoTest (9 new), IdentityRepositoryTest
  +1, PeerCapTest 11 → 19 covering every pubkey-pinning matrix cell.
  All passing.
- 2026-05-25 — **unreleased**: **Phase 1 multi-network bridging**
  shipped in `ospchat-shared` (see "Suggested next steps" item 7 for
  the full four-phase plan). Three coupled changes:
  (1) `JmDnsPeerDiscovery` enumerates every UP, non-loopback IPv4
  interface and creates one `JmDNS` per address — a host with both
  Ethernet and Wi-Fi (or LAN + a VPN overlay) is now advertised on
  every interface. Drops the misleading `isVirtual` filter (Java's
  `NetworkInterface.isVirtual()` means "sub-interface", not
  "TUN/TAP"). (2) `Peer` carries a non-empty `List<Endpoint>`;
  `protectedInsert` merges same-UUID resolutions at different hosts
  into the candidate list sorted by RFC1918 > CGNAT > public, capped
  at `MAX_CANDIDATES_PER_PEER = 8`. The F9 hijack rejection is
  relaxed pending phase 2 (signed advertisements) — see
  `docs/SECURITY.md` F9. (3) `MessageClient` walks candidates in
  preference order on connect failures before falling back to
  `forgetPeer` + rediscover; `MessageRoutes` source-IP trust matches
  against any candidate, not just primary. No wire / OpenAPI change.
  Tests in `PeerCapTest` extended from 5 to 11 cases covering
  candidate merge, preference sort, candidate cap, and tier
  classification.
- 2026-05-21 — **unreleased**: fixed Android → Desktop calls hanging at
  `Connecting…` (the reverse direction of the 2026-05-20 fix below).
  Symptom: Desktop's call log showed `bufferedIce=0` on accept, no
  `applyIce ←` arrived, and Desktop emitted only a single TCP-passive
  host candidate before `NEGOTIATING` stalled until Android hung up.
  Root cause in `media/JvmAudioCallSession.kt` (and mirrored on the
  Android side): the local-ICE `MutableSharedFlow` was constructed
  with `replay = 0` and `extraBufferCapacity = 64`. With `replay = 0`,
  `tryEmit` against a flow with zero subscribers is silently discarded
  — `extraBufferCapacity` only buffers for *existing slow subscribers*.
  libwebrtc's signaling thread starts firing `onIceCandidate` the
  instant `setLocalDescription` returns inside `createOffer` /
  `acceptOffer`, well before `CallRepository.bindSession`'s
  `scope.launch { collect { … } }` has scheduled its collector. Fast
  gathering (1-2 interfaces on Android) loses every candidate; slow
  gathering (4+ interfaces on Desktop) loses early ones but enough
  late ones survive — hence the working Desktop → Android, broken
  Android → Desktop. Fix: switch to `replay = 64`. No wire change.
- 2026-05-21 — **unreleased**: bumped `ospchat-shared` to `0.2.4` for
  detailed ICE / call-signaling logging. The shared `CallRepository` and
  the `/v1/call/*` Ktor routes now log every offer, answer, ICE candidate
  (local + remote), and call state transition with the `callId` for
  cross-side correlation, plus the candidate string itself (so a
  CHECKING-forever ICE case is diagnosable from logs without attaching a
  debugger). Logging only; no wire / behavioural change. Used to diagnose
  intermittent bidirectional ICE failures.
- 2026-05-21 — **unreleased**: NavigationRail is now persistent chrome.
  Previously it only lived inside `Screen.Main`; navigating into Chat /
  GroupChat / InCall replaced the entire content area and the user lost
  the tab switcher. The rail is now rendered at the top level of
  `MainRoot` (in the same `Column` as the `CallStatusBar`), and clicking
  any rail item from a sub-screen pops `screen` back to `Screen.Main`
  with the clicked tab selected. On `Screen.InCall` specifically this
  also reveals the `CallStatusBar` (which suppresses itself only on
  `Screen.InCall`) — so the rail doubles as the "leave the full call UI
  but keep the call running" affordance. `selectedTab` is hoisted out
  of `MainShell` into `MainRoot` so it survives the Chat→Main
  round-trip; `MainShell`'s new signature is
  `(selectedTab, onTabClick, content: @Composable () -> Unit)` — the
  parent now dispatches on `selectedTab` directly instead of receiving
  it through the content slot, and the old `MainShell { tab -> … }`
  convenience overload (which owned its own tab state) is gone.
- 2026-05-21 — **unreleased**: collapse of `Screen.InCall` is now
  fully user-driven. Earlier in this cycle a `LaunchedEffect(call.state)`
  auto-popped to `Screen.Main` on `CONNECTED`; that's gone. The persistent
  NavigationRail (also added this cycle) makes the explicit transition
  natural: any rail click pops InCall→Main (revealing the
  `CallStatusBar`), and the bar's `onClick` restores the full screen.
  Hangup / remote teardown still pop via their existing branches. Also
  swapped the bar's hangup IconButton for a plain Box+clickable at
  28 dp with an 18 dp icon. The intermediate `.size(28.dp)` IconButton
  attempt rendered at ~48 dp on screen because Material3 IconButton
  applies `LocalMinimumInteractiveComponentSize` (48 dp touch target):
  the size modifier only sized the red background, the layout slot
  stayed at 48 dp and visually dominated the avatar / text. Box +
  clickable bypasses the minimum-interactive enforcement entirely.
- 2026-05-21 — **unreleased**: added a global call status bar
  (`ui/CallStatusBar.kt`). Previously, once the user navigated off the
  full-screen `Screen.InCall` there was no UI indication a call was active
  and no in-app way to hang up without re-entering the call screen. The
  new bar renders above the screen `when` in `MainRoot` whenever
  `callRepository.activeCall` is non-null *and* the user isn't already on
  `Screen.InCall`, showing the peer avatar + nickname, the live label /
  duration (re-using shared `statusLabel(now)`), and a red hangup button.
  Tapping the bar (outside the hangup) routes to `Screen.InCall` so the
  user can return to the mute control. Implementation notes: wrapped the
  existing `when (screen)` in a `Column { CallStatusBar; Box(weight(1f)) {
  when … } }`; the weighted Box keeps `fillMaxSize()` in the child screens
  working since Column otherwise hands its non-weighted children infinite
  height. Consolidated `MainRoot`'s two `activeCall` `collectAsState`
  observations (one at the top for the bar + InCall branch, the trailing
  one for the incoming-call dialog) into a single declaration. The
  incoming-RINGING case is still routed exclusively to `IncomingCallDialog`
  — the bar suppresses itself in that state so the modal isn't competing
  with a passive banner.
- 2026-05-20 — **unreleased**: fixed Desktop → Android calls hanging at
  `Connecting…`. Symptom: Android logcat showed
  `D/JvmAudioCallSession: ICE connection state: CHECKING` and the call
  never reached CONNECTED, eventually NO_ANSWER-ing at 30 s; Android →
  Desktop worked. Root cause in `ospchat-shared`'s
  `CallRepository.applyIce`: the callee dropped every ICE candidate
  that arrived before the user tapped Accept (`val active = current
  ?: return`; `current` only gets created in `acceptCall`). A
  multi-interface desktop JVM (loopback + eth + wifi + docker/vpn)
  trickles its entire host-candidate set the moment
  `setLocalDescription` returns inside `createOffer` — well before the
  Android user accepts — so Android ended up with the answer SDP and
  zero remote candidates, and Desktop's STUN binding requests had no
  return path. ICE pairs stayed CHECKING one-way forever. The reverse
  direction usually worked because Android has fewer interfaces and
  Desktop's user accepts fast enough that some candidates squeak
  through after `current` is set. Fix in shared: `PendingOffer`
  grows a `pendingIce` buffer; `applyIce` appends to it while
  ringing; `acceptCall` drains the buffer into the session right
  after `acceptOffer` (which sets the remote description, so
  libwebrtc is ready to accept them). Wire-compatible — no OpenAPI
  change.
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
7. **Multi-network bridging — phased plan.** This supersedes the
   `Out of scope (deferred)` line above ("Multi-network discovery
   (Tailscale, multiple interfaces)"). Four phases, each independently
   shippable; implementation order is the listed order, and phases 2-4
   chain (4 needs 2, 3 needs 2). See `docs/SECURITY.md` F9 for the
   security trade-off active during phase 1.

   - **Phase 1 — Multi-NIC + candidate-list peer model (LAN-only, no
     wire change).** Today `JmDnsPeerDiscovery.pickLocalAddress()` (in
     `ospchat-shared`'s `desktopMain`) returns the **first**
     non-loopback IPv4 from an UP, non-virtual interface, so a host
     with both Ethernet and Wi-Fi is advertised on only one of them.
     The `isVirtual` filter is also misleading — Java's
     `NetworkInterface.isVirtual()` means "sub-interface" (`eth0:1`),
     not "TUN/TAP", so a VPN's `tailscale0` / `utun*` can silently win
     the enumeration race. Changes:
     - Enumerate UP / non-loopback / non-link-local interfaces and
       create one `JmDNS` per address, sharing a single listener.
     - `PeerDiscoveryService.Peer` carries a non-empty
       `List<Endpoint>` of candidates; `host` / `port` become computed
       getters returning the first (most-preferred) candidate so
       existing callers keep compiling unchanged.
     - `protectedInsert` becomes a merge: same-UUID resolutions at
       different `host:port` are *appended* and re-sorted by
       preference tier (RFC1918 > CGNAT 100.64.0.0/10 > public).
       Per-peer candidate cap (`MAX_CANDIDATES_PER_PEER = 8`) bounds
       DoS amplification.
     - The F9 hijack rejection in `protectedInsert` is deliberately
       relaxed in phase 1 — restored properly by phase 2. Until then,
       every cross-host same-UUID insertion logs at WARN so anomalies
       are visible in dev.
     - `MessageClient` walks `peer.candidates` in order on connect
       failure; only after every candidate is exhausted does it fall
       back to the existing `forgetPeer` + rediscover retry.
     - Inbound source-IP trust (`MessageRoutes.verifiedRequestingPeer`,
       `MessageRoutes.matchesPeerHost`) matches against *any* of a
       peer's candidates, not just the primary.
     - No OpenAPI / wire change.
   - **Phase 2 — Signed peer advertisements / signed messages.**
     Per-install Ed25519 keypair, public key published in `/v1/info`.
     Message DTOs (`IncomingMessageDto`, `ReactionDto`, group `*Dto`s)
     gain a signature field over a canonical body hash; receiver
     verifies against the peer's published public key (TOFU-pinned on
     first contact). Restores F9 properly: a same-UUID candidate from
     an unverified responder no longer becomes part of the peer's
     candidate set; only signed advertisements promote to "trusted
     endpoint." Backwards-compat path during rollout: ignore-unsigned
     for one release, then require signatures. OpenAPI bump.
   - **Phase 3 — TURN-as-ICE-relay for voice.** Embed a tiny TURN
     server (e.g. pion/turn, or coturn as a sidecar) in each OSPChat
     node, opt-in via a "relay for my contacts" flag. Caller's
     `RTCPeerConnection` ICE servers list grows to include the
     contact-as-TURN. No new application-layer protocol needed —
     libwebrtc already does DTLS-SRTP end-to-end *through* the TURN
     relay, so the relay sees encrypted media and can't tamper with
     call content. Authentication via short-lived TURN credentials
     issued from a new `/v1/call/relay-cred` endpoint, signed using
     the phase 2 keypair.
   - **Phase 4 — `via` relay for text / group messages.** Wire
     change: `IncomingMessageDto` / group DTOs gain
     `via: List<Uuid>?` (hop list, capped at 3) and `hopTtl: Int`.
     Each intermediate node forwards to the next hop based on its own
     peer list; receiver verifies the original sender via the phase 2
     signature regardless of how many hops the message took. Requires
     phase 2 first — without per-message signatures, a relay can
     trivially rewrite `fromUuid` or body. Adds idempotency-key +
     hop-loop detection. Relay-side rate limiter per source UUID
     (extend existing D-class limits).

   Explicitly **not recommended** as alternatives: unicast DNS-SD
   against a hosted DNS zone (same operational footprint as the phase
   3-4 relay path for less control); prescribing ZeroTier as the
   overlay (Android `VpnService` strips multicast, defeating the
   point); inventing a custom VIA semantic for voice (TURN is the
   right primitive — phase 3).
