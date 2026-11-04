import { h, render } from 'preact'
import { useEffect, useRef, useState } from 'preact/hooks'
import htm from 'htm'
import { nip19 } from 'nostr-tools'
import QRCode from 'qrcode'
import {
  Household,
  poolTransport,
  TIER_NAMES,
  mayRemove,
  type KincludeRoom,
  type KincludeSigner,
  type RoomView,
  type Tier,
} from '../../src/index.js'
import { connectMySignet, createLocalIdentity, forgetEverything, localStore, relays, restoreIdentity, savedKind, setRelays, type Kind } from './identity.js'

const html = htm.bind(h)

// ---------------------------------------------------------------- app state

let household: Household | undefined
const listeners = new Set<() => void>()
let renderQueued = false
function changed(): void {
  if (renderQueued) return
  renderQueued = true
  requestAnimationFrame(() => {
    renderQueued = false
    for (const l of listeners) l()
  })
}

function useTick(): void {
  const [, set] = useState(0)
  useEffect(() => {
    const l = () => set((n) => n + 1)
    listeners.add(l)
    return () => { listeners.delete(l) }
  }, [])
}

let toastTimer: number | undefined
function toast(text: string): void {
  let el = document.querySelector<HTMLDivElement>('.toast')
  if (!el) {
    el = document.createElement('div')
    el.className = 'toast'
    el.setAttribute('role', 'status')
    document.body.append(el)
  }
  el.textContent = text
  clearTimeout(toastTimer)
  toastTimer = window.setTimeout(() => el?.remove(), 3500)
}

async function attempt(fn: () => Promise<unknown>, done?: string): Promise<void> {
  try {
    await fn()
    if (done) toast(done)
  } catch (err) {
    console.error(err)
    toast(err instanceof Error ? err.message : String(err))
  }
}

async function startHousehold(identity: KincludeSigner, name?: string, kind?: Kind): Promise<void> {
  household = await Household.open({
    transport: poolTransport(relays()), identity, store: localStore, onChange: changed,
    ...(name ? { name } : {}), ...(kind ? { kind } : {}),
  })
  await household.start()
  changed()
}

// ---------------------------------------------------------------- names

function short(pk: string): string {
  const npub = nip19.npubEncode(pk)
  return `${npub.slice(0, 10)}…${npub.slice(-4)}`
}

function nameOf(pk: string): string {
  const hh = household
  if (!hh) return short(pk)
  if (pk === hh.me) return hh.data.name
  const known = hh.data.children.find((c) => c.pk === pk)?.name ?? hh.data.guardians.find((g) => g.pk === pk)?.name
    ?? hh.data.contacts.find((c) => c.pk === pk)?.name
  if (known) return known
  for (const room of hh.rooms.values()) {
    const m = room.view().state?.members.find((x) => x.pk === pk)
    if (m?.name) return m.name
  }
  return short(pk)
}

function time(t: number): string {
  return new Date(t * 1000).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' })
}

function list(names: string[]): string {
  if (names.length <= 1) return names.join('')
  return `${names.slice(0, -1).join(', ')} and ${names.at(-1)}`
}

const TIER_HELP: Record<Tier, string> = {
  1: 'You see every message. They know you do.',
  2: 'You can read their messages, and they see exactly what you have read.',
  3: 'Their messages are theirs. In an emergency you can open them, and they are told straight away.',
}

// ---------------------------------------------------------------- setup

