# Kinclude

Family-only messaging on KithMoot rooms: dual-approval invitations and three
oversight tiers. The library is in `src/`, the web client in `app/`, the
marketing site in `sites/kinclude/`. Read `docs/design.md` for why and
`docs/protocol.md` for the wire before changing either.

## Build & Test

| Command | Purpose |
|---------|---------|
| `npm ci` | Install (`.npmrc` sets `legacy-peer-deps`; npm 10 otherwise fails on a vitest peer) |
| `npm test` | All vitest suites; no network (the socket test starts its own relay) |
| `npm run test:e2e` | Playwright, three phones against `test/ws-relay.mjs` |
| `npm run typecheck` | Library and app |
| `npm run build` / `npm run build:lib` | App to `app/dist`, library to `dist/` |

## Conventions

- British English in prose, comments and UI copy, in the register of the
  site: plain statements, no jargon. The UI says *First messages*, *Growing
  up*, *Nearly grown*, never "tier".
- The circle layer is `@forgesworn/fold-kit`, pinned. Do not reimplement
  epochs, rekeys or invitations here; change fold-kit first.
- Guardian links use Kindependence's `kindependence/...` `d` tags byte for
  byte. Do not fork the format.
- Every honesty rule (receipt before reading, notice before opening,
  banners from signed state) is a test. A change that weakens one needs its
  test changed in the same commit, with the reason.
- A child's signer refuses NIP-44 (`dependantSigner`), as My Signet does
  for a dependant. Nothing on a child's path may need it.
- `vectors/kinclude-vectors.json` changes only through `npm run vectors`.

## Common Pitfalls

- `dist/` and `app/dist/` are build output; never read them as source.
- Statements and room state are kind 30078 but are never published bare:
  they ride inside room ciphertext.
- The scenario tests share a fixed clock; advance it and put it back.
