import { apiUrl } from './server'

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

/** Why the person was signed out without asking (shown once on the sign-in screen), or null after they signed out themselves. */
let signedOutBecause: string | null = null
export function takeSignOutReason(): string | null {
  const r = signedOutBecause
  signedOutBecause = null
  return r
}

/** What the sign-in screen says when the password is right but an administrator has switched the account off. */
export const ACCOUNT_DISABLED_SIGN_IN = 'Your account is disabled. An administrator has switched it off, so you cannot sign in. Ask an administrator to switch it on again.'
/** What the sign-in screen says once, after someone working was signed out because their account was switched off. */
export const ACCOUNT_DISABLED_SIGNED_OUT = 'Your account was disabled. An administrator switched it off while you were signed in, so you were signed out. Nothing you saved was lost. Ask an administrator to switch it on again.'

/** The words for a sign-in the server stopped accepting while the person was working. */
function refusedSignIn(res: Response): string {
  if (res.headers.get('X-Auth-Reason') === 'ACCOUNT_DISABLED') return ACCOUNT_DISABLED_SIGNED_OUT
  if (res.headers.get('X-Auth-Reason') === 'TEMPORARY_PASSWORD_EXPIRED') return 'You were signed out because your temporary password ran out. Ask an administrator for a new one.'
  return 'You were signed out because the server no longer accepts your sign-in. An administrator may have switched your account off or reset your password. Sign in again, or ask an administrator.'
}

export function signOut(reason?: string) {
  signedOutBecause = reason ?? null
  credentials = null
  try { sessionStorage.removeItem(SESSION_KEY) } catch { /* storage unavailable */ }
  window.dispatchEvent(new Event(SIGNED_OUT))
}

/** Every request goes through here: it adds the sign-in, and signs out if the server no longer accepts it. */
export async function http(url: string, init: RequestInit = {}): Promise<Response> {
  let res: Response
  try {
    res = await fetch(apiUrl(url), { ...init, headers: { Authorization: authHeader(), ...init.headers } })
  } catch (e) {
    // fetch only rejects when no answer came back at all (server stopped, network down, wrong address), or when the caller cancelled
    if (e instanceof DOMException && e.name === 'AbortError') throw e
    throw new Error(CANNOT_REACH)
  }
  if (res.status === 401 && credentials) signOut(refusedSignIn(res))
  return res
}

export const CANNOT_REACH = 'Cannot reach the server. Check the network cable or Wi-Fi, or ask whether the server is running, then try again.'

/** Words a person can act on, for when the server gives no readable reason of its own. */
const BY_STATUS: Record<number, string> = {
  400: 'Some details are missing or not valid. Check the form and try again.',
  401: 'Your sign-in has ended. Sign in again.',
  403: 'You do not have permission to do this. Ask an administrator if you need it.',
  404: 'That record was not found. It may have been removed; reload the screen.',
  409: 'This cannot be done right now because the record changed. Reload the screen and try again.',
  413: 'The file is too large.',
  415: 'That kind of file is not accepted.',
}
/** When the server failed and said nothing a person can use (an older server, or a reply from something in between). */
const SERVER_TROUBLE = 'The server failed while doing this and gave no reason. Try again; if it happens again, tell your administrator the time it happened, so they can find it in the server’s log (logs/backend.log).'

/** The server program did not answer: the development proxy or a gateway in between says so with these. */
const NOT_ANSWERING: Record<number, string> = {
  502: 'The server program is not answering (it may be stopped or restarting). Start it again (scripts/run-backend.ps1 on the server computer) or ask your administrator, then try again.',
  503: 'The server is busy or not ready yet. Wait a moment and try again; if it keeps happening, ask your administrator whether the server and its database are running.',
  504: 'The server took too long to answer. Try again; for a large report, choose a shorter period.',
}

/** Text the server wrote for people (a sentence), as opposed to a technical message from an unexpected failure. */
function readable(message: string): boolean {
  if (message.length < 8 || message.length > 600) return false
  if (/Exception|SQL|jdbc|JDBC|PreparedStatement|StatementCallback|nested exception|\bjava\.[a-z]|\borg\.[a-z]|could not execute|No message available|^Access Denied$|^Forbidden$|^Unauthorized$|^Bad Request$|^Not Found$|^Internal Server Error$/.test(message)) return false
  return /[A-Za-z]/.test(message)
}

/** The message shown for a failed reply: the server's own explanation when it is meant for people, otherwise plain words by status. */
export function friendlyError(status: number, serverMessage?: string | null): string {
  if (serverMessage && readable(serverMessage)) return serverMessage
  if (NOT_ANSWERING[status]) return NOT_ANSWERING[status]
  if (status >= 500) return SERVER_TROUBLE
  return BY_STATUS[status] ?? `The server refused this (code ${status}). Try again, or tell your administrator.`
}

/**
 * Reads a failed reply and returns the message to show. An unexpected failure comes with a reference (ServerErrors): its message is already in
 * everyday words (what could not be done, what to do), and its technical details are in the administrators' error log under that reference, so it is
 * shown as it is. Otherwise see {@link friendlyError}.
 */
export async function errorFrom(res: Response): Promise<string> {
  let message: string | null = null
  try {
    const j = await res.json()
    if (j && typeof j.reference === 'string' && typeof j.message === 'string') return j.message
    if (j && typeof j.message === 'string') message = j.message
  } catch { /* no readable body */ }
  return friendlyError(res.status, message)
}

export async function getJson<T>(url: string): Promise<T> {
  const res = await http(url)
  if (!res.ok) throw new Error(await errorFrom(res))
  return res.json()
}

/** requestKey: for a posting, the dialog's key (ui/requestKey); the server writes that posting once whatever how often it is sent. */
export async function send<T>(method: string, url: string, body?: unknown, requestKey?: string): Promise<T> {
  const res = await http(url, {
    method,
    headers: { ...(body === undefined ? {} : { 'Content-Type': 'application/json' }), ...(requestKey ? { 'Idempotency-Key': requestKey } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  if (!res.ok) throw new Error(await errorFrom(res))
  const text = await res.text() // some replies have no body
  return (text ? JSON.parse(text) : undefined) as T
}
