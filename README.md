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

**Not yet verified**: behavior in an actual Android Auto head unit (voice
readout / voice reply), via Google's Desktop Head Unit (DHU) emulator — the
notification shape it needs (`MessagingStyle` + `RemoteInput`) is already
built and confirmed working, so this is a validation step rather than
outstanding app work.

An attempt to run this on an Android Studio emulator (AVD) hit a real
environment wall: Google Play refuses to install the real Android Auto app
there (`"This app isn't compatible with your device anymore"`), regardless of
having a signed-in Google account — Play's device-compatibility filtering
excludes most/all standard AVD profiles for Android Auto specifically, a
known limitation independent of anything in this project. The Play-delivered
Android Auto app is also a stub whose real functionality is a dynamic module
fetched only once Play accepts the device, so this blocks before DHU pairing
even becomes relevant. A real physical Android phone (which typically already
has the full, non-stub app since it was installed through normal Play
compatibility checks) with USB or wireless `adb` debugging is the practical
way to complete this step — connect it, then follow Google's [DHU
setup](https://developer.android.com/training/cars/testing#dhu) to enable
developer settings + "Unknown sources" in the Android Auto app and pair it
with the `desktop-head-unit` binary (already present at
`$ANDROID_HOME/extras/google/auto/desktop-head-unit` on this machine).

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
