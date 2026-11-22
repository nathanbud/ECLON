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
/** switchedOn: for Membership Officer, the Member tab permissions the grant switched on (by label); empty when all were on already. */
export interface RoleGranted { role: string; switchedOn?: string[] }
export const grantRole = (username: string, role: string) => send<RoleGranted | undefined>('PUT', `/api/admin/users/${encodeURIComponent(username)}/roles/${role}`)
export const revokeRole = (username: string, role: string) => send<void>('DELETE', `/api/admin/users/${encodeURIComponent(username)}/roles/${role}`)

// ---- a person's legacy account setup (administrators only, read-only) ---------------------------------------------

export interface UserOption { value: string; label: string }
export interface UserField {
  label: string
  /** What to show; for choices and lists, the descriptions. */
  value: string | null
  kind: 'text' | 'date' | 'money' | 'number' | 'bool' | 'list' | 'note'
  /** What the setting allows in this application, shown under it. */
  hint?: string
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

/**
 * Switches a person's account on or off (the old `AccessIsDisabled` flag), through the same save as the account setup, so it is checked, guarded
 * against a clash and audited the same way. The stored value is read first: the list only knows enabled or not, and "not disabled" may be stored
 * as 0 or left blank, which the save tells apart. Someone switched off is signed out on their next action.
 */
export async function setUserEnabled(username: string, enabled: boolean) {
  const profile = await getUserProfile(username)
  if (!profile.canEdit) throw new Error(profile.editNote || 'You cannot change this account.')
  const field = Object.values(profile.tabs).flat().flatMap((s) => s.fields).find((f) => f.key === 'AccessIsDisabled')
  if (!field) throw new Error('This server does not offer switching an account off.')
  return saveUserProfile(username, { AccessIsDisabled: { from: field.raw ?? null, to: enabled ? '0' : '1' } })
}

export const saveUserTransactionTypes =(username: string, changes: { code: number; column: string; allowed: boolean }[]) =>
  send<{ changed: number }>('PUT', `/api/admin/users/${encodeURIComponent(username)}/transaction-types`, { changes })

// ---- audit log (administrators) -----------------------------------------------------------------------------------

/** One changed field of an audit entry; a null side means there was no value. */
export interface AuditChange { field: string; label: string; before: string | null; after: string | null }
export interface AuditEntry {
  /** Local time to the millisecond, for example 2026-11-13T05:16:17.874 (the fraction is left out when it is .000). */
  id: number; at: string; username: string; action: string; entity: string; entityId: string
  previous: string | null; current: string | null; reason: string | null; clientIp: string | null; clientInfo: string | null
  changes: AuditChange[]
}
export interface AuditFilter { user?: string; entity?: string; action?: string; q?: string; from?: string; to?: string }
export const getAuditLog = (f: AuditFilter, page: number, size: number) => {
  const p = new URLSearchParams({ page: String(page), size: String(size) })
  for (const [k, v] of Object.entries(f)) if (v) p.set(k, v)
  return getJson<{ content: AuditEntry[]; totalElements: number; number: number; size: number }>(`/api/admin/audit-log?${p}`)
}
export const getAuditFacets = () => getJson<{ entities: string[]; actions: string[]; users: string[] }>('/api/admin/audit-log/facets')

// ---- audit integrity (administrators) ------------------------------------------------------------------------------------

export interface AuditIntegrity { installed: boolean; seals: number; lastSealedId: number; lastSealedAt: string | null; unsealedRows: number; nightly: boolean }
export interface AuditVerification { ok: boolean; sealsChecked: number; rowsChecked: number; unsealedRows: number; problems: string[] }
export const getAuditIntegrity = () => getJson<AuditIntegrity>('/api/admin/audit/integrity')
export const sealAuditLog = () => send<AuditIntegrity>('POST', '/api/admin/audit/integrity/seal')
export const verifyAuditLog = () => send<AuditVerification>('POST', '/api/admin/audit/integrity/verify')

// ---- creating a user (administrators; an administrator only by a super administrator) ------------------------------
export interface NewUser {
  username: string; firstName: string; middleName: string | null; lastName: string
  branchCode: number; level: 'USER' | 'ADMIN' | 'SUPERADMIN'
  /** whose permissions to start from (their access flags, transaction rights and roles), or null for none */
  copyFrom: string | null
  validityHours: number
}
export interface CreatedUser {
  username: string; accessId: number; level: string; branchCode: number; copiedFrom: string | null
  transactionRights: number; roles: string[]
  /** the first, temporary password: in this reply only */
  password: TemporaryPassword
}
export const createUser = (u: NewUser) => send<CreatedUser>('POST', '/api/admin/users', u)