function Setup() {
  const [kind, setKind] = useState<Kind>('adult')
  const [name, setName] = useState('')
  const [bunker, setBunker] = useState('')
  const [busy, setBusy] = useState(false)
  const go = (how: 'local' | 'bunker') => attempt(async () => {
    if (!name.trim()) throw new Error('Add a name first.')
    setBusy(true)
    try {
      const identity = how === 'local' ? createLocalIdentity(kind) : await connectMySignet(kind, bunker)
      await startHousehold(identity, name.trim(), kind)
    } finally {
      setBusy(false)
    }
  })
  return html`
    <div class="shell">
      <header class="bar"><img class="mark" src="./favicon.svg" alt="" /><h1>Kinclude</h1></header>
      <main>
        <section class="stack">
          <p>Family messaging with no way in from outside. Everyone in it is someone you both chose.</p>
        </section>
        <section class="stack">
          <h2>Whose phone is this?</h2>
          <div class="choices" role="radiogroup">
            <label class="choice"><input type="radio" name="kind" checked=${kind === 'adult'} onChange=${() => setKind('adult')} />
              <span><span class="title">Mine: I'm a parent, carer or relative</span><br /><span class="muted small">You can invite people you know and pair with a child's phone.</span></span></label>
            <label class="choice"><input type="radio" name="kind" checked=${kind === 'child'} onChange=${() => setKind('child')} />
              <span><span class="title">A child's phone</span><br /><span class="muted small">Pairs with a parent or carer, in person. Nobody can find it from outside.</span></span></label>
          </div>
          <label>Name shown to your family<input value=${name} onInput=${(e: Event) => setName((e.target as HTMLInputElement).value)} placeholder=${kind === 'child' ? 'Sam' : 'Mum'} maxlength="40" /></label>
        </section>
        <section class="stack">
          <h2>Sign in</h2>
          <label>My Signet link<input value=${bunker} onInput=${(e: Event) => setBunker((e.target as HTMLInputElement).value)} placeholder="bunker://…" /></label>
          <button class="primary" disabled=${busy || !bunker} onClick=${() => go('bunker')}>Sign in with My Signet</button>
          <details>
            <summary>Just trying it out?</summary>
            <div class="stack" style="margin-top:.5rem">
              <p class="muted small">Keep a key on this phone instead. Fine for a test; a family should use My Signet, so the key is never on the phone.</p>
              <button disabled=${busy} onClick=${() => go('local')}>Make a key on this phone</button>
            </div>
          </details>
        </section>
      </main>
    </div>`
}

// ---------------------------------------------------------------- home

function Home({ open }: { open: (id: string) => void }) {
  useTick()
  const hh = household!
  const [roomName, setRoomName] = useState('')
  const rooms = [...hh.rooms.values()]
  const approvals = rooms.flatMap((r) => r.view().proposals
    .filter((p) => r.approvalsToSign(p.invitee).length > 0)
    .map((p) => ({ room: r, p })))
  const tiers = hh.pendingTiers()
  const waiting = hh.data.invites.length + approvals.length + tiers.length
  return html`
    <div class="shell">
      <header class="bar"><img class="mark" src="./favicon.svg" alt="" /><h1>Kinclude</h1><span class="muted small">${hh.data.name}</span></header>
      <main>
        ${waiting > 0 && html`
          <section>
            <h2>Waiting for you</h2>
            <div class="list">
              ${hh.data.invites.map((i) => html`
                <div class="card stack" data-testid="invite">
                  <div>${i.forChild
                    ? html`<strong>${nameOf(i.sender)}</strong> invites <strong>${nameOf(i.forChild)}</strong> to <strong>${i.envelope.name}</strong>. Saying yes passes it to ${nameOf(i.forChild)}'s phone, and lists you as ${nameOf(i.forChild)}'s guardian there.`
                    : hh.data.kind === 'child'
                      ? html`<strong>${nameOf(i.sender)}</strong> passed on an invitation to <strong>${i.envelope.name}</strong>.`
                      : html`<strong>${nameOf(i.sender)}</strong> invites you to <strong>${i.envelope.name}</strong>.`}</div>
                  <div class="row">
                    <button class="primary" onClick=${() => attempt(() => hh.acceptInvite(i.id), 'Joined')}>Accept</button>
                    <button onClick=${() => attempt(() => hh.declineInvite(i.id))}>No thanks</button>
                  </div>
                </div>`)}
              ${approvals.map(({ room, p }) => html`
                <div class="card stack" data-testid="approval">
                  <div><strong>${nameOf(p.by)}</strong> would like to invite <strong>${p.name ?? nameOf(p.invitee)}</strong> to <strong>${room.view().name}</strong>${p.role === 'child' ? html`, with ${list(p.guardians.map(nameOf))} as their guardian${p.guardians.length > 1 ? 's' : ''}` : ''}.</div>
                  <div class="row">
                    <button class="primary" onClick=${() => attempt(() => room.approve(p.invitee), 'Approved')}>Approve</button>
                    <button onClick=${() => open(room.roomId)}>Open room</button>
                  </div>
                </div>`)}
              ${tiers.map((t) => html`
                <div class="card stack" data-testid="tier-request">
                  <div><strong>${nameOf(t.signedBy)}</strong> would like ${t.child === hh.me ? 'you' : nameOf(t.child)} to move to <strong>${TIER_NAMES[t.tier]}</strong>.</div>
                  <div class="muted small">${TIER_HELP[t.tier]}</div>
                  <div class="row"><button class="primary" onClick=${() => attempt(() => hh.agreeTier(t), 'Agreed')}>Agree</button></div>
                </div>`)}
            </div>
          </section>`}

        <section>
          <h2>Rooms</h2>
          <div class="list">
            ${rooms.length === 0 && html`<p class="empty">No rooms yet.</p>`}
            ${rooms.map((r) => {
              const v = r.view()
              const last = v.messages.at(-1)
              return html`<button class="tile card" data-testid="room-tile" onClick=${() => open(r.roomId)}>
                <div class="title">${v.name}</div>
                <div class="muted small">${last ? `${nameOf(last.from)}: ${last.text}` : v.reading ? 'No messages yet' : v.removed ? 'You cannot read this room now' : 'Waiting to be let in…'}</div>
              </button>`
            })}
            ${hh.data.kind === 'adult' && html`
              <form class="row" onSubmit=${(e: Event) => { e.preventDefault(); void attempt(async () => { const r = await hh.createRoom(roomName.trim() || 'Family'); setRoomName(''); open(r.roomId) }) }}>
                <input class="grow" value=${roomName} onInput=${(e: Event) => setRoomName((e.target as HTMLInputElement).value)} placeholder="New room, e.g. Home" aria-label="New room name" maxlength="40" />
                <button type="submit">Make room</button>
              </form>`}
          </div>
        </section>

        <${Family} />
        ${hh.data.kind === 'adult' && html`<${People} />`}
        <${Me} />
      </main>
    </div>`
}

