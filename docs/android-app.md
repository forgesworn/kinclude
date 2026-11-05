# The Kinclude Android app

**Status:** planned, 2026-11-05. The Kotlin core it runs on is built and
tested (`android/core`, 23 tests against the TypeScript library's vectors and
scenarios). The app module waits on the build machine reaching
`dl.google.com`, which serves the Android SDK, the Android Gradle plugin and
AndroidX. Kinclude is app only: the web client in `app/` is a test harness
for the protocol, not a product.

## How it should feel

Like WhatsApp and Signal: one place for every conversation, a call a tap
away, and nothing to learn. What is different is shown where it matters,
in the same plain words as the site, and nowhere else.

### Three tabs

1. **Chats.** One row per room:
   - the room's name, the last message and its time;
   - an unread count;
   - a small "Call" mark while a call is on.

   Rooms with unread messages come first, then the newest. A floating
   button starts a new room (adults) or opens the family (children).
   Invitations and approvals waiting on you sit at the top of the list, as
   Signal shows message requests.
2. **Calls.** Recent calls by room, from the room's own history (who
   joined, when, voice or video), with a call-back button.
3. **Family.**
   - **For a parent:** each child, their oversight ("Growing up") and the
     "Ask to move" control; pairing a new child with a QR code; and the
     people you know (later filled from My Signet's kin and kith).
   - **For a child:** their guardians, their own oversight in their own
     words, and scanning a guardian's code.

### A conversation

- **Messages** are bubbles, with yours on the right and a time under each.
  Long-press a message to reply or withdraw it.
- **The top bar** shows the room's name, with voice and video call
  buttons on the right.
- **The oversight line.** A thin line under the top bar, for example
  "Dad can read this chat, and you'll see when he has". Tap it to see who
  is in the room and why. It is drawn from the room's signed state, never
  from a setting. It is there all the time, as Signal keeps its
  safety-number notice to hand.
- **Receipts and emergency openings** appear in the timeline as small
  centred notes ("Dad read this conversation · 19:42"), like WhatsApp's
  date separators.
- **For a guardian at Growing up,** the composer area shows "Read, and
  show that I did" until they tap it.
- **For a guardian at Nearly grown,** the conversation is shut, with
  "Open in an emergency" behind a confirmation that says who will be told.

### Calls

- **Ringing** goes through Android's own calling system (telecom
  `ConnectionService`), so an incoming call rings on the lock screen and
  shows in the system's call log, as WhatsApp's and Signal's do.
- **The call screen** is full screen: the other people's video in a
  grid, your own in a corner, and mute, camera, speaker and hang-up along
  the bottom.
- **Implementation.** `RoomCall` in `android/core` keeps the rules; the
  app supplies WebRTC through `CallEnvironment`, using the maintained
  `io.github.webrtc-sdk:android` build KithMoot already pins.

### Background and notifications

- **No Google push.** A foreground service keeps the relay connections
  open and raises the notifications: KithMoot's `BackgroundCallListenerService`
  pattern.
- **Messages** notify per room.
- **An emergency opening** notifies the child at once, at high priority,
  and the notice cannot be silenced from inside Kinclude.

### Sign-in

- **My Signet** on the same phone, over NIP-55, or over NIP-46. The
  identity key is never on the phone.
- **A child's phone** signs with the child's dependant identity, through
  the guardian's My Signet.

## What comes from KithMoot's Android client

The submodule `android/vendor/kithmoot-android` (pinned):

- `:protocol`, already used;
- from `:app`, to lift with attribution rather than depend on:
  - the OkHttp relay pool,
  - the WebRTC peer and video surfaces,
  - the telecom `ConnectionService`,
  - the background listener service.

Kinclude's screens are its own.

## Order of work, once the SDK is reachable

1. App shell: sign-in, the three tabs, the relay pool, the household on
   disk.
2. Chats and a conversation, with the oversight line, receipts and the
   Growing up gate.
3. Pairing by QR, invitations and approvals, the family tab.
4. Calls: WebRTC, the call screen, telecom ringing.
5. Background service and notifications; the emergency notification.
6. Instrumented tests on an emulator: two phones, a family room, a call.
