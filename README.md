# Kinclude

Family-only messaging: no way in from outside, and everyone in it is someone
you both chose. Part of the [Kindred](https://kindred.software) suite.

## Status

Version 1 is built: a TypeScript library and a phone-first web client,
tested end to end in real browsers against an offline relay. It is not
released. Voice and video calls work in every room. There is no Android
shell yet and no photos or files, and the "people you know" list is kept
by hand rather than read from My Signet.
[`docs/design.md`](docs/design.md) §11–13 says what was decided and what
was left out.

The marketing site for kinclude.app lives in `sites/kinclude/` and is live at
https://kinclude.app.

## How it works

A Kinclude room is a [KithMoot](https://github.com/forgesworn/kithmoot)
group (fold-kit's epochs, rekeys and group invitations, and KithMoot's chat
envelope) with a family layered on top:

- **Nothing to find.** An invitation is a bearer minted for one person, sent
  sealed, and only once every child in the room and one of each child's
  guardians have signed the same approval. An adult's phone takes
  invitations only from their kin and kith. A child's invitations go to the
  child's guardians, who pass them on.
- **Oversight in three tiers**, agreed with two signatures and chained so
  neither side can replay an older one:
  - **First messages:** a guardian holds the room's key, and the room says so.
  - **Growing up:** the guardian's phone publishes a receipt before it shows
    anything.
  - **Nearly grown:** the guardian is rekeyed out of the room. They can open
    the current epoch in an emergency, from a key the child's phone escrows
    for them, but only after a signed notice has reached the room.
- **Every banner comes from the room's signed state**, never from a local
  setting, and every phone checks each change to that state against the
  rules for itself.

The wire is in [`docs/protocol.md`](docs/protocol.md), with vectors in
`vectors/kinclude-vectors.json`.

## Build and test

| Command | Purpose |
|---|---|
| `npm ci` | Install (`.npmrc` sets `legacy-peer-deps`) |
| `npm test` | Unit, scenario, socket and vector tests (vitest, no network) |
| `npm run test:e2e` | Three phones in Chromium against the offline relay (Playwright) |
| `npm run typecheck` | Library and app |
| `npm run build:lib` | Library to `dist/` |
| `npm run build` | The app to `app/dist/` |
| `npm run relay` | The offline relay on `ws://localhost:7777` |
| `npm run demo` | The app on http://localhost:5180 |
| `npm run vectors` | Regenerate the vectors (after `build:lib`) |

To try it by hand with no network, run `npm run relay`, then start the app
pointed at the offline relay:

```sh
KINCLUDE_RELAYS=ws://localhost:7777 npm run demo
```

Open http://localhost:5180 in two or three separate browser profiles (one
per phone) and choose *Just trying it out?* on each.

## Layout

```
src/            the library
  statements.ts   what people sign: guardian links, approvals, tiers, emergency notices
  family.ts       reading them: who is whose guardian, a child's tier, complete approvals
  state.ts        the room's signed state
  policy.ts       the rules every phone checks
  messages.ts     KithMoot envelope-1 messages with Kinclude payloads
  room.ts         one phone in one room, on fold-kit
  invite.ts       sealed invitations and per-room keys
  pairing.ts      pairing a child's phone in person
  household.ts    one person on one phone
  call-wire.ts    call signalling (KithMoot profile 1) and presence
  call.ts         voice and video calls, a mesh per room
app/            the web client (Vite, Preact)
test/           scenario tests, the offline relay, Playwright specs in test/e2e/
vectors/        wire vectors and their generator
docs/           design and protocol
sites/kinclude/ the kinclude.app marketing site
```
