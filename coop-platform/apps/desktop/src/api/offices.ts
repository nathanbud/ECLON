import { getJson, send } from './http'

// ---- Offices (Settings > Organization > Offices): the cooperative's offices, which members and users belong to ----------------
export interface OfficeRow {
  id: number
  name: string
  /** members whose office this is */
  members: number
  /** users who have this office in their office list */
  users: number
}
export interface Offices {
  /** false until the database lets the application change its offices (PostgreSQL V035, MySQL V072) */
  editable: boolean
  offices: OfficeRow[]
  /** one more than the highest number in use, or 1 */
  nextNumber: number
  maxNameLength: number
}

export const getOffices = () => getJson<Offices>('/api/admin/offices')
export const addOffice = (id: number, name: string, reason: string) => send<Offices>('POST', '/api/admin/offices', { id, name, reason })
export const renameOffice = (id: number, name: string, reason: string) => send<Offices>('PUT', `/api/admin/offices/${id}`, { name, reason })
export const deleteOffice = (id: number, reason: string) => send<Offices>('DELETE', `/api/admin/offices/${id}`, { reason })
