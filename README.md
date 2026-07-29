# PhantomAuto

A native Android app that makes [PhantomChat](../phantomchat) — a client-side,
end-to-end encrypted Nostr messaging PWA — usable in the car through **Android
Auto**, while behaving exactly like the normal PhantomChat PWA when opened on
the phone.

Android Auto doesn't allow arbitrary WebView/PWA UI on the car display, but it
doesn't need to: incoming messages are surfaced as standard Android
`MessagingStyle` notifications with a voice-reply action, the same mechanism
WhatsApp/Signal/Telegram use. Android Auto reads them aloud and lets the
driver reply by voice automatically — no `androidx.car.app` / Car App Library
code required.

For the full design rationale — including two earlier approaches that were
tried and rejected — see [docs/PLAN.md](docs/PLAN.md).

## How it works

There is exactly **one** running PhantomChat session at all times: a single
`WebView` loading the real, unmodified PhantomChat PWA
(`https://chat.phantomyard.ai`), owned by a foreground service.

- **App open on the phone** — `MainActivity` reparents that WebView into its
  own layout. You're looking at the actual PWA, live — same session, not a
  reload.
- **App backgrounded / driving** — the WebView keeps running headlessly in
  `PhantomAutoService`. A small injected script (`assets/bridge.js`) listens
  for incoming messages via PhantomChat's own `ChatAPI` and turns them into
  native Android notifications; replying from a notification (including
  Android Auto's voice reply) calls back into the same PhantomChat instance
  to actually send it.

Because it's the same live instance either way, the phone-open and
car-notification behaviors can never diverge, and the app needs **zero
changes to the `../phantomchat` repo** — the bridge only depends on a global
(`window.__phantomchatChatAPI`) that PhantomChat already exposes for exactly
this kind of external integration.

```
app/src/main/java/chat/phantomyard/auto/
  MainActivity.kt                  — hosts the WebView while the app is foregrounded
  service/
    PhantomAutoService.kt          — owns the single WebView; builds/updates notifications
    AndroidBridgeInterface.kt      — JS-to-Kotlin bridge (postMessage → Kotlin)
    ReplyReceiver.kt               — handles the RemoteInput reply action
    BootReceiver.kt                — posts a reconnect prompt after reboot
app/src/main/assets/
  bridge.js                        — injected into the PWA; the only "glue" code
```

## Requirements

- [Android Studio](https://developer.android.com/studio) (bundles a
  compatible JDK). On macOS: `brew install --cask android-studio`.
- On first open, let Android Studio finish its SDK setup and Gradle sync —
  the Gradle wrapper is intentionally not committed (a wrapper script without
  its jar is worse than none) and gets generated automatically on first sync.

## Building & running

Open the project in Android Studio and hit **Run**, or from the command line
once the wrapper exists:

```bash
./gradlew installDebug
```

The first time you launch the app, it shows PhantomChat's normal onboarding —
create a new identity or import an existing seed phrase/nsec, exactly as in
the web app (including QR-code import, once camera permission is granted).

## Current status

Verified end-to-end with a real second PhantomChat identity, over real Nostr
relays (not mocked): identity persistence, live incoming messages producing
notifications, notification replies actually sending, permission prompts, and
boot-time recovery.

**Not yet verified**: behavior in an actual Android Auto head unit (voice
readout / voice reply), via Google's Desktop Head Unit (DHU) emulator — the
notification shape it needs (`MessagingStyle` + `RemoteInput`) is already
built and confirmed working, so this is a validation step rather than
outstanding app work.

**Possible future work**: a `CarAppService` + `ConversationTemplate` for a
richer, browsable in-car conversation screen, beyond notifications alone.

## Bugs found during real-world testing

Three real issues surfaced only once actually exercised end-to-end (emulator
+ a second live identity), not from writing the code alone:

1. **Bridge attach timeout too short.** The bridge's poll for
   `window.__phantomchatChatAPI` gave up after 30s; identity setup or a PIN
   unlock can easily take longer, permanently missing the connection since
   the page only loads once for the WebView's whole lifetime. Fixed: it now
   polls indefinitely.
2. **`onMessage` silently reassigned.** PhantomChat's own UI reassigns
   `chatApi.onMessage` whenever a chat view opens, discarding a naive
   one-time wrap around it. Fixed with a `defineProperty` getter/setter trap
   on `chatApi.onMessage` so the bridge's hook can never be silently dropped,
   regardless of how many times the app reassigns it.
3. **Foreground service can't start from `BOOT_COMPLETED`.** This Android
   version refuses to start a `dataSync`-type foreground service directly
   from a boot broadcast receiver — confirmed even when routed through an
   expedited WorkManager job. Fixed by having `BootReceiver` post a plain
   "tap to reconnect" notification instead of forcing a background start;
   tapping it opens the app, which starts the service through a normal,
   user-initiated launch.
