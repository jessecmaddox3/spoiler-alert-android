# Design and working with the source

> **TL;DR:** This is a complete Android app built around a local notification listener and an explicitly controlled reveal process. Public sports data is a separate, read-only input. Keep that boundary when adapting it.

## The app in five pieces

The listener classifies a source, matches text against an immutable snapshot of effective hiding sessions, and captures that session identity before dismissing the source notification. Persistence can happen later, but it cannot look up a newer session and pretend that was the one that captured the item.

The Room data layer stores interests, session identities, notification content and minimal consent-routing metadata. A session has explicit start/end state and reveal authorization. Extending it advances its revision. Starting again creates a new identity. Notification ownership routing retains a growing set of consent requirements, independently of deletable content. A delayed write with no remaining live match stays sealed until every recorded owner has authorized future content. Known notification keys retain hashed chat aliases when Android omits or renames the display label. Alias groups merge conservatively and retain every earlier consent requirement, including after content deletion. Existing rows are only consolidated when their capture and owner identities agree. Notification actions and worker inputs carry those identities so stale actions remain limited to what the user was offered.

WorkManager handles reminders and deadlines. Local database mutations and notification effects are serialized across repository instances. Database transactions never wait for public-provider network requests. A worker rechecks its session after such a request before posting anything. Startup repairs missing schedules and obsolete notification effects from durable state.

The `schedule/` package fetches public schedules, event status and timeline evidence. Timeline parsers treat missing scores, malformed arrays, ambiguous clocks, unsupported overtime and incomplete coverage as unavailable. A real zero score is valid evidence; a made-up zero baseline is not. Soccer's displayed minute can represent an interval, so skip checks preserve that uncertainty rather than silently rounding it away.

Compose supplies onboarding, Home, discovery, hidden items, history, event questions and settings. Confirmation dialogs retain the exact session selection shown when opened. The Glance widget and Quick Settings tile use session metadata and public game data. They can start hiding or open the app; they do not reveal content with an accidental toggle.

## Make it your own

Start with the built-in invented notification demo and the constructed fixtures under `app/src/test/resources/espn/`. Do not paste your phone's notification history, account details, private conversations or downloaded provider responses into a public issue or test. Replace examples with independently invented ones.

Useful first changes include a different visual theme, an additional public sport adapter, improved keyboard or TalkBack navigation, or a more useful narrow score question. Preserve complete feature behavior when changing the presentation. The app's fonts have separate notices, and the artwork catalog documents the included illustrations.

For a new matching rule, test the intended notification alongside calls, alarms, navigation alerts, ordinary chat and an excluded app. For a lifecycle change, test both orderings of capture and reveal, automatic expiry, extension, rearm, overlapping events, a delayed write and startup recovery. For a parser change, test missing and contradictory evidence as well as the positive example.

## Working with an AI coding assistant

Give the assistant a small, observable task, the relevant source paths and an invented example. Ask it to explain the current behavior before editing. Keep a single writer in a checkout; an independent reviewer can inspect without changing files.

A useful starting prompt is:

```text
Read README.md, docs/DESIGN.md and the relevant implementation.
Make this specific change: [describe the behavior].
Use only independently invented examples. Do not read or copy my phone's data,
credentials, notification history, private repositories or signing keys.
Keep notification content on-device and networking inside schedule/.
For behavior changes, reproduce the problem with a meaningful regression first.
Run the appropriate JVM, lint and disposable-emulator tests.
Explain what changed, what passed and what remains unverified.
```

The tests are part of the foundation. Use them to explore the design, but do not treat a passing suite as permission to remove consent checks, make claims about unsupported provider evidence, or ship an unreviewed artifact.
