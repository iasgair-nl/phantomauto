# Battery drain analysis — background message delivery

## Problem

Current background design is not workable in practice: battery drain is far too
high. Three mechanisms fight Android's power management simultaneously, on top of
keeping a full Chromium renderer with N websockets alive 24/7 just to catch the
occasional DM.

## Root causes (roughly in order of cost)

1. **12-hour `PARTIAL_WAKE_LOCK`** — the CPU is never allowed to sleep. This
   alone accounts for a few percent per hour.
2. **30-second keep-alive that fires `dispatchEvent(new Event('online'))`** —
   every Nostr client treats this as "network is back" and performs a full relay
   reconnect + resubscribe. Radio out of idle, TLS/WS handshakes, ~2880x per day.
3. **2x2px `TYPE_APPLICATION_OVERLAY` with alpha 0.01** — keeps a real surface in
   the compositor, so Chromium keeps rendering and the GPU pipeline stays warm.
   Combined with `WIFI_MODE_FULL`, which disables wifi power-save.

## Options

### A. Push from outside (preferred end state)

A small server-side daemon (fits alongside the existing phantombot infra)
subscribes to the DM relays for the target npub and, on a new event, sends a
high-priority **data-only FCM** message to the app. The app then needs no
WebView, no wakelock and no overlay in the background: it is woken on a message,
builds the `MessagingStyle` notification, and goes back to sleep. Battery cost is
effectively zero.

Privacy is preserved by forwarding the **gift-wrapped ciphertext** and decrypting
locally — the server never sees plaintext.

- Downside: dependency on Google Play Services, plus a server component to
  operate.
- Variant: self-hosted UnifiedPush/ntfy is the same pattern without Google, but
  then the ntfy client carries the persistent socket instead.

### B. Native socket instead of Chromium (recommended next step)

The foreground service holds a single OkHttp websocket to the relays itself, with
server pings every 60-120s, and performs NIP-17 decryption in Kotlin. A
foreground service is already exempt from Doze network restrictions, so the
**wakelock, overlay and wifi lock can all be removed**. The WebView then exists
only while visible.

- Orders of magnitude cheaper; no server component required.
- Downside: duplicates part of the Nostr logic that currently lives in the PWA.

### C. Stopgap (can land today)

Remove the wakelock, the overlay and the wifi lock; reduce the keep-alive to a
minimum or replace it with a plain `ws.ping` instead of an `online` event. This
removes the bulk of the drain, but Chromium will throttle background timers, so
delivery becomes unreliable. Acceptable as a temporary patch, not an end state.

### D. WorkManager poll (complement)

A 15-minute periodic `WorkManager` job as a *supplement* to catch messages missed
after a push hiccup. Costs almost nothing. Only viable if latency is not
critical.

## Recommendation

- **Next step: B** — no new infrastructure, immediately workable.
- **End state: A** — near-zero battery cost, with D as a safety net.
- Optionally measure first with `dumpsys batterystats` + Battery Historian to
  confirm which of the three causes dominates on the actual device.
