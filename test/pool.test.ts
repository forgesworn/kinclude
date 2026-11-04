import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { generateSecretKey } from 'nostr-tools/pure'
// @ts-expect-error plain JS module
import { startRelay } from './ws-relay.mjs'
import { poolTransport } from '../src/pool-transport.js'
import { KincludeRoom } from '../src/room.js'
import { adult } from './fixtures.js'

let relay: { close(): Promise<void> }
const port = 17000 + Math.floor(Math.random() * 1000)
beforeAll(() => { relay = startRelay(port) })
afterAll(async () => { await relay.close() })

describe('over a real socket', () => {
  it('creates a room and reads its own message back from the relay', async () => {
    const mum = adult('Mum')
    const transport = poolTransport([`ws://localhost:${port}`])
    const room = await KincludeRoom.create({ transport, identity: mum.signer, name: 'Mum', roomName: 'Home', phoneSecret: generateSecretKey() })
    await room.send('over the wire')
    const other = poolTransport([`ws://localhost:${port}`])
    const again = new KincludeRoom({ transport: other, identity: mum.signer, name: 'Mum', secrets: room.secrets(), phoneSecret: generateSecretKey() })
    await again.start()
    for (let i = 0; i < 50 && again.view().messages.length === 0; i++) await new Promise((r) => setTimeout(r, 50))
    expect(again.view().name).toBe('Home')
    expect(again.view().messages.map((m) => m.text)).toEqual(['over the wire'])
    room.close()
    again.close()
    transport.close()
    other.close()
  })
})
