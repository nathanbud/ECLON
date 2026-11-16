import { getJson, send, errorFrom } from './http'
import { apiUrl } from './server'

// Which database the server works on: everyone may see the names, a SUPERADMIN manages them (Settings > Database connection).

/** What the sign-in screen may see: no host or port. */
export interface PublicConnection { id: string; name: string; dbName: string; branch: string | null; active: boolean }
export interface PublicConnections { active: string; connections: PublicConnection[] }

export interface ConnectionRow {
  id: string
  name: string
  host: string
  port: number
  dbName: string
  branchId: number | null
  branchName: string | null
  isDefault: boolean
  active: boolean
  /** The server's own startup setting: shown, never edited or deleted. */
  startup: boolean
}
export interface ConnectionIn { name: string; host: string; port: number | null; dbName: string; branchId: number | null; makeDefault: boolean }
export interface Place { host: string; port: number | null; dbName: string | null }
export interface BranchOption { id: number; name: string }
export interface ConnectionProbe { reachable: boolean; usable: boolean; warnings: string[]; message: string }

/** Read before anyone has signed in, so no sign-in is sent. Asks the server chosen on the sign-in screen, or the one at {@code serverUrl} (a bare address; empty is this page's own) to look at another before choosing it. */
export async function getPublicConnections(serverUrl?: string): Promise<PublicConnections> {
  const path = '/api/public/connections'
  const res = await fetch(serverUrl === undefined ? apiUrl(path) : serverUrl.replace(/\/+$/, '') + path, { cache: 'no-store', signal: AbortSignal.timeout(4000) })
  if (!res.ok) throw new Error(await errorFrom(res))
  return res.json()
}

export const getConnections = () => getJson<ConnectionRow[]>('/api/admin/connections')
export const createConnection = (c: ConnectionIn) => send<ConnectionRow>('POST', '/api/admin/connections', c)
export const updateConnection = (id: string, c: ConnectionIn) => send<ConnectionRow>('PUT', `/api/admin/connections/${id}`, c)
export const deleteConnection = (id: string) => send<void>('DELETE', `/api/admin/connections/${id}`)
export const loadDatabases = (host: string, port: number | null) => send<string[]>('POST', '/api/admin/connections/databases', { host, port, dbName: null })
export const loadBranches = (p: Place) => send<BranchOption[]>('POST', '/api/admin/connections/branches', p)
export const testConnection = (p: Place) => send<ConnectionProbe>('POST', '/api/admin/connections/test', p)
export const activateConnection = (id: string) => send<PublicConnections>('POST', `/api/admin/connections/${id}/activate`)
