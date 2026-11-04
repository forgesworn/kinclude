# Kinclude — design

**Status:** draft, 2026-11-04. Nothing here is built. This document turns the
promises on [kinclude.app](https://kinclude.app) into a design, names the
Kindred and KithMoot pieces each promise rests on, and lists what is still
undecided. Where it says *decision*, the decision is proposed, not taken.

## 1. What Kinclude is

Family-only messaging for a child's first phone: rooms, one-to-one chats,
photos, files and calls, where the only people in a room are people the
parent and the child both chose, and where how much the parent can see is
agreed in the open and shown to the child at all times.

It is a **client and a policy layer**, not a new protocol. The rooms, the
encryption, the admission, the files and the calls are KithMoot's
(`forgesworn/kithmoot`). Identity is My Signet's: the parent is a Signet
owner identity, the child is a Signet dependant. Contacts come from the
parent's Signet contact directory through `signet-contacts` v2. Kinclude
adds the family model on top (guardian links, dual approval, oversight
tiers) and a phone-first client in the Kindred style.

This is the same shape as Kindependence and Kinterest: My Signet is the
family's root, the product consumes it, and the shared surfaces change only
for needs several products share.

### Not goals

- Finding people. There is no directory, search, username or friend request,
  and no way to add somebody Kinclude has not been handed by My Signet.
- Content monitoring, filtering or classification. Oversight is a human
  reading, with the child knowing; nothing reads messages on the family's
  behalf.
- Age verification or age-based rules. Tiers are chosen, not computed.
- Operating anything. No account with ForgeSworn, no server holding a guest
  list, room state or messages. Public Nostr relays and a Blossom server
  carry ciphertext, as for KithMoot.
- Interoperating with mainstream messengers. The closed room is the product.

## 2. The promises, and what each rests on

| Promise on the site | Mechanism | Section |
|---|---|---|
| Nothing to find: no search, usernames or requests | No discovery surface; invitees must already be kin or kith in the guardian's My Signet, or the dependant of such a person | §5, §6 |
| In by invitation that parent and child both approve | A room invitation is valid only with a guardian signature and a child signature over the same invitee and room | §6 |
| Readable only by the room; the parent is a member, not a back door | KithMoot room epoch keys; at tiers 1 and 2 the guardian's device is an ordinary key-holding member, at tier 3 it is not | §7 |
| Three tiers, each a step back | Tier statement, co-signed, held in the room state; the tier decides whether the guardian device is in the key set and what it must publish before it reads | §7 |
| The child can always see which tier they are on | Tier banner in every room, derived from the signed tier statement, never from local settings | §7.5 |
| Growing up: the parent can read, and the child sees exactly what was read | Guardian read receipts published into the room before the guardian's client renders anything | §7.2 |
| Nearly grown: emergency opening, and the child is told straight away | Guardian device holds no keys; opening publishes a signed notice into the room before any key is released to it | §7.3 |
| Moved up together; no ages written in | Tier change needs both signatures; no date-of-birth field exists | §7.4 |
| Withdraw an invitation whenever you like | KithMoot invitation retirement (kind 1461) signed by the inviter | §6.4 |
| Nobody holds the guest list | Membership lives in encrypted room state on relays; no operator | §9 |

## 3. Building blocks

### KithMoot

Used as a library, unchanged where possible. Relevant pieces:

- **Persistent groups** (`docs/persistent-groups.md`): the v3 stored
  invitation (kind 1463) carrying a bearer and an inviter pubkey, no traffic
  secret in the link, admission without the creator online, and retirement
  (1461). A Kinclude room is a KithMoot persistent group with no end date.
- **Epochs and rekeys** (`docs/protocol.md`, fold-kit): room epoch keys,
  authority-signed rekeys (1462) that exclude removed devices, and member
  epoch catch-up (20471/20472). Tier changes that add or remove the guardian
  device from the key set are rekeys.
- **Chat envelope** (kind 1460) with threads, edits, reactions, retractions,
  mentions; **files** announced in-room and stored on Blossom, encrypted;
  **calls** over the existing signalling profile and ICE/forwarder design.
- **Roster** with self-declared flags (`agent: true` is the precedent).
  Kinclude adds a `guardian` role in the same spirit, with the difference
  that it is backed by a signed guardian link, not self-declared (§4).
- **Device credentials** (`docs/device-credential.md`): a device acts in a
  room with a per-room device key authorised by the person's identity. This
  is how a phone signs room traffic without the identity key being present,
  which matters for a child whose identity signs through a guardian's bunker.
- **RoomAgent** (`docs/agents.md`): a headless member. Reserved here for the
  possible emergency-opening witness (§7.3) and for nothing else in v1.
- **MLS** is in development in KithMoot. Kinclude v1 targets the shipped
  epoch model; the design is written so that the guardian device being "in
  the key set or not" maps onto an MLS group membership later.

### My Signet

- The **guardian** signs in with My Signet (NIP-55 on the same Android
  device, NIP-46 over a relay otherwise), as Kindependence does. The identity
  key is never on the phone.
- The **child** is a Signet dependant. Which of two shapes the child's key
  takes is the first open decision (§5.1).
- My Signet's **autonomy** levels gate what a dependant's identity may sign
  without a guardian tap. Kinclude's tiers are not the same thing (§7.6).

### signet-contacts

- The guardian's **kin / kith / ken** tiers from My Signet. Only kin and kith
  are invitable; kens show greyed with *Meet in person to add*, which opens
  My Signet. This is Kindependence's rule, reused.
- **Guardian links**: the two-way signed statements Kindependence defined
  (§7 of its contacts-and-circles design). *Decision:* Kinclude reuses the
  same statement formats rather than inventing a second kind of guardian link,
  so a family that uses both products links each child once. Whether the
  formats move to a shared package is a Kindred question, not Kinclude's.

## 4. People and roles

| Role | Who | Identity | Can |
|---|---|---|---|
| Guardian | A parent or carer | Signet owner identity, with a guardian link to each of their children | Create rooms, co-sign invitations and tier changes, be a member at tiers 1–2, open in an emergency at tier 3 |
| Child | The person the phone is for | Signet dependant of one or more guardians | Co-sign invitations and tier changes for themself, chat, call, share, see their tier |
| Relative | Grandparent, aunt, older cousin, a friend's parent | Signet owner identity, kin or kith of a guardian | Chat, call, share; invite only from their own kin/kith, and only with the room's dual approval (§6.2) |
| Another family's child | The cousin, the best friend | Dependant of their own guardian | As a child, with their own guardian and tier |
| Witness (optional, later) | A `RoomAgent` the family runs | Its own key | Hold a share of an emergency escrow and announce its use (§7.3) |

A **family** is not a record anywhere. It is the set of guardian links a
child has, plus whatever rooms those people are in. Two guardians of one
child each hold their own link; a co-parent is added as the first guardian's
kin and links the child in person, as Kindependence does.

A room may contain children at different tiers, each with their own
guardians. Rules below are stated per child and compose.

## 5. Identity and keys

### 5.1 The child's key — open decision

Two shapes exist in the suite:

- **(a) Guardian-bunker signing** (Kindependence). The dependant's identity
  key lives only in My Signet on the guardian's phone; the child's phone
  holds a per-app device key authorised by an identity-signed device
  statement, and anything structural waits for a guardian tap. Nothing new
  is needed in My Signet.
- **(b) One-time scoped export to the child device** (Kinterest's
  recommendation). My Signet derives the dependant and hands the secret once
  to the child's device, which signs offline from then on. Needs a My Signet
  feature that does not exist yet.

*Proposed:* **(a)** for v1. Kinclude's structural actions are rare
(approving an invitation, changing tier), the guardian is a party to every
one of them anyway, and the child's everyday traffic is signed by the
room device key, which never waits. If Signet ships per-app dependant
issuance for Kinterest, Kinclude can adopt (b) without changing the room
protocol, because the room only ever sees the device key.

Consequence of (a): a dependant has no personal inbox and cannot receive
anything addressed to their identity key. Every invitation for a child goes
to a guardian (§6.3).

### 5.2 Keys a device holds

| Key | Where | Signs |
|---|---|---|
| Guardian identity | My Signet | Guardian links, invitation approvals, tier statements, emergency notices, device credentials for the guardian's own phones |
| Child identity | My Signet on a guardian's phone (shape a) | The child's half of approvals and tier statements, device credentials for the child's phone |
| Per-room device key | The phone, per KithMoot | All room traffic: chat, files, call signalling, guardian read receipts |
| Room epoch keys | Every member device in the key set | Encrypts everything in the room |

Nothing in Kinclude needs remote `nip44_*` from a dependant, which My Signet
refuses below full autonomy.

## 6. No way in from outside

### 6.1 What an invitation is

A Kinclude room is a KithMoot persistent group. Its invitation is the v3
stored group invitation: bearer `j` and inviter `h`, with the room secret
in the 1463 body under a key derived from the bearer. Kinclude changes the
**policy around the bearer**, not the format:

- A bearer is never shown as a URL and never put on the clipboard. It is
  delivered, NIP-44-sealed, to one intended invitee's identity (an adult) or
  to the guardian of an intended invitee (a child).
- The 1463 body carries an additional field, `approval`, holding the dual
  approval (§6.2). A Kinclude client refuses to join a room whose invitation
  lacks a valid approval naming it; a plain KithMoot client ignores the field
  and would join, which is why the bearer is never exposed (the field is
  belt, the delivery is braces).
- The inviter `h` is the inviting adult's identity key, or, when a child
  initiates, their guardian's. Delegation chains are not used.

### 6.2 Dual approval

An invitation for room *R* to invitee *I*, made in a room that holds child
*C*, is valid only with two signatures over the same statement:

```
{ room: R, invitee: I, child: C, issuedAt, expires }
```

one by a guardian of *C* and one by *C* (through the bunker: one tap). In a
room with several children, the approval set must contain a pair for each
child in the room at the time of issue. A child joining a room is itself an
invitation, so the room's existing children (and their guardians) approve
the newcomer, and the newcomer's own guardian and the newcomer approve the
room.

Rooms with no child in them (a grandparents' room before any child is added)
need only the inviter's signature; the moment a child is added, the rule
applies.

*Decision:* approvals are per invitee, not per room. A family may find this
heavy for a large room; the cost is a tap each, and it is what "everyone in
it is someone you both chose" means.

### 6.3 Who may be invited

- An adult may be invited only if they are **kin or kith** of the inviting
  guardian in My Signet. Kinclude checks this on the inviting side and the
  invitee's client checks the inverse (the inviter is in its own usable
  contacts), as Kindependence does. Strangers' invitations are dropped
  silently.
- A child may be invited only **through a guardian**: the invitation is
  addressed to the guardian, who, if they accept, adds the child under their
  own guardian link, co-signing with the child. The child's device accepts
  only an admission from someone holding a valid guardian link to it.
- A relative in the room may propose an invitation from their own kin/kith;
  it becomes valid only once the room's dual approvals exist. Their client
  shows it as *waiting for Mum and Sam*.

### 6.4 Withdrawing

The inviter retires the invitation with the signed KithMoot retirement
(1461). An invitee whose bearer has not yet been used is refused on arrival.
Removing somebody already in the room is a rekey without their devices, as
in KithMoot; who may remove is the inviter, any guardian of a child in the
room, or the member themself. The child is shown who left and who removed
them.

### 6.5 Honesty about the boundary

A room member can copy ciphertext, screenshot, or read a message aloud.
Kinclude closes the door to people the family did not choose; it does not
and cannot police the people they did.

## 7. Oversight tiers

The tier is the product. Everything here is designed so that the child's
view of what the parent can see is **true**, not a setting the parent could
quietly differ from.

A tier is a property of a **child**, and applies in every room that child is
in, including rooms other families made. It is carried by a **tier
statement**:

```
{ child: C, tier: 1 | 2 | 3, from, previous: <hash of prior statement> }
```

signed by both the child and a guardian, stored encrypted in the room state
of every room the child is in, and shown to every member of those rooms.

### 7.1 Tier 1 — First messages

*You see every message. They know you do.*

- Every guardian device of *C* is an ordinary member of every room *C* is
  in: it holds the epoch keys and appears in the roster with role
  `guardian`. A room admitting a tier-1 child admits their guardians'
  devices in the same admission; a room that cannot (the inviter refuses)
  cannot admit the child.
- Rooms show a persistent banner, *Mum can see this chat*, to every member,
  not only the child.
- The guardian may read, and may send, as a member. There is no silent mode.

### 7.2 Tier 2 — Growing up

*You can read their messages, and they can see exactly what you've read.*

- Key set as tier 1: the guardian device is a member.
- The guardian's client **publishes before it renders**. When the guardian
  opens a room, their client first publishes a guardian read receipt into
  the room (a kind-1460 payload field, `guardianRead: { upTo, at }`, signed by
  the guardian's room device key), waits for the relay's acknowledgement, and
  only then decrypts the view. The receipt names the latest message the
  guardian can now see. Members see *Mum read this conversation · 19:42*.
- This is **client-enforced honesty**: the guardian's device holds the keys
  and a modified client could read without publishing. The design accepts
  this at tier 2 for three reasons: the family already trusts the guardian
  with the keys at this tier; the clients are open source and the receipt
  rule is a test fixture, not a preference; and a parent who wants to read
  without a trace has chosen tier 1, where the banner already says so. The
  cryptographic alternative is tier 3.
- KithMoot's own read positions (kind 30078, own-account ciphertext) are
  private to the account and are **not** this. Guardian receipts are a new
  in-room payload.
- Guardian devices do not receive call media unless they join the call as a
  visible participant. There is no listening in. The agent-media switch
  precedent applies: media is handed only to roster entries the sender's
  policy admits, and a guardian is admitted only when present in the call.

### 7.3 Tier 3 — Nearly grown

*Their messages are theirs. In an emergency you can open them, and they're
told straight away.*

- The guardian device is **not** in the key set. Moving a child to tier 3 is
  a rekey of every room the child is in that excludes their guardians'
  devices (unless a guardian is in the room in their own right, as a member
  of the family room; then they keep the keys to that room, and the banner
  says so: *Mum is in this room*).
- The guardian still appears in the roster with role `guardian`, flagged
  `reading: false`, so the room can show *Dad can't see this chat*.
- **Emergency opening.** Each epoch, the child's device seals the room's
  current epoch secret to each guardian's identity key and publishes the
  sealed escrow into the room (members other than the guardian can see that
  escrow exists but not open it). To open, the guardian's client publishes an
  **emergency notice** into the room, signed by the guardian's identity:

  ```
  { child: C, room: R, openedAt, reason?: <free text shown to the child> }
  ```

  and only after the relay acknowledges it does the client ask My Signet to
  decrypt the escrow (a remote `nip44_decrypt`, which itself produces a tap
  and an entry in My Signet's activity log). Every member sees *Dad opened
  your messages in an emergency · 21:14*; the child's phone notifies them
  whether or not they have the room open. Opening grants the current epoch
  only; the room rekeys at the next opportunity, so an opening does not
  become standing access.
- The notice "can't be switched off" to the same degree as tier 2's
  receipt: it is enforced by the guardian's client, backed by My Signet's
  independent record of the decryption. A stronger form is available later:
  **split escrow**, where the epoch secret is shared between the guardian
  and a **witness** (a `RoomAgent` the family runs, on a kithmoot-node or a
  spare device), and the witness releases its share only after it has
  itself posted the notice. That makes the notice cryptographically
  unavoidable at the cost of the family running something. *Decision:*
  ship single-party escrow in v1, design the escrow record so a witness
  share can be added without a format change, and say plainly in the
  product which form is in use.

### 7.4 Moving between tiers

- A new tier statement needs **both** signatures, in either direction. A
  guardian cannot move a child back down alone, and a child cannot move up
  alone. (A guardian who believes a child is unsafe has emergency opening,
  not a quiet downgrade.)
- The statement chains to the previous one by hash; a client accepts a
  statement only if `previous` matches the one it holds, so neither side can
  replay an old, more permissive statement.
- Moving up to 3 rekeys guardians out; moving down to 1 or 2 admits guardian
  devices by the member epoch grant and the next rekey includes them. Either
  way, the rooms show *Sam moved to Nearly grown · agreed with Mum, 3 Nov*.
- Tier changes apply to all of the child's rooms at once. A room another
  family runs learns of the change from the statement and acts on it; a room
  whose members refuse to admit a tier-1 child's guardian is a room the
  child cannot be in, and both families are told why.

### 7.5 Always on show

- Every room shows the tier of every child in it, and who their guardians
  are, from the signed statements. There is no local setting that changes
  what the banner says.
- A guardian at tier 1 or 2 who is in the room is in the roster as such.
  There is no hidden membership: a device in the key set is a device in the
  roster, which KithMoot's roster protocol already requires for presence.
- The starting tier is chosen at pairing, together, and shown then.

### 7.6 Tiers are not My Signet autonomy

My Signet's autonomy levels decide what a dependant's *identity* may sign
without a guardian's tap. Kinclude's tiers decide what a guardian may
*read*. A family may well move them together, and the Kinclude client can
suggest it, but Kinclude does not read or write autonomy and the two are
recorded separately.

## 8. Rooms, chat, files and calls

- **Family room**: made by a guardian; children added under their guardian
  links; other guardians as kin. **Grandparents' room**: a room with the
  grandparents and whichever children the family chooses, under the same
  rules. **Cousins' room**: made by one family, children of the other
  family invited through their guardian. **One-to-one**: a two-member room;
  at tiers 1 and 2 the child's guardian device is a third key holder and the
  banner says so.
- **Chat** is KithMoot's chat envelope unchanged, plus the `guardianRead`
  field. Reactions, threads, edits and retractions work as in KithMoot;
  a retraction by the child is visible to a guardian at tier 1/2 as a
  retraction, as it is to any member.
- **Files** are KithMoot's: encrypted to the room, announced in-room,
  stored on a Blossom server. *Open:* which Blossom server a family uses
  by default, and whether Kinclude should bundle a kithmoot-node option so a
  family can keep its own photos.
- **Calls** are KithMoot's signalling and media. A guardian joins a call as
  a visible participant or not at all (§7.2).
- **Across the family's phones**: a person's rooms follow their identity to
  each of their phones through KithMoot's account bookmarks and device
  credentials. Phones first; the web and desktop clients are later.

## 9. Where the data lives, and what is visible

- Rooms live on public Nostr relays chosen per room (KithMoot's room-relays
  design). Nothing identifies a room as a Kinclude room on the wire: the
  kinds, tags and ciphertext are KithMoot's.
- What a relay sees is what it sees for any KithMoot room: opaque room ids,
  device pubkeys, timing and volume. It does not see names, membership,
  tiers, who is a child or who is a guardian. Stated here rather than
  hidden, as KithMoot's decisions doc does.
- My Signet's activity log, on the guardian's phone, records every
  structural signature and every emergency decryption. That log is the
  family's, not ours.
- There is no ForgeSworn account, server, analytics or crash reporter.

## 10. The client

- Phone first: Android, following Kindependence's path (Capacitor shell over
  the web client, NIP-55 to My Signet on-device). iOS after, with NIP-46.
- Built from the KithMoot library and app code, with Kinclude's own
  surfaces: pairing a child, the tier screens, the approval queue
  (*waiting for Sam*), and the banners.
- Kindred brand: the negative-space flames mark, the suite's type and
  colour. Copy in British English, in the register of the site: plain
  statements, no jargon. "Tier" is a design word; the client says *First
  messages*, *Growing up*, *Nearly grown*.
- Every honesty rule (§7.2, §7.3, §7.5) is an acceptance test in the
  client's own suite, run against the offline relay, before it is a feature.

## 11. Open questions

1. **Child key shape** (§5.1): guardian-bunker signing now, per-app
   dependant issuance later? Depends on Signet's roadmap for Kinterest.
2. **Emergency escrow strength** (§7.3): is single-party escrow with a
   client-enforced notice honest enough to ship under the words "can't be
   switched off", or does v1 need the witness? If the witness, where does a
   family run it with no operator?
3. **Guardian link formats**: Kindependence's `kindependence/...` `d` tags, a
   shared `kindred/...` namespace, or Kinclude's own? Sharing is right; who
   owns the package is a suite decision.
4. **Mixed-tier rooms across families**: when family A's tier-1 child joins
   family B's room, family B must admit A's guardian. Is that a one-time
   consent by B's guardians, or implied by approving the child?
5. **Second guardians** who are not in a room (separated parents): does a
   tier-1 child's room admit both guardians' devices, always? *Proposed:*
   yes, by definition of tier 1; a guardian who should not be is one whose
   guardian link should be revoked, which is a My Signet matter.
6. **Default relays and Blossom** for a family that knows nothing about
   either.
7. **A child with no guardian online** for days: approvals queue in the
   child's client and the room shows them as waiting. Is there anything a
   child should be able to do alone? *Proposed:* nothing structural.
8. **What a child sees of a guardian's own rooms**: nothing; a guardian's
   adult rooms are ordinary KithMoot rooms outside Kinclude.

## 12. Milestones

1. **Paper**: this document reviewed; open questions 1–3 decided with the
   Signet and Kindependence owners; wire shapes for approval, tier statement,
   guardian receipt, escrow and emergency notice written into a Kinclude
   protocol note and vectors.
2. **Library**: a `kinclude` policy layer over KithMoot in TypeScript, with
   the offline relay: dual approval, tier statements, rekeys on tier change,
   guardian receipts, escrow and opening; every honesty rule a test.
3. **Android client**: pairing, family room, one-to-one, tiers 1 and 2.
4. **Tier 3** with single-party escrow; **files and calls**.
5. **Witness** option; iOS; web.

## Sources

- The site copy in `sites/kinclude/public/index.html`.
- KithMoot: `docs/decisions.md`, `docs/persistent-groups.md`,
  `docs/protocol.md`, `docs/messages.md`, `docs/agents.md`,
  `docs/device-credential.md`.
- Kindependence: the Signet identity and contacts-and-circles designs
  (2026-09-25) for bunker signing, device statements, kin/kith/ken and
  guardian links.
- Kinterest: *Signet as family root* (2026-08-11) for the dependant
  derivation finding and the two key shapes.
- `signet-contacts` `docs/WIRE.md` for the directory grant and tier source.