function Family() {
  useTick()
  const hh = household!
  const [pairing, setPairing] = useState<{ code: string; qr: string; cancel(): void } | undefined>()
  const [childName, setChildName] = useState('')
  const [tier, setTier] = useState<Tier>(1)
  const [code, setCode] = useState('')
  const offer = () => attempt(async () => {
    const o = hh.offerPairing({ tier, ...(childName.trim() ? { childName: childName.trim() } : {}) })
    const qr = await QRCode.toDataURL(o.code, { margin: 1, scale: 6 })
    setPairing({ code: o.code, qr, cancel: o.cancel })
    const child = await o.paired
    setPairing(undefined)
    setChildName('')
    toast(`Paired with ${nameOf(child)}`)
  })
  useEffect(() => () => pairing?.cancel(), [pairing])

  if (hh.data.kind === 'child') {
    const mine = hh.tierOf(hh.me)
    return html`
      <section>
        <h2>Family</h2>
        <div class="list">
          ${hh.data.guardians.length === 0 && html`<p class="empty">Not paired with anyone yet.</p>`}
          ${hh.data.guardians.map((g) => html`<div class="card row" data-testid="guardian"><span class="grow">${g.name ?? short(g.pk)}</span><span class="muted small">guardian</span></div>`)}
          ${mine && html`
            <div class="card stack">
              <div>Your oversight: <span class="tier" data-testid="my-tier">${TIER_NAMES[mine]}</span></div>
              <div class="muted small">${TIER_HELP[mine]}</div>
              <${TierChooser} child=${hh.me} current=${mine} />
            </div>`}
          <div class="card stack">
            <label>Pairing code from your parent or carer's phone
              <textarea rows="3" value=${code} onInput=${(e: Event) => setCode((e.target as HTMLTextAreaElement).value)} placeholder="kinclude-pair:…" /></label>
            <button class="primary" disabled=${!code.trim()} onClick=${() => attempt(async () => { const g = await hh.acceptPairing(code); setCode(''); toast(`Paired with ${nameOf(g)}`) })}>Pair</button>
          </div>
        </div>
      </section>`
  }

  return html`
    <section>
      <h2>Children</h2>
      <div class="list">
        ${hh.data.children.map((c) => {
          const t = hh.tierOf(c.pk)
          return html`<div class="card stack" data-testid="child">
            <div class="row"><span class="grow title">${c.name ?? short(c.pk)}</span>${t && html`<span class="tier">${TIER_NAMES[t]}</span>`}</div>
            ${t && html`<${TierChooser} child=${c.pk} current=${t} />`}
          </div>`
        })}
        ${pairing
          ? html`<div class="card stack" data-testid="pairing">
              <div>On the child's phone, choose <strong>Pair</strong> and scan or paste this. Do it together, in person.</div>
              <div class="qr"><img src=${pairing.qr} alt="Pairing code" /></div>
              <textarea readonly rows="3" class="mono" data-testid="pairing-code" onFocus=${(e: Event) => (e.target as HTMLTextAreaElement).select()}>${pairing.code}</textarea>
              <div class="row">
                <button onClick=${() => attempt(() => navigator.clipboard.writeText(pairing.code), 'Copied')}>Copy</button>
                <button onClick=${() => { pairing.cancel(); setPairing(undefined) }}>Cancel</button>
              </div>
            </div>`
          : html`<div class="card stack">
              <label>Child's name<input value=${childName} onInput=${(e: Event) => setChildName((e.target as HTMLInputElement).value)} placeholder="Sam" maxlength="40" /></label>
              <div class="stack">
                <span class="muted small">Where you start together</span>
                ${([1, 2, 3] as Tier[]).map((n) => html`
                  <label class="choice"><input type="radio" name="start-tier" checked=${tier === n} onChange=${() => setTier(n)} />
                    <span><span class="title">${TIER_NAMES[n]}</span><br /><span class="muted small">${TIER_HELP[n]}</span></span></label>`)}
              </div>
              <button class="primary" onClick=${offer}>Pair with a child's phone</button>
              <p class="muted small">Already paired with another parent? Pair again here; the child keeps the oversight you agreed.</p>
            </div>`}
      </div>
    </section>`
}

