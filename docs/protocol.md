# Kinclude protocol

**Status:** v1, as implemented in `src/` (2026-11-05). Kind numbers Kinclude
adds are provisional. Wire examples are in `vectors/kinclude-vectors.json`,
checked by `vectors/vectors.test.ts`. The design and its reasons are in
[`design.md`](design.md); this note is what goes on the wire.

A Kinclude room is a KithMoot group: fold-kit 0.17.0's group invitation,
epochs and rekeys, and KithMoot's chat envelope. Nothing on a relay
distinguishes it from any other KithMoot room. Kinclude adds three things,
all inside room ciphertext or inside a seal:

1. **Statements** people sign with their identity (§2).
2. **Room state** the room's authority signs (§4).
3. **Rules** every device applies to both (§5).

## 1. Keys and kinds

| Key | Held by | Used for |
|---|---|---|
| Identity | My Signet (or the phone, for testing) | Statements; room credentials; the seal of an invitation |
| Phone secret | The phone | Derives the keys below; never leaves the phone |
| Room device key | The phone | `HKDF-SHA256(phoneSecret, salt ∅, "kinclude/v1/room-device:" + roomId)`; signs everything the phone writes in that room |
| Inbox key | The phone | `HKDF-SHA256(phoneSecret, salt ∅, "kinclude/v1/inbox")`; a child's phone receives forwarded invitations on it and seals family updates from it |
| Room authority | The room creator's phone | The root inviter of the room's group invitations; signs rekeys, epoch grants, retirements and room state |
| Epoch secrets | Every reader's phone | fold-kit epochs; epoch 0 is the room secret |

A room device key is per room, so relays cannot link one person's rooms by
signing key. Each device holds a **room-scoped** fold-kit device credential
(kind 20460, inside messages), signed by the identity, valid for a year:
fold-kit's epoch desk accepts only room-scoped credentials.

| Kind | Use | Published |
|---|---|---|
| 1460 | KithMoot chat, envelope 1: every Kinclude message | Regular |
| 1461 / 1462 / 1463 | fold-kit invitation retirement, rekey, group invitation | Regular |
| 20468 / 20469 | fold-kit epoch request / grant | Ephemeral |
| 30078 | Statements (§2) and room state (§4) | **Never bare**: inside 1460 ciphertext only |
| 1059 / 13 | NIP-59 gift wrap and seal, for invitations and family updates | 1059 regular |
| 1480 | Rumor: an invitation envelope (§6) | Never; inside a seal |
| 1481 | Rumor: a guardian forwarding a child's invitation (§6) | Never; inside a seal |
| 1482 | Rumor: a child's phone telling its guardians the family (§7) | Never; inside a seal |
| 20480 | Pairing exchange (§7) | Ephemeral |
| 20462 / 21059 | KithMoot call signal, and its wrap (§8) | 21059 ephemeral, NIP-40 expiry 60 s |
| 20481 | Call presence (§8) | Ephemeral |

## 2. Statements

Kind 30078, signed by an identity. Data in tags; content is a sentence a
person reads in My Signet before signing. A reader checks the signature,
the kind, that the `d` tag matches the other tags, and that no tag it does
not know is present (approvals). The readers are in `src/statements.ts`.

