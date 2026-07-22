# VitaFast — Fasting Schedule Prototype

A single-file HTML prototype of a schedule-driven intermittent-fasting app,
modeled on the VitaFast mock-up. No build step, no dependencies — open
`index.html` in any browser (best viewed at a mobile viewport).

```bash
# from this directory — any static server works, or just open the file
npx serve .
```

## What it does

- **24-hour dial with draggable handles.** Drag 🌙 to set when the fast starts
  and ☀️ to set when it ends (15-minute snapping, haptic tick on supported
  devices). The glowing arc, duration, and times update live.
- **Cause & effect as you extend the fast.** Milestone chips (Fat Burning 12h+,
  Autophagy 14h+, Ketosis 16h+, GH Peak 18h+) light up as the arc grows, and a
  stage timeline explains the cause and effect of each zone your chosen
  duration unlocks.
- **Fully automatic — no start/stop buttons.** Flip on the schedule once.
  The app derives fasting/eating state from the clock, shows elapsed/remaining
  time with a progress bar and an amber elapsed arc on the dial, and fires a
  notification when a fast begins and ends.
- **Automatic history.** Completed fasts are logged on their own (including
  days that pass while the app was closed — they're backfilled from the
  schedule). History shows streak, totals, average/longest fast, a 14-day
  bar chart, and a full fast log. An empty history offers sample data so the
  screens can be evaluated immediately.
- **Deliberately no food/recipe tracking.** Strictly fasting.

All state persists in `localStorage` under the key `vitafast.v1`.

## Known prototype limitations

- Web notifications only fire while the page is open — browsers can't schedule
  background notifications from a plain page. This is the main reason for the
  Android phase.
- Backfilled history assumes the schedule was adhered to (by design: the app
  is schedule-driven, not check-in-driven).

## Phase 2 — Android app

Once the design/behavior is signed off, the recommended path is
[Capacitor](https://capacitorjs.com/) — it wraps this exact HTML/JS in a
native Android shell, so the prototype *is* the app:

1. `npm init -y && npm i @capacitor/core @capacitor/cli @capacitor/android @capacitor/local-notifications`
2. `npx cap init vitafast com.example.vitafast --web-dir .` then `npx cap add android`
3. Replace the web `Notification` calls with
   `@capacitor/local-notifications` **scheduled at exact times**
   (`schedule: { at, allowWhileIdle: true }`) so fast start/end notifications
   fire reliably in the background — including `SCHEDULE_EXACT_ALARM`
   permission on Android 12+.
4. On app resume, run the existing `backfill()` so history stays correct even
   if the app wasn't opened for days (this already works in the prototype).
5. Build with `npx cap open android` (Android Studio) or Gradle CLI.

Alternative: a Kotlin/Jetpack Compose rewrite for a fully native feel — more
work, only worth it if WebView performance of the dial feels lacking (it
shouldn't; the dial is a single SVG).
