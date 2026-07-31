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

### The headless-WebView freeze, and how it's worked around

Chromium freezes a backgrounded page's entire JS timer/task queue once it's
had no window attached for a while — confirmed live via Chrome DevTools
Protocol: `Date.now()` kept advancing and synchronous `evaluateJavascript`
calls kept executing instantly, but a plain `setTimeout()` never fired even
75 seconds later, and `WebView.resumeTimers()` doesn't undo it. Only genuine
window-attachment (opening the app) reliably un-freezes it. That means
PhantomChat's own relay-pool reconnect/catch-up logic — which depends on
timers — can't run while the WebView is headless, even though its relay
WebSocket connections still report "connected".

Since neither an invisible overlay window (tried, rejected — see "Bugs
found") nor FCM (rejected — no interest in standing up that infrastructure
for a hobby project) are on the table, the practical mitigation is
`RelayWakeListener.kt`: a small native OkHttp WebSocket client that watches
the account's own relays (read live from `window.__phantomchatPool.
getRelays()` — never hardcoded) for incoming gift-wrapped DM events (kind
`1059`, `#p` tagged to the account's pubkey), mirroring PhantomChat's own
subscription filter. It never decrypts anything — it only detects that a
matching event arrived. On detection it nudges the WebView (in case it's
still responsive enough to process it on its own) and arms a short-delay,
content-free fallback notification ("New PhantomChat message — open the app
to view it") if the real, decrypted notification doesn't beat it there. This
fallback is deliberately shaped as a full `MessagingStyle` notification with
a conversation shortcut — a plain notification doesn't get surfaced by
Android Auto at all (see "Bugs found").

**What this does and doesn't solve**: the driver reliably gets alerted that
a message arrived while backgrounded/driving (confirmed via DHU — Android
Auto reads the fallback alert aloud). Full decrypted content still requires
opening the app, since that's the only thing that's been confirmed to
actually un-freeze the page. This mirrors a privacy mode (`'A'`: "show
generic notification, never read privkey") already designed into
PhantomChat's own web-push feature, which is otherwise disabled pending a
relay server deployment this project deliberately isn't pursuing.

```
app/src/main/java/chat/phantomyard/auto/
  MainActivity.kt                  — hosts the WebView while the app is foregrounded
  service/
    PhantomAutoService.kt          — owns the single WebView; adaptive resume tick;
                                      builds/updates notifications (real + fallback)
    AndroidBridgeInterface.kt      — JS-to-Kotlin bridge (postMessage → Kotlin)
    RelayWakeListener.kt           — native relay watcher for the fallback notification
                                      (detects, never decrypts)
    ReplyReceiver.kt               — handles the RemoteInput reply action
    MarkReadReceiver.kt            — handles the invisible mark-as-read action
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

Either way installs straight to whatever device `adb` currently sees — the
emulator, or a physical phone once it's connected (see below).

The first time you launch the app, it shows PhantomChat's normal onboarding —
create a new identity or import an existing seed phrase/nsec, exactly as in
the web app (including QR-code import, once camera permission is granted).

### Testing on a physical phone

**1. Enable Developer Options + USB debugging on the phone**
- Settings → About phone → tap **Build number** 7 times ("You are now a
  developer")
- Settings → System → Developer options → enable **USB debugging**

**2. Connect it**
- Plug the phone into this machine via USB, then tap **Allow** on the "Allow
  USB debugging?" popup on the phone (optionally check "Always allow from
  this computer")
- Or, without a cable — Settings → Developer options → **Wireless
  debugging** → enable it → "Pair device with pairing code", which shows an
  IP:port and a 6-digit code:
  ```bash
  adb pair <ip>:<pairing-port>   # enter the 6-digit code when prompted
  adb connect <ip>:<connect-port>  # the main connect port shown on the same screen, not the pairing port
  ```

**3. Confirm it's visible, then build & install**
```bash
adb devices -l
./gradlew installDebug
```
Once installed, open "PhantomAuto" from the app drawer like any other app.

## Current status

Verified end-to-end with real Nostr relays (not mocked) and, for the
Android-Auto-specific pieces, against Google's Desktop Head Unit (DHU) with
a real phone:

- Identity persistence, live incoming messages producing notifications,
  notification replies actually sending, permission prompts, and boot-time
  recovery.
- **Android Auto now surfaces messaging notifications correctly.** The
  earlier "notification never appears in Android Auto" issue (see "Bugs
  found" for the full list of fixes that got it there) is resolved — the
  reply/mark-as-read semantic actions, conversation shortcut, and
  `automotive_app_desc.xml` declaration were the missing pieces.
- **Background reliability**: switched the foreground service type from
  `dataSync` (which has a hard 6-hour-per-24-hour runtime quota on Android
  15+) to `remoteMessaging` (built for exactly this, no such quota). An
  earlier attempt at background reliability via aggressive OS-level
  keep-alive hacks (a 12-hour partial wakelock, a WifiLock, a persistent
  invisible `SYSTEM_ALERT_WINDOW` overlay, and two redundant 30-second
  `online`-event spam loops) was deliberately stripped back out — see "Bugs
  found" — in favor of the `remoteMessaging` switch plus an adaptive,
  `CarConnection`-driven resume tick (1 minute while engaged with Android
  Auto, backing off to 12 minutes otherwise).
- **The headless-WebView freeze and its mitigation**: confirmed via CDP that
  a backgrounded WebView's JS timers stop firing entirely after a while
  (independent of the above), and confirmed that only real window-attachment
  (opening the app) reliably un-freezes it. `RelayWakeListener.kt` + a
  `MessagingStyle`-shaped fallback notification (see "How it works" above)
  is the practical mitigation — confirmed live via DHU: a real incoming
  message triggered detection, and Android Auto read the fallback alert
  aloud ("New PhantomChat message — open the app to view it"). Full
  decrypted content still requires opening the app.

**Known limitation, accepted for now**: full message content (sender name,
text) in the car requires opening the app once to let the WebView un-freeze
and process it — the fallback notification only tells you *that* a message
arrived, not what it says. Fixing that fully would mean either a push
server + native FCM integration (rejected — out of scope for this project)
or some other reliable way to force Chromium to un-freeze a truly headless
page, which hasn't been found.

**Possible future work**: a `CarAppService` + `ConversationTemplate` for a
richer, browsable in-car conversation screen, beyond notifications alone;
deduplicating `RelayWakeListener` detections more thoroughly (NIP-17
gift-wraps the same logical message separately per relay, so bursts of
several detections for one message are normal — a 90-second suppression
window after each fallback notification handles the common case, but isn't
a true per-message dedup).

An attempt to run this on an Android Studio emulator (AVD) hit a real
environment wall, unrelated to the app itself: Google Play refuses to
install the real Android Auto app there (`"This app isn't compatible with
your device anymore"`), regardless of having a signed-in Google account —
Play's device-compatibility filtering excludes most/all standard AVD
profiles for Android Auto specifically. The DHU binary is present at
`$ANDROID_HOME/extras/google/auto/desktop-head-unit` and pairs with a real
phone instead, per Google's [DHU
setup](https://developer.android.com/training/cars/testing#dhu).

## Bugs found during real-world testing

Real issues surfaced only once actually exercised end-to-end (emulator + a
second live identity, and later a physical phone), not from writing the code
alone:

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
4. **Top nav bar unreachable on a real phone (Fairphone 6).** Apps targeting
   API 35+ get edge-to-edge layout enforced with no opt-out — PhantomChat's
   own top nav (search bar, hamburger menu) was drawing underneath the status
   bar, where taps landed on the system bar instead of the page. Only showed
   up on real hardware, not the emulator. Fixed by padding `MainActivity`'s
   container view by the system bar insets (`ViewCompat.
   setOnApplyWindowInsetsListener` + `WindowInsetsCompat.Type.systemBars()`).
5. **Missing car-support manifest declaration.** The manifest was missing the
   `com.google.android.gms.car.application` meta-data / `automotive_app_desc.xml`
   declaration that tells Android Auto "this app's notifications should be
   surfaced in the car." Added `res/xml/automotive_app_desc.xml`
   (`<uses name="notification" />`) and referenced it from the manifest —
   necessary, but on its own not sufficient; see #6 for the rest of what was
   needed, confirmed together via DHU (see "Current status").
6. **Missing required semantic actions.** Per Android's [messaging
   notifications for Android
   Auto](https://developer.android.com/training/cars/communication/notification-messaging)
   guide, the reply action needs `setSemanticAction(SEMANTIC_ACTION_REPLY)` +
   `setShowsUserInterface(false)`, and there must be a separate **invisible**
   mark-as-read action (`setSemanticAction(SEMANTIC_ACTION_MARK_AS_READ)`,
   added via `addInvisibleAction(...)`, not `addAction(...)`). Both were
   missing; added the semantic action to the existing reply action and a new
   `MarkReadReceiver` + invisible action for mark-as-read.
   `NotificationCompat.Builder.extend(CarExtender(...))`, mentioned in some
   older/secondary sources, was checked against the current official doc and
   confirmed **not** required — skipped.
7. **`Person` objects missing `setKey()`/icon.** The official sample sets a
   stable `setKey()` and an icon on both the "me" and sender `Person` objects
   in `MessagingStyle`; ours had neither. Added both (key = the peer's
   pubkey, or `"me"`), matching the shortcut's `Person` so they're
   consistent. Together, #5-#7 turned out to be the complete fix — confirmed
   via DHU, Android Auto now reliably surfaces and reads these notifications.
8. **`dataSync` foreground service hits a hard 6-hour/24-hour runtime quota
   on Android 15+.** Diagnosed as the cause of the app "occasionally not
   being active" in real-world use. Fixed by switching
   `foregroundServiceType` from `dataSync` to `remoteMessaging` (built for
   exactly this: background text-message delivery, no such quota, no extra
   permission) in both `AndroidManifest.xml` and the
   `ServiceCompat.startForeground()` call.
9. **Aggressive keep-alive hacks caused more harm than they fixed.** A
   parallel round of changes (see git history) added a 12-hour partial
   `WakeLock`, a `WifiLock`, a persistent 2x2-pixel invisible
   `SYSTEM_ALERT_WINDOW` overlay, and two redundant 30-second
   `online`-event-dispatch loops (one native, one JS/silent-audio-based) —
   together causing ~2880 forced relay reconnects/day. All were removed.
   Re-tested on the leaner baseline (`remoteMessaging` + Doze-whitelist +
   battery-optimization-exemption prompt alone): messages still stopped
   arriving a few minutes after screen-off, confirming a separate,
   still-unaddressed cause (#10) rather than validating the removed hacks.
10. **Chromium freezes a headless WebView's JS timer/task queue.**
    Independent of process/Doze-level exemptions, confirmed live via Chrome
    DevTools Protocol: with the WebView backgrounded a while, `Date.now()`
    kept advancing and synchronous `evaluateJavascript` calls executed
    instantly, but a plain `setTimeout()` never fired even 75 seconds later,
    and `WebView.resumeTimers()` didn't undo it — only genuine
    window-attachment (opening the app) did, confirmed by checking whether a
    message that hadn't arrived in the background flushed in immediately
    upon opening the app (it did). This is why PhantomChat's own relay-pool
    reconnect/catch-up logic can't run while headless even with a healthy,
    "connected" WebSocket. Re-attempting the overlay-window approach from
    #9 specifically to keep the WebView window-attached was tried again and
    still didn't reliably prevent the freeze, so it was dropped for good
    rather than re-added in a leaner form. Mitigated (not fully solved) by
    `RelayWakeListener.kt` — see "How it works" and "Current status" above.
11. **A plain fallback `Notification` doesn't get surfaced by Android
    Auto.** The first version of the `RelayWakeListener` fallback alert used
    a bare `NotificationCompat.Builder` (title/text, no `MessagingStyle`).
    It showed correctly on the phone but Android Auto silently ignored it —
    the same requirement from #6 (`MessagingStyle` + a long-lived
    conversation shortcut) applies to this fallback too, not just real
    decrypted messages. Fixed by routing it through the same
    `notify()`/shortcut path as a real conversation, using a placeholder
    "conversation" (`GENERIC_ALERT_CONVERSATION_ID`) with no real peer
    behind it — confirmed via DHU, which then read the fallback aloud.
12. **Relay fanout re-triggered the fallback notification multiple times
    for one message.** NIP-17 gift-wraps the same logical message
    separately per relay (each copy gets its own random wrapper/event id
    for privacy), so `RelayWakeListener` legitimately detects several
    distinct event ids for what's really one message — confirmed live via
    DHU, which read the same fallback alert aloud three times for one test
    message. A first fix (only arm the fallback timer if none is already
    pending) was insufficient since detections spaced further apart than
    the fallback delay still each armed a fresh one. Fixed with a 90-second
    suppression window after a fallback notification fires, during which
    further detections don't re-arm it.