**Guardian link** (Kindependence's format, unchanged):

| Statement | Signed by | Tags |
|---|---|---|
| guardian-of | guardian | `d` = `kindependence/dependant/<child>`, `p` = child |
| dependant-of | child | `d` = `kindependence/guardian/<guardian>`, `p` = guardian |
| unlink | either | `d` = `kindependence/unlink/<other>`, `p` = other |

A link holds when both halves exist and both are newer than every unlink
either side signed naming the other. A link to oneself is refused.

**Approval**: one person's yes to one newcomer joining one room, for one
child in it.

```
d        kinclude/approval/<room>/<invitee>/<child>
p        <invitee>
room     <room id>
child    <child>
role     adult | child
guardian <pk>        (one per guardian of a child invitee, sorted; none for an adult)
expires  <unix seconds>
```

A **pair** is the same approval (equal on room, invitee, child, role,
guardians and expires) signed by the child and by a guardian linked to the
child. It admits until `expires` (the app uses seven days).

**Tier statement**:

```
d     kinclude/tier/<child>
p     <child>
tier  1 | 2 | 3
seq   0, 1, 2, …
prev  genesis (seq 0) | digest of the statement it replaces
```

`digest = hex(SHA-256(UTF-8 "kinclude/v1/tier:<child>:<seq>:<tier>:<prev>"))`.
A statement counts once the child and a linked guardian have both signed it.
The chain starts at the complete `seq 0` and follows complete statements
whose `prev` is the one before; of two complete statements on one `prev`,
the lower digest wins. A child with no chain is read as tier 1.

**Emergency notice**, signed by a guardian:

```
d      kinclude/emergency/<room>/<child>/<epoch>
p      <child>
room   <room id>
epoch  <the epoch being opened, ≥ 1>
```

Content is what the guardian wants the child told (control and bidi
characters stripped, at most 280 characters).

## 3. Messages

Every message is a KithMoot envelope-1 chat event: kind 1460, one `d` tag
naming the stream, content NIP-44 v2 under the stream key, signed by the
device key, `created_at` equal to `sentAt`. The plaintext is KithMoot's
message JSON (`id`, `participant`, `device`, `credential`, `name`, `text`,
`sentAt`, `sentAtMs`, `retracts`) plus one optional field:

```
"kinclude": { "t": "<type>", … }
```

`text` is always present and reads sensibly alone. A reader verifies the
event, that `device` is its signer, and the credential for this room at
`sentAt`; a malformed `kinclude` field drops the message.

Two streams:

- **Lobby**: `deriveChannel(roomId, roomKey, "kinclude-lobby")` under
  epoch 0. Every device ever admitted can read it.
- **Main**: each epoch's `deriveEpoch` id and key. Only readers.

| `t` | Fields | Stream | From |
|---|---|---|---|
| `hello` | `events` (0–64 statements) | lobby | A newcomer's device, with its family statements and, for a child, its own approval pair |
| `statements` | `events` (1–64) | lobby; approvals in main | Anyone |
| `propose` | `invitee`, `role`, `guardians`, `name?`, `expires` | main | A member in their own right |
| `state` | `event` (§4) | lobby | The authority |
| `read` | `child`, `upTo` (message id), `at` | main | A guardian, before reading (§5.3) |
| `escrow` | `epoch`, `copies[] {guardian, ct}` | lobby | A tier-3 child's device (§5.4) |
| `emergency` | `event` (an emergency notice) | lobby | A guardian, before opening (§5.4) |
| `remove` | `target` | main | A member asking the authority |
| `call` | `call` (32 hex), `state` join\|leave, `video` | main | A device joining or leaving the room's call (§8) |

Links and tier statements ride in the lobby because a guardian who cannot
read (tier 3) still has to see the child's tier change.

## 4. Room state

Kind 30078 signed by the room authority, carried in a `state` message:

```
d    kinclude/state/<room>
seq  <n>
content: {
  "v": 1, "room", "seq", "prev": <id of the state it replaces | null>,
  "name", "epoch": <the epoch readers hold>,
  "members": [{ "pk", "role": adult|child|guardian, "name?", "by", "at",
                "approvals": [events], "links": [events] }],
  "readers": [pk],     // who the current epoch was sealed to, by participant
  "removed": [pk]      // dropped from the key set and not let back
}
```

A `guardian` member is in the room only because a child in it is theirs. A
member keeps the newest state whose `seq` and `prev` follow the one it
holds and which passes `checkTransition` (§5.1); a state that does not is
ignored and the room shows that it is in a state the phone could not check.

## 5. Rules

### 5.1 Admission

A newcomer (adult or child) needs a pair (§2) for every child already in
the room, and a child newcomer also needs its own pair (`child` =
`invitee`, signed by the newcomer and their guardian). A child newcomer's
approvals name exactly the guardians its links show, and those guardians
join as `guardian` members. Somebody with a guardian link joins only as a
child. The genesis state has the creator alone, as an adult. The creator
is never removed.

The authority does the following:

1. Holds a proposal until the pairs for the children already in the room
   are complete.
2. Mints a bearer for that invitee alone, publishes a fold-kit 1463, and
   delivers the invitation (§6).
3. Admits the invitee once their `hello` arrives with what is missing.

Every member's device checks the same rule against each new state.

### 5.2 Who reads

`readers` = every adult and child member, plus every `guardian` member
with a child in the room at tier 1 or 2. Whenever `readers` or membership
changes, the authority rekeys (fold-kit 1462, `removed` = readers dropped,
`commit` set) and publishes the next state at the new epoch. A newcomer,
or a guardian let back in, therefore reads only from the epoch they were
admitted in.

The authority's desk answers an epoch request (20468) with the current
epoch only (no earlier epochs) to a participant in `readers`, refuses
`removed`, and stays silent to anybody else.

### 5.3 Receipts

A device whose person is a `guardian` member, for a child past tier 1,
shows no message until it has published a `read` naming the newest message
and the relay has acknowledged it. Messages arriving later stay hidden
until the next receipt. This holds at tier 3 too, for messages the
guardian's device still holds from tier 2.

### 5.4 Escrow and emergency opening

At tier 3, the child's device seals each epoch it holds to each `guardian`
member who is not a reader:

```
ct = NIP-44 v2 (conversation key of child's room device key and guardian's identity) of hex(epoch secret)
```

and posts it as `escrow` in the lobby. To open, the guardian's device:

1. Publishes an `emergency` message carrying the signed notice, and waits
   for the relay's acknowledgement.
2. Only then asks the identity (My Signet: `nip44_decrypt`, with a tap and
   an activity-log entry) to open its copy.

It reads that one epoch. On a notice for the current epoch the authority
rekeys, so the opening does not last.

### 5.5 Removal

The authority removes a member when the request comes from the member
themself, whoever proposed them, the creator, or any guardian of a child
in the room. A `guardian` member with no child left is removed with them.
Removal rekeys, retires the member's bearer (1461), and a later return
needs a proposal made after the removal.

## 6. Invitations

An **envelope** (rumor 1480):

```
{ "v": 1, "room": <room id>, "name", "bearer": <32-byte hex>, "authority",
  "invitee", "role", "guardians": [pk], "fromName?", "relays?" }
```

It is gift-wrapped (NIP-59) from the authority's person to the invitee
(an adult) or to each guardian named (a child). The joiner opens the
fold-kit group invitation from `bearer` and `authority`, and refuses it
unless the room secret derives `room`.

An adult's phone keeps an invitation only from its kin and kith, its own
guardians or children, or itself; anything else is dropped unread.

A guardian who accepts a child's invitation:

1. Signs their half of the child's own pair.
2. Forwards both the envelope and that half (rumor 1481, `{ envelope,
   approvals }`) to the child's inbox key.
3. Joins as a guardian member.

The child's phone takes forwards only from its guardians, signs its own
half, and joins.

## 7. Pairing

The guardian's phone shows `kinclude-pair:` followed by base64url JSON
`{ g: guardian, s: 16-byte hex secret, t: tier, n?: child name, m?: guardian name }`.

Messages are kind 20480:

- tag `d` = `HKDF(secret, ∅, "kinclude/v1/pairing-tag")`;
- content NIP-44 v2 under `HKDF(secret, ∅, "kinclude/v1/pairing-key")` of
  `{ from: child|guardian, pk, name?, inbox?, events }`.

The exchange runs like this:

1. The child's phone sends its dependant-of half and either a new genesis
   half at the offered tier, or the chain it already has.
2. The guardian's phone checks those, then answers with guardian-of and,
   for a new child, its genesis half.
3. The child's phone then gift-wraps its full family statements (rumor
   1482) from its inbox key to each guardian, so every guardian knows every
   other one before proposing the child anywhere.

