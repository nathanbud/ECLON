// Which server (backend) this app talks to. A person can keep several (the office server, a test server) and pick one on the sign-in screen.
// The list lives in this computer's browser storage, shared by every window of the app, so a window the app opens itself talks to the same server.
// This file imports nothing from the rest of the app so that http.ts can use it.

export interface ServerEntry { id: string; name: string; url: string }

const LIST_KEY = 'coop.servers'
const ACTIVE_KEY = 'coop.server'
export const LOCAL_ID = 'local'

const inTauri = typeof window !== 'undefined' && '__TAURI_INTERNALS__' in window

/** The built-in entry. Under the dev server (and in a browser) "/api" is answered by the page's own address; the installed desktop app has no such address, so it asks the server on this computer. */
export const localServer = (): ServerEntry => ({ id: LOCAL_ID, name: 'This computer', url: inTauri && !import.meta.env.DEV ? 'http://localhost:8090' : '' })

/** The address as a bare origin ("http://host:port"), or null when it is not a web address the app may call. Only http and https; no sign-in details, path or options. */
export function normalizeServerUrl(raw: string): string | null {
  const text = raw.trim()
  if (!text) return null
  let u: URL
  try { u = new URL(/^[a-z][a-z0-9+.-]*:\/\//i.test(text) ? text : `http://${text}`) } catch { return null }
  if (u.protocol !== 'http:' && u.protocol !== 'https:') return null
  if (u.username || u.password || !u.hostname) return null
  return u.origin
}

const read = <T,>(key: string, fallback: T): T => {
  try {
    const text = localStorage.getItem(key)
    return text ? (JSON.parse(text) as T) : fallback
  } catch { return fallback }
}
const write = (key: string, value: unknown) => { try { localStorage.setItem(key, JSON.stringify(value)) } catch { /* storage unavailable */ } }

const saved = (): ServerEntry[] =>
  read<ServerEntry[]>(LIST_KEY, []).filter((s) => s && typeof s.id === 'string' && typeof s.name === 'string' && typeof s.url === 'string' && s.id !== LOCAL_ID && normalizeServerUrl(s.url) !== null)

/** The built-in entry first, then the ones this person added. */
export const servers = (): ServerEntry[] => [localServer(), ...saved()]

export function activeServer(): ServerEntry {
  const id = read<string>(ACTIVE_KEY, LOCAL_ID)
  return servers().find((s) => s.id === id) ?? localServer()
}

/** Everything the app asks for starts here: "/api/..." becomes the chosen server's address. Left as it is when the server is this page's own address. */
export function apiUrl(path: string): string {
  if (!path.startsWith('/api')) return path
  return activeServer().url.replace(/\/+$/, '') + path
}

export function chooseServer(id: string) {
  write(ACTIVE_KEY, id)
}

/** Adds a server and returns it. Throws a message fit for the person when the name or address is not usable. */
export function addServer(name: string, rawUrl: string): ServerEntry {
  const entry = checked(name, rawUrl, null)
  write(LIST_KEY, [...saved(), entry])
  return entry
}

export function editServer(id: string, name: string, rawUrl: string): ServerEntry {
  if (id === LOCAL_ID) throw new Error('The built-in entry cannot be changed. Add a server of your own instead.')
  const entry = checked(name, rawUrl, id)
  write(LIST_KEY, saved().map((s) => (s.id === id ? entry : s)))
  return entry
}

export function deleteServer(id: string) {
  if (id === LOCAL_ID) throw new Error('The built-in entry cannot be deleted.')
  write(LIST_KEY, saved().filter((s) => s.id !== id))
  if (read<string>(ACTIVE_KEY, LOCAL_ID) === id) chooseServer(LOCAL_ID)
}

function checked(rawName: string, rawUrl: string, selfId: string | null): ServerEntry {
  const name = rawName.trim()
  if (!name) throw new Error('Give the server a name.')
  if (name.length > 60) throw new Error('The name is longer than 60 characters.')
  const url = normalizeServerUrl(rawUrl)
  if (!url) throw new Error('Enter the server address as http://host:port (for example http://192.168.1.20:8090).')
  const others = servers().filter((s) => s.id !== selfId)
  if (others.some((s) => s.name.toLowerCase() === name.toLowerCase())) throw new Error(`There is already a server named "${name}".`)
  if (others.some((s) => s.url === url)) throw new Error('That address is already in the list.')
  return { id: selfId ?? `s${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`, name, url }
}
