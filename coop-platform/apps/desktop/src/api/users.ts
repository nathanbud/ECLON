import { getJson, send } from './http'

// ---- user administration (administrators only) --------------------------------------------------------------------

export interface AdminUser {
  username: string
  name: string | null
  level: string | null
  enabled: boolean
  canEdit: boolean
  hasPassword: boolean
  /** NONE, ACTIVE (their own), TEMPORARY (issued, not yet replaced) or EXPIRED (issued, ran out unused). */
  passwordState: 'NONE' | 'ACTIVE' | 'TEMPORARY' | 'EXPIRED'
  tempExpiresAt: string | null
  roles: string[]
}

export const listUsers = () => getJson<AdminUser[]>('/api/admin/users')
export interface TemporaryPassword { username: string; password: string; validityHours: number; expiresAt: string }
/** The system makes the password; it is in this reply only. */
export const issueTemporaryPassword = (username: string, validityHours: number) =>
  send<TemporaryPassword>('PUT', `/api/admin/users/${encodeURIComponent(username)}/password`, { validityHours })
export const grantRole = (username: string, role: string) => send<void>('PUT', `/api/admin/users/${encodeURIComponent(username)}/roles/${role}`)
export const revokeRole = (username: string, role: string) => send<void>('DELETE', `/api/admin/users/${encodeURIComponent(username)}/roles/${role}`)

// ---- a person's legacy account setup (administrators only, read-only) ---------------------------------------------

export interface UserOption { value: string; label: string }
export interface UserField {
  label: string
  /** What to show; for choices and lists, the descriptions. */
  value: string | null
  kind: 'text' | 'date' | 'money' | 'number' | 'bool' | 'list'
  /** The column this field writes to, whether it may be edited now, the stored value, and the choices if any. */
  key: string
  editable: boolean
  raw?: string | null
  options?: UserOption[]
}
export interface UserSection { title: string; fields: UserField[] }
export interface UserProfile {
  username: string
  name: string
  enabled: boolean
  hasPassword: boolean
  roles: string[]
  /** Whether this person's account can be edited by you right now, and if not, why. */
  canEdit: boolean
  editNote?: string | null
  /** Sections for each tab: general, transactions, pos, client, receivables, deposits, others. */
  tabs: Record<string, UserSection[]>
}
export interface TransactionPermissions {
  columns: string[]
  keys: string[]
  canEdit: boolean
  editNote?: string | null
  rows: { code: number; name: string; allowed: boolean[] }[]
}

export const getUserProfile = (username: string) => getJson<UserProfile>(`/api/admin/users/${encodeURIComponent(username)}/profile`)
export const getUserTransactionTypes = (username: string) =>
  getJson<TransactionPermissions>(`/api/admin/users/${encodeURIComponent(username)}/transaction-types`)

/** Saves changed fields of a person's account setup. Each change carries the value it started from, so a clash is caught. */
export const saveUserProfile = (username: string, changes: Record<string, { from: string | null; to: string | null }>) =>
  send<{ changed: number }>('PATCH', `/api/admin/users/${encodeURIComponent(username)}/profile`, { changes })

export const saveUserTransactionTypes = (username: string, changes: { code: number; column: string; allowed: boolean }[]) =>
  send<{ changed: number }>('PUT', `/api/admin/users/${encodeURIComponent(username)}/transaction-types`, { changes })

// ---- audit log (administrators) -----------------------------------------------------------------------------------

export interface AuditEntry {
  id: number; at: string; username: string; action: string; entity: string; entityId: string
  previous: string | null; current: string | null; reason: string | null
}
export interface AuditFilter { user?: string; entity?: string; action?: string; q?: string; from?: string; to?: string }
export const getAuditLog = (f: AuditFilter, page: number, size: number) => {
  const p = new URLSearchParams({ page: String(page), size: String(size) })
  for (const [k, v] of Object.entries(f)) if (v) p.set(k, v)
  return getJson<{ content: AuditEntry[]; totalElements: number; number: number; size: number }>(`/api/admin/audit-log?${p}`)
}
export const getAuditFacets = () => getJson<{ entities: string[]; actions: string[]; users: string[] }>('/api/admin/audit-log/facets')