## 8. Calls

A call is a mesh: one WebRTC peer connection per pair of devices on it.
The code is `src/call-wire.ts` and `src/call.ts`.

**Signalling** is KithMoot's call signalling profile 1, unchanged:

1. An `offer`, `answer` or `ice` body is the content of a kind 20462 event
   signed by the room device key. Its tags are `p` (recipient device),
   `call-id` (room id), `alt` and `kithmoot` = `1`.
2. The signed event is NIP-44 encrypted, whole, from a throwaway key to the
   recipient device.
3. The result goes inside a kind 21059 tagged only `p` and
   `expiration` = inner time + 60.

Kinclude adds `call` (the call id) to the body. A reader drops anything
more than 20 seconds from its inner time, or addressed elsewhere.

**Presence** says who is on the call. It is a kind 20481, signed by the
room device key:

- tag `d` = `HKDF-SHA256(epochKey, ∅, "kinclude/v1/call-presence-tag")`;
- content NIP-44 under `HKDF-SHA256(epochKey, ∅,
  "kinclude/v1/call-presence-key")` of
  `{ call, state: on|off, participant, device, credential, video, at }`.

Each device sends one every 10 seconds while on the call, and at once when
it sees somebody new. A reader drops one more than 30 seconds old, and one
whose credential is not this device's for this room. The keys come from
the current epoch, so only a reader can write or read presence.