function TierChooser({ child, current }: { child: string; current: Tier }) {
  const hh = household!
  const [to, setTo] = useState<Tier>(current)
  const mine = hh.data.family.some((e) => e.pubkey === hh.me && e.tags.some((t) => t[0] === 'p' && t[1] === child) && hh.tiers.pending(child).some((p) => p.event.id === e.id))
  if (mine) return html`<div class="muted small">Waiting for the other side to agree.</div>`
  return html`<div class="row">
    <select class="grow" aria-label="Move to" value=${String(to)} onChange=${(e: Event) => setTo(Number((e.target as HTMLSelectElement).value) as Tier)}>
      ${([1, 2, 3] as Tier[]).map((n) => html`<option value=${String(n)}>${TIER_NAMES[n]}</option>`)}
    </select>
    <button disabled=${to === current} onClick=${() => attempt(() => hh.proposeTier(child, to), 'Asked. It changes once you both agree.')}>Ask to move</button>
  </div>`
}

function People() {
  useTick()
  const hh = household!
  const [key, setKey] = useState('')
  const [name, setName] = useState('')
  const [tier, setTier] = useState<'kin' | 'kith'>('kin')
  const add = () => attempt(async () => {
    let pk = key.trim()
    if (pk.startsWith('npub')) {
      const d = nip19.decode(pk)
      if (d.type !== 'npub') throw new Error('That is not a person\'s key.')
      pk = d.data
    }
    if (!/^[0-9a-f]{64}$/.test(pk)) throw new Error('That is not a person\'s key.')
    hh.setContacts([...hh.data.contacts.filter((c) => c.pk !== pk), { pk, tier, ...(name.trim() ? { name: name.trim() } : {}) }])
    setKey('')
    setName('')
  }, 'Added')
  return html`
    <section>
      <h2>People you know</h2>
      <div class="list">
        <p class="muted small">Only people here can invite you, and only they can be invited by you. In a family set-up this list comes from My Signet: the kin and kith you have met.</p>
        ${hh.data.contacts.map((c) => html`<div class="card row" data-testid="contact"><span class="grow">${c.name ?? short(c.pk)}</span><span class="tier">${c.tier}</span>
          <button class="link" onClick=${() => hh.setContacts(hh.data.contacts.filter((x) => x.pk !== c.pk))}>Remove</button></div>`)}
        <div class="card stack">
          <label>Their key<input value=${key} onInput=${(e: Event) => setKey((e.target as HTMLInputElement).value)} placeholder="npub1…" /></label>
          <div class="row">
            <label class="grow">Name<input value=${name} onInput=${(e: Event) => setName((e.target as HTMLInputElement).value)} maxlength="40" /></label>
            <label>Who<select value=${tier} onChange=${(e: Event) => setTier((e.target as HTMLSelectElement).value as 'kin' | 'kith')}>
              <option value="kin">Kin</option><option value="kith">Kith</option></select></label>
          </div>
          <button onClick=${add} disabled=${!key.trim()}>Add</button>
        </div>
      </div>
    </section>`
}

