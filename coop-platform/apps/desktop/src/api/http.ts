// HTTP Basic until Keycloak arrives. The credentials are kept for this window's session only (cleared when it closes).

const SESSION_KEY = 'coop.session'
let credentials: string | null = (() => {
  try { return sessionStorage.getItem(SESSION_KEY) } catch { return null }
})()

/** Fired when the server rejects the saved sign-in, or the person signs out. */
export const SIGNED_OUT = 'coop:signed-out'

/** Hands the sign-in to a window the app opened itself (a detached form), which starts without one. */
export const currentCredentials = () => credentials
export function adoptCredentials(token: string) {
  credentials = token
  try { sessionStorage.setItem(SESSION_KEY, token) } catch { /* storage unavailable */ }
}

/** Used by sign-in: the same as adoptCredentials, named for what it does there. */
export const setCredentials = adoptCredentials

const authHeader = () => (credentials ? `Basic ${credentials}` : '')

export function signOut() {
  credentials = null
  try { sessionStorage.removeItem(SESSION_KEY) } catch { /* storage unavailable */ }
  window.dispatchEvent(new Event(SIGNED_OUT))
}

/** Every request goes through here: it adds the sign-in, and signs out if the server no longer accepts it. */
export async function http(url: string, init: RequestInit = {}): Promise<Response> {
  const res = await fetch(url, { ...init, headers: { Authorization: authHeader(), ...init.headers } })
  if (res.status === 401 && credentials) signOut()
  return res
}

export async function getJson<T>(url: string): Promise<T> {
  const res = await http(url)
  if (!res.ok) throw new Error(`Request failed (${res.status})`)
  return res.json()
}

export async function send<T>(method: string, url: string, body?: unknown): Promise<T> {
  const res = await http(url, {
    method,
    headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  if (!res.ok) {
    let message = `Request failed (${res.status})`
    try {
      const j = await res.json()
      if (j.message) message = j.message
    } catch { /* keep the generic message */ }
    throw new Error(message)
  }
  const text = await res.text() // some replies have no body
  return (text ? JSON.parse(text) : undefined) as T
}