**Rules**, each a test in `test/call.test.ts`:

1. **Joining is said first.** A device publishes a `call` join message in
   the main stream, and waits for the relay to acknowledge it, before it
   sends presence, signals or media. It records a `leave` when it leaves.
   The room's history therefore shows who was on every call.
2. **A phone only connects to people it shows.** A device negotiates only
   with devices whose fresh presence it holds, for its call, whose person
   is in the room state's `readers`. The lower device key makes the offer.
   An offer that arrives before its sender's presence waits for the
   presence; it is never answered blind.
3. **The call follows the key set.** When the state stops listing
   somebody as a reader, every phone closes its connection to them. A phone
   that can no longer read the room leaves the call.
4. **One call per room.** A device joins the call it sees in presence, or
   the newest recorded in the room's history, or starts one with a random
   id. Two calls started at once merge into the lower id.

So a guardian at tier 3 cannot be on the call: they hold no key. A guardian
at tiers 1 and 2 can, and shows on it like anybody else. There is no
listening in.

## 9. What this protects, and what it does not

- **A stranger cannot get in.**
  - There is no discovery. An invitation needs a bearer minted for one
    person, after the dual approvals.
  - A leaked bearer opens only epoch 0. The desk never hands the current
    epoch to somebody the state does not list (`test/scenario.test.ts`, "a
    stranger…").
- **Relays see** what they see for any KithMoot room: opaque ids, device
  pubkeys, timing and volume. They never see names, membership, tiers or
  family links.
- **The lobby is readable by everyone ever admitted**, including people
  since removed, for as long as they keep the room secret. Through it they
  see:
  - later room states: membership, approvals and links;
  - tier changes;
  - that escrows exist;
  - emergency notices.

  They see no messages.
- **Banners trust the authority.** `readers` is the authority's word. A
  creator's phone could seal keys to someone and not list them; the room's
  creator is trusted with its key set, as in KithMoot.
- **Receipts and emergency notices are enforced by the guardian's client**
  (design §7.2–7.3). The emergency opening is also recorded independently
  in My Signet's activity log. A modified client could read without a
  receipt at tier 2. At tier 3 it could not read without the escrow, and it
  could not get the escrow without its identity decrypting it.
- **Calls are peer to peer.** Media goes straight between phones,
  encrypted by WebRTC (DTLS-SRTP), unless a TURN server relays it; a TURN
  server carries ciphertext but sees both ends' addresses. The STUN server
  (the app defaults to Cloudflare's) sees each phone's address when a call
  starts.
- **Room members can copy what they read.** Kinclude decides who is in the
  room, not what they do with it.
