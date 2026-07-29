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

Verified end-to-end with a real second PhantomChat identity, over real Nostr
relays (not mocked): identity persistence, live incoming messages producing
notifications, notification replies actually sending, permission prompts, and
boot-time recovery.

**Not yet working: the notification doesn't actually surface in Android
Auto.** Tested live in a real car (Fairphone 6, real Android Auto session
with Maps/media active) with a bot pinging every minute. The notification
posts correctly and is confirmed received system-wide (it mirrors fine to a
Wear OS watch, and the phone's own notification-listener log shows it being
delivered), but it never appears in Android Auto itself.

What's been ruled out / already fixed while chasing this (see "Bugs found"
below for detail): missing `automotive_app_desc.xml` declaration, missing
semantic actions (`SEMANTIC_ACTION_REPLY` / `SEMANTIC_ACTION_MARK_AS_READ`),
missing invisible mark-as-read action, missing conversation shortcut,
missing `Person.setKey()`/icons. `CarExtender` was investigated and
confirmed **not** required by the current official docs. The AVD emulator's
Play-compatibility block (see below) is not the cause either — this was
tested on a real phone with the full, non-stub Android Auto app.

What's confirmed via `adb`:
- `adb shell settings get secure enabled_notification_listeners` shows
  `com.google.android.projection.gearhead/...SharedNotificationListenerManager$ListenerService`
  is registered and enabled, so Android Auto's own listener is live.
- There's no separate per-app "Notifications" toggle inside the modern
  Android Auto settings screens to check (that UI has apparently been
  removed/folded into the OS in current versions) — nothing to misconfigure
  there.
- `logcat` shows zero trace of gearhead's listener acting on the
  notification at all (only the launcher's own badge-counting listener logs
  receiving it) — but this is a release build with no verbose logging, so
  that's not conclusive proof gearhead ignored it vs. silently declining it.

**Next steps for whoever picks this up**: with everything in the official
docs now implemented, the remaining candidates are less certain — a
Fairphone/OEM-specific restriction on non-Play-verified apps, a real
Android Auto bug/limitation, or something in the exact notification/shortcut
shape still not quite matching what Android Auto expects that isn't spelled
out in the docs. Getting verbose logs out of the real `gearhead` app (e.g.
`setprop log.tag.<tag> DEBUG` if the right tags can be identified) or
testing against the DHU (see below) to get a friendlier debugging surface
would be the next avenues.

An attempt to run this on an Android Studio emulator (AVD) hit a real
environment wall, separate from the above: Google Play refuses to install
the real Android Auto app there (`"This app isn't compatible with your
device anymore"`), regardless of having a signed-in Google account — Play's
device-compatibility filtering excludes most/all standard AVD profiles for
Android Auto specifically, a known limitation independent of anything in
this project. The Play-delivered Android Auto app is also a stub whose real
functionality is a dynamic module fetched only once Play accepts the device,
so this blocks before DHU pairing even becomes relevant there. The DHU
binary is already present at
`$ANDROID_HOME/extras/google/auto/desktop-head-unit` on this machine and can
be paired with a real phone instead, per Google's [DHU
setup](https://developer.android.com/training/cars/testing#dhu) — this may
give better error visibility than a real car does.

**Possible future work**: a `CarAppService` + `ConversationTemplate` for a
richer, browsable in-car conversation screen, beyond notifications alone.

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
   (`<uses name="notification" />`) and referenced it from the manifest. On
   its own this did **not** fix the underlying "not surfaced in Android Auto"
   issue (see "Current status" above) — it's a necessary but apparently not
   sufficient piece.
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
   consistent. Like #5, this is a reasonable correctness fix but not
   confirmed to be the actual blocker either.
