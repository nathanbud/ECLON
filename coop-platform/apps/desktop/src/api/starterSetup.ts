import { getJson, send } from './http'

// ---- Starter setup (Settings > System): the system's standard lists, compared with this database and loaded where missing ----------------
export interface StarterList {
  table: string
  group: string
  label: string
  /** rows the application ships */
  inPackage: number
  /** rows on this database now */
  here: number
  /** rows of the package not here yet (by key), that can be added */
  missing: number
  /** rows of the package left out because what they point to is not on this database */
  leftOut: number
  leftOutWhy: string[]
  /** why this list cannot be loaded here at all, or null */
  problem: string | null
}
export interface StarterStatus { lists: StarterList[]; missing: number; leftOut: number; canLoad: boolean; cannotLoad: string | null }
export interface StarterResult { added: number; loaded: string[]; status: StarterStatus }

export const getStarterSetup = () => getJson<StarterStatus>('/api/admin/starter-setup')
/** Adds the missing rows of every list (or of the tables named), with the reason for the audit log. Nothing already there is changed. */
export const loadStarterLists = (reason: string, tables?: string[]) => send<StarterResult>('POST', '/api/admin/starter-setup/load', { reason, tables: tables ?? null })
