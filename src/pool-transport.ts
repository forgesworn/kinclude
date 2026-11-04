import { SimplePool } from 'nostr-tools/pool'
import type { Event } from 'nostr-tools/pure'
import type { RelayTransport } from '@forgesworn/fold-kit'

/**
 * fold-kit's `RelayTransport` over nostr-tools' `SimplePool`: what the app
 * and the CLI talk to real relays through.
 *
 * A publish resolves once any one relay has accepted the event, and rejects
 * only when every relay refused or could not be reached: that is the
 * acknowledgement a guardian's receipt or emergency notice waits for before
 * anything is shown.
 */
export function poolTransport(relays: string[], pool: SimplePool = new SimplePool({ enablePing: true, enableReconnect: true })): RelayTransport {
  if (relays.length === 0) throw new Error('at least one relay')
  return {
    async publish(event: Event) {
      await Promise.any(pool.publish(relays, event))
    },
    subscribe(filters, onEvent, onEose) {
      let waiting = filters.length
      const closers = filters.map((filter) => pool.subscribeMany(relays, filter, {
        onevent: (e) => onEvent(e),
        oneose: () => {
          waiting -= 1
          if (waiting === 0) onEose?.()
        },
      }))
      return () => { for (const c of closers) c.close() }
    },
    close() {
      pool.close(relays)
    },
  }
}
