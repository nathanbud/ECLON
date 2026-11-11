import { currentCredentials, http, send, setCredentials } from './http'
import { apiUrl } from './server'

export interface Me {
  username: string
  name: string
  roles: string[]
  canEdit: boolean
  canApprove: boolean
  admin: boolean
  /** Signed in with a temporary password: nothing else opens until it is replaced. */
  mustChangePassword: boolean
}

export async function signIn(username: string, password: string): Promise<Me> {
  const token = btoa(unescape(encodeURIComponent(`${username}:${password}`)))
  const res = await fetch(apiUrl('/api/me'), { headers: { Authorization: `Basic ${token}` } })
  if (res.status === 401) {
    if (res.headers.get('X-Auth-Reason') === 'TEMPORARY_PASSWORD_EXPIRED') throw new Error('Your temporary password has expired. Ask an administrator to issue a new one.')
    if (res.headers.get('X-Auth-Reason') === 'ACCOUNT_LOCKED') {
      const minutes = Math.max(1, Math.ceil(Number(res.headers.get('Retry-After') ?? '900') / 60))
      throw new Error(`Too many wrong passwords. This sign-in is locked for about ${minutes} minute${minutes === 1 ? '' : 's'}, or until an administrator unlocks it.`)
    }
    const left = res.headers.get('X-Auth-Attempts-Left')
    if (left !== null && Number(left) === 0) throw new Error('Too many wrong passwords. This sign-in is now locked for about 15 minutes, or until an administrator unlocks it.')
    if (left !== null && Number.isFinite(Number(left))) {
      const n = Number(left)
      throw new Error(`The username or password is wrong. ${n} attempt${n === 1 ? '' : 's'} left before this sign-in is locked.`)
    }
    throw new Error('The username or password is wrong.')
  }
  if (!res.ok) throw new Error(`Sign-in failed (${res.status}).`)
  setCredentials(token)
  return res.json()
}

/** The password this window signed in with, read back from the saved sign-in. Used to replace a temporary one without asking for it again. */
export function currentPassword(): string | null {
  if (!currentCredentials()) return null
  try {
    const plain = decodeURIComponent(escape(atob(currentCredentials() ?? '')))
    return plain.slice(plain.indexOf(':') + 1)
  } catch { return null }
}

/** A person replaces their own password. */
export const changeOwnPassword = (current: string, next: string) => send<void>('POST', '/api/me/password', { current, next })

/** The signed-in person, or null when there is no saved sign-in or the server no longer accepts it. */
export async function restoreSession(): Promise<Me | null> {
  if (!currentCredentials()) return null
  const res = await http('/api/me')
  return res.ok ? res.json() : null
}