function Me() {
  const hh = household!
  const [editing, setEditing] = useState(false)
  const [relayText, setRelayText] = useState(relays().join('\n'))
  const npub = nip19.npubEncode(hh.me)
  return html`
    <section>
      <h2>This phone</h2>
      <div class="card stack">
        <div><span class="title">${hh.data.name}</span> <span class="muted small">${hh.data.kind === 'child' ? 'child\'s phone' : ''}</span></div>
        <div class="mono" data-testid="my-key">${npub}</div>
        <div class="row"><button onClick=${() => attempt(() => navigator.clipboard.writeText(npub), 'Copied')}>Copy my key</button></div>
        <details open=${editing} onToggle=${(e: Event) => setEditing((e.target as HTMLDetailsElement).open)}>
          <summary>Relays</summary>
          <div class="stack" style="margin-top:.5rem">
            <textarea rows="3" class="mono" value=${relayText} onInput=${(e: Event) => setRelayText((e.target as HTMLTextAreaElement).value)} />
            <button onClick=${() => { setRelays(relayText.split(/\s+/).filter((r) => /^wss?:\/\//.test(r))); location.reload() }}>Save and restart</button>
          </div>
        </details>
        <button class="danger" onClick=${() => { if (confirm('Forget everything on this phone? Rooms and keys kept here are lost.')) { forgetEverything(); location.reload() } }}>Forget this phone</button>
      </div>
    </section>`
}

// ---------------------------------------------------------------- a room

