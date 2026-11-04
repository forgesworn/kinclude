import { generateSecretKey } from 'nostr-tools/pure'
import { BunkerSigner, parseBunkerInput, type BunkerPointer } from 'nostr-tools/nip46'
import { bytesToHex, hexToBytes } from '@noble/hashes/utils'
import { dependantSigner, localSigner, type KincludeSigner, type HouseholdData, type HouseholdStore } from '../../src/index.js'

/**
 * Who this phone is, and where it keeps things.
 *
 * Two ways to be somebody. **My Signet**: a bunker link (NIP-46). The key
 * never reaches this phone; every signature is a request to My Signet. For
 * a child's phone it is the child's dependant identity, which My Signet
 * keeps on a guardian's phone and asks the guardian about. **A key on this
 * phone**: for trying Kinclude out, and for the tests. A child's local key
 * is wrapped so it refuses NIP-44, as My Signet does for a dependant, so
 * the app cannot come to depend on something the real thing refuses.
 */
export type Kind = 'adult' | 'child'

type Saved =
  | { via: 'local'; kind: Kind; sk: string }
  | { via: 'bunker'; kind: Kind; clientSk: string; pointer: BunkerPointer; pubkey: string }

const KEY = 'kinclude.identity.v1'
const DATA = 'kinclude.household.v1'

function read(): Saved | undefined {
  try {
    const raw = localStorage.getItem(KEY)
    return raw ? (JSON.parse(raw) as Saved) : undefined
  } catch {
    return undefined
  }
}

export function savedKind(): Kind | undefined {
  return read()?.kind
}

export async function restoreIdentity(): Promise<KincludeSigner | undefined> {
  const saved = read()
  if (!saved) return undefined
  if (saved.via === 'local') {
    const sk = hexToBytes(saved.sk)
    return saved.kind === 'child' ? dependantSigner(sk) : localSigner(sk)
  }
  return bunkerSigner(saved.kind, hexToBytes(saved.clientSk), saved.pointer, saved.pubkey)
}

export function createLocalIdentity(kind: Kind): KincludeSigner {
  const sk = generateSecretKey()
  localStorage.setItem(KEY, JSON.stringify({ via: 'local', kind, sk: bytesToHex(sk) } satisfies Saved))
  return kind === 'child' ? dependantSigner(sk) : localSigner(sk)
}

export async function connectMySignet(kind: Kind, input: string): Promise<KincludeSigner> {
  const pointer = await parseBunkerInput(input.trim())
  if (!pointer) throw new Error('That is not a My Signet link. It starts with bunker://')
  const clientSk = generateSecretKey()
  const bunker = BunkerSigner.fromBunker(clientSk, pointer)
  await bunker.connect()
  const pubkey = await bunker.getPublicKey()
  localStorage.setItem(KEY, JSON.stringify({ via: 'bunker', kind, clientSk: bytesToHex(clientSk), pointer, pubkey } satisfies Saved))
  return bunkerSigner(kind, clientSk, pointer, pubkey, bunker)
}

function bunkerSigner(kind: Kind, clientSk: Uint8Array, pointer: BunkerPointer, pubkey: string, existing?: BunkerSigner): KincludeSigner {
  const bunker = existing ?? BunkerSigner.fromBunker(clientSk, pointer)
  const signer: KincludeSigner = {
    pubkey,
    signEvent: (e) => bunker.signEvent(e),
  }
  // A dependant's route refuses NIP-44; nothing a child does needs it.
  if (kind === 'adult') {
    signer.nip44Encrypt = (peer, text) => bunker.nip44Encrypt(peer, text)
    signer.nip44Decrypt = (peer, text) => bunker.nip44Decrypt(peer, text)
  }
  return signer
}

export const localStore: HouseholdStore = {
  async load() {
    try {
      const raw = localStorage.getItem(DATA)
      return raw ? (JSON.parse(raw) as HouseholdData) : undefined
    } catch {
      return undefined
    }
  },
  async save(data) {
    localStorage.setItem(DATA, JSON.stringify(data))
  },
}

export function forgetEverything(): void {
  localStorage.removeItem(KEY)
  localStorage.removeItem(DATA)
  localStorage.removeItem('kinclude.relays')
}

const DEFAULT_RELAYS = (import.meta.env.KINCLUDE_RELAYS as string | undefined)?.split(',').filter(Boolean)
  ?? ['wss://relay.damus.io', 'wss://nos.lol']

export function relays(): string[] {
  const fromQuery = new URLSearchParams(location.search).getAll('relay').filter((r) => /^wss?:\/\//.test(r))
  if (fromQuery.length) return fromQuery
  try {
    const saved = JSON.parse(localStorage.getItem('kinclude.relays') ?? 'null') as unknown
    if (Array.isArray(saved) && saved.length && saved.every((r) => typeof r === 'string')) return saved as string[]
  } catch {
    // Fall through to the defaults.
  }
  return DEFAULT_RELAYS
}

export function setRelays(list: string[]): void {
  localStorage.setItem('kinclude.relays', JSON.stringify(list))
}
