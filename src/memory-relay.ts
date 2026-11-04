import type { Event } from 'nostr-tools/pure'
import { matchFilters, type Filter } from 'nostr-tools/filter'
import type { RelayTransport } from '@forgesworn/fold-kit'

/**
 * A relay in this process, for tests and for the offline demo.
 *
 * Regular events are stored and replayed to every new subscription before
 * its end-of-stored-events; ephemeral events (20000-29999) are delivered to
 * whoever is listening and forgotten. Delivery is asynchronous, as it is
 * over a socket, so a test cannot lean on an order a network would not
 * give it.
 */
export class MemoryRelay {
  readonly stored: Event[] = []
  private readonly subs = new Set<{ filters: Filter[]; onEvent: (e: Event) => void; open: boolean }>()
  /** Every publish, in order, for tests that check what was sent when. */
  readonly log: Event[] = []

  connect(): RelayTransport & { readonly relay: MemoryRelay } {
    const mine = new Set<{ open: boolean }>()
    const relay = this
    return {
      relay,
      async publish(event: Event) {
        await Promise.resolve()
        relay.accept(event)
      },
      subscribe(filters, onEvent, onEose) {
        const sub = { filters, onEvent: (e: Event) => onEvent(e, 'memory://'), open: true }
        mine.add(sub)
        queueMicrotask(() => {
          if (!sub.open) return
          for (const e of [...relay.stored]) if (sub.open && matchFilters(filters, e)) sub.onEvent(e)
          if (sub.open) {
            relay.subs.add(sub)
            onEose?.()
          }
        })
        return () => {
          sub.open = false
          relay.subs.delete(sub)
          mine.delete(sub)
        }
      },
      close() {
        for (const s of mine) {
          s.open = false
          relay.subs.delete(s as never)
        }
        mine.clear()
      },
    }
  }

  accept(event: Event): void {
    if (this.log.some((e) => e.id === event.id)) return
    this.log.push(event)
    const ephemeral = event.kind >= 20000 && event.kind < 30000
    if (!ephemeral) this.stored.push(event)
    for (const sub of [...this.subs]) {
      if (sub.open && matchFilters(sub.filters, event)) {
        queueMicrotask(() => { if (sub.open) sub.onEvent(event) })
      }
    }
  }
}
