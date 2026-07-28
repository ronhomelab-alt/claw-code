# SMS Spam Filter (Android)

Blocks incoming text messages by **sender number pattern** and **body text**,
with an always-allow list and a quarantine log so nothing is lost silently.

## What Android actually allows (read this first)

Android has **no third-party "SMS filter" extension point** — nothing like
iOS's Message Filter API. There are exactly two realistic integration modes,
and this app implements both:

| Mode | How it works | Trade-off |
|---|---|---|
| **Full blocking** — this app becomes the *default SMS app* | Android delivers each SMS to us first (`SMS_DELIVER`). Blocked texts go to the quarantine log and never reach an inbox or notification. | Replaces Google Messages as the default. Android allows only one default SMS app; Messages becomes read-only. |
| **Companion mode** — Google Messages stays default | A `NotificationListenerService` watches Google Messages' notifications and instantly dismisses any that match block rules (also logs them). | The message still lands inside Google Messages; only the interruption is suppressed. Delivery itself cannot be stopped by a non-default app. |

The only true "before it reaches the phone" filtering is carrier-level
(e.g. T-Mobile Scam Shield, AT&T ActiveArmor, Verizon Call Filter) or Google
Messages' own built-in spam protection — worth enabling alongside this app.

## Rule types

Rules are evaluated in this order (first match wins):

1. **Allow list** — numbers that are never blocked (contacts, banks, OTP senders).
2. **Blocked numbers** — exact known-spam numbers.
3. **Number patterns** — `#` matches any digit; formatting is ignored:
   - `(407)` — blocks the whole 407 area code
   - `(507) 413-####` — blocks the 507-413 exchange
   - `(809) 123-5678` — blocks one number
   - Area-code prefixes only apply to full 10-digit numbers, so `(407)` never
     accidentally blocks a 5-digit shortcode starting with 407.
4. **Text rules** — fuzzy substring match on the body. Case, spacing,
   punctuation, and common leetspeak substitutions (0→o, 1→i, 3→e, 4→a,
   5/$→s, 7→t, 8→b, @→a) are folded away before matching, so one
   "Top Tier Solar" rule also catches "TOP-TIER SOLAR!!", "T0p T1er S0lar",
   and "TopTier$olar". Messages that look like verification codes are exempt
   from text rules (a lost OTP hurts more than one spam text). **Saved
   contacts are also exempt from text rules** — a family member mentioning a
   rule word is never auto-blocked (requires the READ_CONTACTS permission).
   Explicit number/pattern blocks still apply to contacts.

Rules are stored in a human-editable text file (`rules.txt` in app storage)
with `allow:` / `block:` / `pattern:` / `text:` lines, so lists can be
exported, imported, and merged.

### Seed rules

The app ships with pattern rules for the Caribbean area codes repeatedly
cited in FCC/FTC "one-ring" (Wangiri) scam advisories: 232, 268, 284, 473,
649, 664, 767, 809, 829, 849, 876. Specific "known spammer" numbers are
deliberately **not** hardcoded — spam numbers are spoofed and recycled within
days — but you can import a current community blocklist as a rules file.

## Privacy & security posture

- **Hide message previews** (Setup tab): notifications show only the sender,
  never message content, so scam text can't appear on the lock screen.
- Message bodies are rendered as plain text — links are never clickable.
- MMS attachments are never downloaded or parsed; nothing in an incoming
  message can auto-fetch content or execute.
- All rules and logs live on-device; the app has no network permission at all.

## Project layout

- `filtercore/` — pure-JVM Kotlin module: matching engine, rule parsing,
  serialization, seed rules. Fully unit-tested; no Android SDK required.
- `app/` — the Android app: a Google-Messages-style SMS client (conversation
  list, chat-bubble threads, contact names, direct-reply notifications,
  mark-as-read) plus the receivers/services for the default-SMS-app role, the
  notification listener for companion mode, and the filter settings UI
  (rules editor, blocked-message log, mode setup).

Both modes ship in the one app: when it holds the default-SMS-app role the
`SMS_DELIVER` receiver quarantines spam before it reaches any inbox; when it
doesn't, the notification listener screens Google Messages' notifications.

**What can't be cloned from Google Messages:** RCS ("chat features" — typing
indicators, read receipts, high-res media, end-to-end encryption) is not
available to third-party apps; Google does not expose an RCS API. Texts from
RCS users still arrive as SMS/MMS. Google's ML spam detection and
messages.google.com web sync are likewise Google-only.

## Building

```sh
# Engine tests only (no Android SDK needed):
gradle :filtercore:test

# Full app (requires the Android SDK; the :app module is auto-included when
# ANDROID_HOME is set or local.properties points at an SDK):
gradle :app:assembleDebug
```

## Known gaps / next steps

- MMS is accepted unfiltered in default-app mode (`MmsDeliverReceiver` stub),
  and the conversation UI shows SMS only — incoming MMS (group texts,
  pictures) are not yet rendered.
- Companion mode matches on the notification's title, which is the contact
  name (not the number) for senders in your contacts — those are treated as
  trusted anyway.
- Planned: contact-list auto-allow, per-rule hit counts, undo/restore from
  quarantine into the inbox, export/import UI, report-to-7726 shortcut.
