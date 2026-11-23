import { getJson, send } from './http'

// ---- SL Controls (Settings > Membership): which accounts open by themselves with a new or reactivated member ----------------
export interface SlTypeRow {
  slc: number; slt: number; name: string; minBalance: number | null; interestRate: number | null
  /** switched off in Account Types: cannot be switched on here */
  disabled: boolean
  /** member type code -> opens by itself */
  on: Record<string, boolean>
  /** on for a type not set up on this database (left from another cooperative's list): can only be switched off */
  missing?: boolean
}
export interface SlClassGroup { slc: number; name: string; note: string; types: SlTypeRow[] }
export interface SlControls {
  branch: number
  /** false while the table is not installed: the built-in list is shown and cannot be changed */
  installed: boolean
  memberTypes: { code: number; name: string }[]
  classes: SlClassGroup[]
  lastChangedBy: string | null; lastChangedAt: string | null
}
export interface SlChange { clientType: number; slc: number; slt: number; on: boolean }

export const getSlControls = () => getJson<SlControls>('/api/admin/sl-controls')
export const saveSlControls = (changes: SlChange[], reason: string) => send<SlControls>('PUT', '/api/admin/sl-controls', { changes, reason })
