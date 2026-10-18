import type { Lookups, Member } from './api'

/** Key of a client: branch plus client number. */
export type Id = { branchCode: number; clientId: number }


export const memberNo = (id: Id) => `${String(id.branchCode).padStart(4, '0')}-${String(id.clientId).padStart(7, '0')}`

export const displayName = (m: Pick<Member, 'accountName' | 'lastName' | 'firstName' | 'middleName' | 'suffix'>) => {
  if (m.accountName) return m.accountName
  const given = [m.firstName, m.middleName ? `${m.middleName[0]}.` : null].filter(Boolean).join(' ')
  return [[m.lastName, given].filter(Boolean).join(', '), m.suffix].filter(Boolean).join(' ') || 'Unnamed client'
}

export const initials = (m: Pick<Member, 'accountName' | 'lastName' | 'firstName'>) =>
  ((m.firstName?.[0] ?? m.accountName?.[0] ?? '') + (m.lastName?.[0] ?? m.accountName?.[1] ?? '')).toUpperCase() || '?'

export function statusOf(m: Pick<Member, 'accountStatus' | 'closedDate'>, lk?: Lookups) {
  const code = m.accountStatus ?? (m.closedDate ? 3 : 1)
  const label = lk?.accountStatus?.[code] ?? (code === 3 ? 'Closed' : 'Active')
  const kind = code === 1 ? 'success' : code === 2 ? 'warning' : code === 99 ? 'info' : 'default'
  return { label, kind } as const
}
