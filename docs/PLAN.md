# PhantomAuto — native Android Auto companion for PhantomChat

This is the original design document written before implementation. It's kept
as-is (not updated after the fact) because it explains *why* the architecture
looks the way it does, including the two earlier approaches that were tried
and rejected. For what was actually found and fixed during implementation and
testing, see [README.md](../README.md#bugs-found-during-real-world-testing).

## Context

PhantomChat (`../phantomchat`) is a client-side, E2E-encrypted Nostr messaging PWA — no
servers, no accounts, keys held locally. The user wants to use it while driving, surfaced
through **real Android Auto** (phone → car-screen projection), while the app must also work
as a completely normal, fully-functional PhantomChat client when opened directly on the phone
(not connected to Android Auto at all).

Android Auto does not allow arbitrary WebView/PWA UI on the car display — but it doesn't
need to: the standard way messaging apps integrate (WhatsApp, Signal, Telegram) is plain
`NotificationCompat.MessagingStyle` notifications with a `RemoteInput` reply action. Android
Auto reads these aloud and lets the driver reply by voice automatically — no
androidx.car.app / Car App Library code required. User confirmed this "notifications only"
scope for v1.

**How this plan evolved** (kept here because it explains the design, not just the result):
1. First draft proposed porting PhantomChat's crypto ("v2" ECDH+HKDF+AES-GCM protocol) and
   relay-pool logic into Kotlin. The user correctly flagged this as rebuilding the PWA a
   second time — every future PhantomChat protocol change would need manual re-porting.
2. Investigation showed `ChatAPI` (`src/lib/phantomchat/chat-api.ts`) already exposes the two
   hooks needed (`onMessage` callback, `async sendText()`), and neither it nor
   `message-store.ts` touch React/DOM — so the Android app could drive the *real* PhantomChat
   code headlessly in a WebView instead of reimplementing it, inheriting all future protocol
   improvements automatically. This needed one small new file in `../phantomchat`, which the
   user approved.
3. The user then required that opening the app on the phone (no Android Auto) must behave as
   the real PWA — not a stripped-down setup screen. That pushed toward using **one single
   WebView instance** for everything (visible when the phone app is open, headless when
   backgrounded) rather than two separate WebViews/bundles, so there's exactly one live
   PhantomChat session, ever — no divergence between "the phone app" and "the car bridge" is
   even possible, because they're literally the same running instance.
4. That unification also revealed the `../phantomchat` change is no longer needed at all:
   `ChatAPI`'s constructor already exposes itself as `window.__phantomchatChatAPI`
   (`chat-api.ts:280-282`), and another module (`phantomchat-bridge.ts:301-311`) already
   attaches to that global late via a poll-and-retry pattern for its own purposes — a stable,
   pre-existing integration point. The Android app can inject a small bridge script against
   that same public global with **zero changes to the `../phantomchat` repo**.

## Key facts gathered

- **Identity import already exists, no new code needed**: `src/pages/phantomchat/
  onboarding.ts` (`PhantomChatOnboarding.showImport()`, ~line 186) accepts a pasted 12-word
  seed phrase, a raw nsec/hex key, or a QR scan, via `nostr-identity.ts`. Persistence goes
  through `key-storage.ts`. Default protection is `'none'` — **not plaintext**: even "none"
  mode AES-GCM encrypts the seed/nsec with a `crypto.subtle`-generated, **non-extractable**
  key (`generateBrowserScopedKey()`, key-storage.ts:44-50) that can't be exported even by JS.
  This is the right mode here — a background service can't prompt for a PIN while driving,
  and re-entering one after every process kill isn't workable — protection then rests on
  Android's per-app storage sandbox + device lock screen, the same boundary most messaging
  apps rely on locally.
- **Bridge integration point**: `window.__phantomchatChatAPI` (chat-api.ts:280-282, set
  unconditionally on construction), constructed at `src/pages/
  phantomchat-onboarding-integration.ts:185` inside `handleIdentity()`. `onMessage`
  (chat-api.ts:170, fired chat-api-receive.ts:548) and `async sendText(content, opts)`
  (chat-api.ts:691) are the two hooks the bridge needs.
- **Cold-start conversation listing**: composable from `message-store.ts`:
  `getAllConversationIds()` (L638) + `getMessages(id, 1)` + `countUnread(id, ownPubkey)`
  (L874) — no single exported helper exists, the bridge script does this small loop itself.
- Aside (not in scope): `phantomchat-push.ts` (L92-109) reads IndexedDB store `'peers'` for
  sender-name resolution, but the store is actually named `'mappings'`
  (`virtual-peers-db.ts` L21) — likely a pre-existing bug in the web-push path. Unrelated
  follow-up, not part of this build.

## Architecture

### `../phantomchat` — no changes

The bridge only depends on the already-public `window.__phantomchatChatAPI`, using the same
poll-and-retry attach idiom `phantomchat-bridge.ts:301-311` already uses. Nothing to add or
maintain on the PhantomChat side.

### `phantomauto/` — Android app (Kotlin), single shared WebView

```
app/src/main/java/chat/phantomyard/auto/
  MainActivity.kt         — binds to PhantomAutoService; when bound, detaches the service's
                             WebView from wherever it currently lives and attaches it into
                             this Activity's content view, so the user sees/interacts with
                             the live, fully-functional PhantomChat UI (https://chat.
                             phantomyard.ai) exactly as the PWA — same running JS session,
                             not a reload. Handles camera permission
                             (WebChromeClient.onPermissionRequest) for QR-scan onboarding,
                             requests POST_NOTIFICATIONS (Android 13+), and prompts to
                             disable battery optimization to ensure the background
                             connection stays alive during deep sleep. On stop/destroy,
                             detaches the WebView (does not destroy it) back to the service.
  service/
    PhantomAutoService.kt  — foreground service that owns the single WebView instance
                             (created with applicationContext to avoid Activity-context
                             leaks across reparenting), loads chat.phantomyard.ai once, and
                             keeps running/persisting it whether or not any Activity is
                             currently attached. Acquires a partial WakeLock to keep the CPU
                             awake (and thus the WebView bridge processing messages) when
                              the screen is off. Injects assets/bridge.js on page load
                             (WebViewClient.onPageFinished), which:
                               - polls for window.__phantomchatChatAPI (same retry idiom as
                                 phantomchat-bridge.ts)
                               - wraps (not overwrites) chatApi.onMessage to also call
                                 window.AndroidBridge.postMessage(JSON.stringify(...))
                               - defines window.sendReply(conversationId, text) and
                                 window.getConversationsSnapshot() using chatApi.sendText(...)
                                 and the message-store loop above
                             Registers addJavascriptInterface("AndroidBridge", ...) to
                             receive those postMessage calls, and on each one builds/updates
                             one MessagingStyle notification per conversationId (category
                             CATEGORY_MESSAGE, Person per sender, RemoteInput reply action).
                             Runs with a persistent low-priority "PhantomChat connected"
                             notification (required for background foreground-service
                             activity).
    ReplyReceiver.kt       — BroadcastReceiver for the RemoteInput action; takes the reply
                             text and calls evaluateJavascript("window.sendReply(...)") on
                             the (possibly headless) WebView
    BootReceiver.kt        — restarts the service on RECEIVE_BOOT_COMPLETED
```

No native crypto, no native WebSocket/relay code, no Kotlin protocol port, no second bundle.
Exactly one PhantomChat session ever runs; the phone-open and car-notification behaviors are
two presentations of that same instance.

### Notification shape (what makes Android Auto treat this as messaging)
- `NotificationCompat.Builder(...).setCategory(NotificationCompat.CATEGORY_MESSAGE)`
- `MessagingStyle` with one message per incoming DM, `Person` per sender
- Reply `Action` built with `RemoteInput.Builder` (required for Auto to offer voice reply)
- Group by `conversationId` (matches PhantomChat's own sorted-pubkey grouping)
- High-importance notification channel so Auto doesn't suppress it

## Trade-offs / risks, explicitly

- **Pro**: zero protocol duplication, zero changes to `../phantomchat` — the car app
  inherits every future PhantomChat improvement automatically because it *is* PhantomChat.
- **Pro**: exactly satisfies "behaves as the PWA when opened on the phone" — there is only
  ever one running instance, so there's no way for the two modes to diverge.
- **Risk**: reparenting a single `WebView` between a Service's lifecycle and an Activity's
  view hierarchy is a real Android implementation detail to get right — must create the
  WebView with `applicationContext` (not the Activity) to avoid leaks, and handle
  configuration changes (rotation) without tearing down the WebView. This needs early,
  dedicated real-device testing; it's the highest-risk single piece of this plan.
- **Risk/cost**: a Chromium WebView instance runs continuously in the foreground service —
  heavier CPU/RAM/battery than a lean native socket client. A persistent foreground
  notification (standard for background media/VoIP-style services) plus a partial
  WakeLock ensures survival under Doze/background restrictions, but increases idle
  battery drain. User-prompted exclusion from battery optimization is used to
  guarantee message delivery reliability.
- Camera permission for QR-based onboarding needs explicit handling in the WebView (`
  onPermissionRequest`) plus the Android runtime `CAMERA` permission — plain `WebView` doesn't
  grant this automatically the way Chrome does.

## Phasing

1. **Android scaffold** — Gradle project, `PhantomAutoService` owning the WebView, basic
   `MainActivity` that binds/attaches it (no bridge script yet) — confirm the reparenting
   pattern works reliably across app open/close/rotate before adding anything else.
2. **Bridge script** — `assets/bridge.js` (poll for `window.__phantomchatChatAPI`, wrap
   `onMessage`, define `sendReply`/`getConversationsSnapshot`), `addJavascriptInterface`
   wiring; verify by logging bridge payloads first, before building notifications.
3. **Notifications** — `MessagingStyle` + `RemoteInput` construction from bridge payloads,
   `ReplyReceiver` wired back to `window.sendReply`.
4. **Onboarding polish** — camera permission handling for QR scan, `POST_NOTIFICATIONS`
   prompt, boot receiver.
5. **Real-world verification** — confirm normal phone use (app open, no Android Auto) behaves
   identically to the web PWA; confirm messages sent from a separate browser tab/device
   arrive as notifications when the app is backgrounded; confirm replies from notifications
   are received on the other side; confirm survival through screen-off/Doze on a real device.
6. **Android Auto verification** — Google's **Desktop Head Unit (DHU)** emulator (enable
   "Unknown sources" in the Android Auto app's developer settings, since this won't be
   Play-Store-validated) to confirm read-aloud + voice reply.
7. **Future/optional** — a `CarAppService` + `ConversationTemplate` for an in-car browsable
   conversation screen, once the notification MVP is validated end-to-end.

## Verification plan
- Manual: open the app on the phone with no Android Auto connected, use it like the normal
  PWA (send/receive, settings, contacts) — confirm no behavioral gaps vs. a browser tab.
- Manual: background the app, send a message from another device/browser, confirm a
  `MessagingStyle` notification appears; reply from the notification, confirm delivery.
- Real-device background survival test (screen off, app backgrounded, Doze) — confirm
  messages still arrive as notifications.
- DHU-based Android Auto test: confirm the notification is read aloud and a voice reply is
  captured and sent correctly.