function Banners({ v }: { v: RoomView }) {
  const hh = household!
  const out = []
  for (const b of v.banners) {
    const child = b.child === hh.me ? 'you' : nameOf(b.child)
    const childs = b.child === hh.me ? 'your' : `${nameOf(b.child)}'s`
    const tierName = b.tier ? TIER_NAMES[b.tier] : TIER_NAMES[1]
    const who = (pks: string[]) => list(pks.map((pk) => (pk === hh.me ? 'You' : nameOf(pk))))
    if (b.reading.length) {
      const seeing = who(b.reading)
      out.push(b.tier === 2
        ? html`<div class="banner sees" data-testid="banner"><span><strong>${seeing}</strong> can read ${childs} messages here, and everyone sees when ${b.reading.length > 1 ? 'they have' : b.reading[0] === hh.me ? 'you have' : 'they have'}. <span class="tier">${tierName}</span></span></div>`
        : html`<div class="banner sees" data-testid="banner"><span><strong>${seeing}</strong> can see this chat. <span class="tier">${tierName}</span></span></div>`)
    }
    if (b.notReading.length) {
      out.push(html`<div class="banner quiet" data-testid="banner"><span><strong>${who(b.notReading)}</strong> can't see this chat. ${b.child === hh.me ? 'Your' : `${nameOf(b.child)}'s`} messages are ${b.child === hh.me ? 'yours' : 'theirs'}. <span class="tier">${tierName}</span></span></div>`)
    }
    if (b.inOwnRight.length && b.tier === 3) {
      out.push(html`<div class="banner quiet"><span><strong>${who(b.inOwnRight)}</strong> ${b.inOwnRight.length > 1 ? 'are' : 'is'} in this room in ${b.inOwnRight.length > 1 ? 'their' : 'their'} own right.</span></div>`)
    }
    if (b.catchingUp) {
      out.push(html`<div class="banner alert"><span>This room hasn't caught up with ${child === 'you' ? 'your' : `${nameOf(b.child)}'s`} new oversight yet. Until it does, the people shown above can still see it.</span></div>`)
    }
  }
  for (const e of v.emergencies) {
    out.push(html`<div class="banner alert" data-testid="emergency"><span><strong>${nameOf(e.signer)}</strong> opened ${e.notice.child === hh.me ? 'your' : `${nameOf(e.notice.child)}'s`} messages in an emergency · ${time(e.at)}${e.notice.reason ? html`<br />"${e.notice.reason}"` : ''}</span></div>`)
  }
  return html`${out}`
}

function RoomScreen({ room, back }: { room: KincludeRoom; back: () => void }) {
  useTick()
  const hh = household!
  const v = room.view()
  const [text, setText] = useState('')
  const [reading, setReading] = useState(false)
  const [reason, setReason] = useState('')
  const [inviting, setInviting] = useState(false)
  const bottom = useRef<HTMLDivElement>(null)

  // A guardian who chose to read keeps reading while the room is open: each
  // new message is receipted before it is shown, never after.
  useEffect(() => {
    if (reading && v.unrevealed > 0) void attempt(() => room.reveal())
  }, [reading, v.unrevealed])
  useEffect(() => { bottom.current?.scrollIntoView({ block: 'end' }) }, [v.messages.length])

  const s = v.state
  const myRole = v.role
  const canInvite = myRole === 'adult'
  const inRoom = new Set(s?.members.map((m) => m.pk) ?? [])
  const latestReceipts = new Map<string, { guardian: string; child: string; at: number }>()
  for (const r of v.receipts) {
    const k = `${r.guardian}:${r.child}`
    if ((latestReceipts.get(k)?.at ?? 0) <= r.at) latestReceipts.set(k, r)
  }
  const send = () => attempt(async () => {
    if (!text.trim()) return
    await room.send(text.trim())
    setText('')
  })

  return html`
    <div class="shell">
      <header class="bar">
        <button class="link" onClick=${back} aria-label="Back">‹ Back</button>
        <h1>${v.name}</h1>
      </header>
      <main>
        <${Banners} v=${v} />
        ${v.stateProblem && html`<div class="notice">This room's membership changed in a way this phone could not check: ${v.stateProblem}. It is showing the last state it could check.</div>`}
        ${v.gatedFor.length > 0 && v.unrevealed > 0 && !reading && html`
          <div class="card stack" data-testid="gate">
            <div>${list(v.gatedFor.map(nameOf))} ${v.gatedFor.length > 1 ? 'are' : 'is'} at <strong>${TIER_NAMES[v.tierOf[v.gatedFor[0]!] ?? 2]}</strong>. If you read this conversation, ${v.gatedFor.length > 1 ? 'they' : nameOf(v.gatedFor[0]!)} will see that you did.</div>
            <div class="muted small">${v.unrevealed} message${v.unrevealed === 1 ? '' : 's'} not yet read.</div>
            <button class="primary" onClick=${() => setReading(true)}>Read, and show that I did</button>
          </div>`}
        ${v.openable.length > 0 && html`
          <div class="card stack" data-testid="emergency-open">
            <div>${list(v.openable.map(nameOf))}'s messages are theirs. In an emergency you can open them, and ${v.openable.length > 1 ? 'they are' : `${nameOf(v.openable[0]!)} is`} told straight away. Everyone in the room sees that you did.</div>
            <label>What should ${nameOf(v.openable[0]!)} be told?<input value=${reason} onInput=${(e: Event) => setReason((e.target as HTMLInputElement).value)} placeholder="Your phone has been off since 9" maxlength="280" /></label>
            <button class="danger" onClick=${() => {
              if (confirm(`Open ${nameOf(v.openable[0]!)}'s messages? They will be told straight away, and so will everyone in this room.`)) {
                void attempt(() => room.openInEmergency(v.openable[0]!, reason.trim() || undefined), 'Opened. They have been told.')
              }
            }}>Open in an emergency</button>
          </div>`}
        ${!v.reading && !v.removed && !v.openable.length && html`<p class="muted">${myRole === 'guardian'
          ? 'You are listed here as a guardian, and cannot read this room at the moment.'
          : 'Waiting to be let in. Everyone the room needed has to agree first.'}</p>`}
        ${v.removed && myRole !== 'guardian' && html`<p class="notice">You are no longer in this room.</p>`}

        <div class="messages" data-testid="messages">
          ${v.messages.map((m) => html`
            <div class=${`msg${m.from === hh.me ? ' mine' : ''}${m.viaEmergency ? ' emergency' : ''}`} data-testid="message">
              ${m.from !== hh.me && html`<div class="who">${nameOf(m.from)} · ${time(m.sentAt)}</div>`}
              <div>${m.text}</div>
            </div>`)}
          ${[...latestReceipts.values()].map((r) => html`<div class="receipt" data-testid="receipt">${r.guardian === hh.me ? 'You' : nameOf(r.guardian)} read this conversation · ${time(r.at)}</div>`)}
          <div ref=${bottom}></div>
        </div>

        ${v.proposals.length > 0 && html`
          <section>
            <h2>Invitations being agreed</h2>
            <div class="list">
              ${v.proposals.map((p) => html`<div class="card stack" data-testid="proposal">
                <div><strong>${nameOf(p.by)}</strong> would like to invite <strong>${p.name ?? nameOf(p.invitee)}</strong>.</div>
                ${p.approvals.map((a) => html`<div class="muted small">For ${nameOf(a.child)}: ${a.byChild ? `${nameOf(a.child)} agreed` : `waiting for ${nameOf(a.child)}`}; ${a.byGuardian ? `${nameOf(a.byGuardian)} agreed` : 'waiting for a guardian'}.</div>`)}
                ${p.invited && html`<div class="muted small">Invitation sent.</div>`}
                ${room.approvalsToSign(p.invitee).length > 0 && html`<button class="primary" onClick=${() => attempt(() => room.approve(p.invitee), 'Approved')}>Approve</button>`}
              </div>`)}
            </div>
          </section>`}

        ${s && html`
          <section>
            <h2>In this room</h2>
            <div class="list">
              ${s.members.map((m) => html`<div class="card row" data-testid="member">
                <span class="grow">${m.pk === hh.me ? 'You' : nameOf(m.pk)}</span>
                <span class="muted small">${m.role === 'guardian' ? (s.readers.includes(m.pk) ? 'guardian, can read' : 'guardian, cannot read') : m.role === 'child' ? 'child' : ''}</span>
                ${m.pk !== hh.me && v.creator && mayRemove(s, hh.me, m.pk, v.creator) && html`<button class="link" onClick=${() => { if (confirm(`Remove ${nameOf(m.pk)}?`)) void attempt(() => room.remove(m.pk)) }}>Remove</button>`}
              </div>`)}
              ${canInvite && (inviting
                ? html`<div class="card stack">
                    ${hh.data.children.filter((c) => !inRoom.has(c.pk)).map((c) => html`<button onClick=${() => attempt(async () => { await hh.addChild(room, c.pk); setInviting(false) }, 'Asked. The room has to agree.')}>Add ${c.name ?? short(c.pk)}</button>`)}
                    ${hh.data.contacts.filter((c) => !inRoom.has(c.pk)).map((c) => html`<button onClick=${() => attempt(async () => { await room.propose({ invitee: c.pk, role: 'adult', ...(c.name ? { name: c.name } : {}) }); setInviting(false) }, 'Asked. The room has to agree.')}>Invite ${c.name ?? short(c.pk)}</button>`)}
                    ${hh.data.children.length + hh.data.contacts.length === 0 && html`<p class="empty">Add people you know, or pair with a child, first.</p>`}
                    <button class="link" onClick=${() => setInviting(false)}>Cancel</button>
                  </div>`
                : html`<button onClick=${() => setInviting(true)}>Invite someone</button>`)}
              ${v.creator !== hh.me && myRole && myRole !== 'guardian' && html`<button class="link" onClick=${() => { if (confirm('Leave this room?')) void attempt(() => room.remove(hh.me)) }}>Leave this room</button>`}
            </div>
          </section>`}
      </main>
      ${v.reading && html`
        <form class="composer" onSubmit=${(e: Event) => { e.preventDefault(); void send() }}>
          <input value=${text} onInput=${(e: Event) => setText((e.target as HTMLInputElement).value)} placeholder="Message" aria-label="Message" maxlength="2000" />
          <button class="primary" type="submit">Send</button>
        </form>`}
    </div>`
}

// ---------------------------------------------------------------- root

function App() {
  useTick()
  const [roomId, setRoomId] = useState<string | undefined>()
  if (!household) return html`<${Setup} />`
  const room = roomId ? household.rooms.get(roomId) : undefined
  if (room) return html`<${RoomScreen} room=${room} back=${() => setRoomId(undefined)} />`
  return html`<${Home} open=${setRoomId} />`
}

async function boot(): Promise<void> {
  const identity = await restoreIdentity().catch(() => undefined)
  if (identity) await startHousehold(identity, undefined, savedKind())
  render(html`<${App} />`, document.getElementById('app')!)
}

void boot()
