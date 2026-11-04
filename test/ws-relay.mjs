#!/usr/bin/env node
// A small NIP-01 relay for development and the end-to-end tests: no
// network, no disk. Regular events are kept in memory and replayed;
// ephemeral events (20000-29999) go to whoever is listening and are
// forgotten. Signatures are checked, as a real relay would.
//
//   node test/ws-relay.mjs [port]      (default 7777, or $KINCLUDE_RELAY_PORT)
import { WebSocketServer } from 'ws'
import { verifyEvent } from 'nostr-tools/pure'
import { matchFilters } from 'nostr-tools/filter'

export function startRelay(port = 7777) {
  const stored = []
  const ids = new Set()
  const wss = new WebSocketServer({ port })
  wss.on('connection', (ws) => {
    const subs = new Map()
    ws.on('message', (raw) => {
      let msg
      try { msg = JSON.parse(String(raw)) } catch { return }
      if (!Array.isArray(msg)) return
      const [type, ...rest] = msg
      if (type === 'EVENT') {
        const event = rest[0]
        if (!event || !verifyEvent(event)) {
          ws.send(JSON.stringify(['OK', event?.id ?? '', false, 'invalid: bad signature']))
          return
        }
        ws.send(JSON.stringify(['OK', event.id, true, '']))
        if (ids.has(event.id)) return
        ids.add(event.id)
        const ephemeral = event.kind >= 20000 && event.kind < 30000
        if (!ephemeral) stored.push(event)
        for (const client of wss.clients) {
          if (client.readyState !== 1) continue
          for (const [subId, filters] of client.subs ?? []) {
            if (matchFilters(filters, event)) client.send(JSON.stringify(['EVENT', subId, event]))
          }
        }
      } else if (type === 'REQ') {
        const [subId, ...filters] = rest
        subs.set(subId, filters)
        ws.subs = subs
        for (const event of stored) if (matchFilters(filters, event)) ws.send(JSON.stringify(['EVENT', subId, event]))
        ws.send(JSON.stringify(['EOSE', subId]))
      } else if (type === 'CLOSE') {
        subs.delete(rest[0])
      }
    })
    ws.subs = subs
  })
  return { wss, close: () => new Promise((r) => wss.close(r)) }
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const port = Number(process.argv[2] ?? process.env.KINCLUDE_RELAY_PORT ?? 7777)
  startRelay(port)
  console.log(`kinclude test relay on ws://localhost:${port}`)
}
