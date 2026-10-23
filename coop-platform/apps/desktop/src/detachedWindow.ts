import { adoptCredentials, currentCredentials } from './api'

const inTauri = typeof window !== 'undefined' && '__TAURI_INTERNALS__' in window

const NEED_SESSION = 'coop:need-session'
const SESSION = 'coop:session'
const SAVED = 'coop:loanapp-saved'
const CLOSE_ALL = 'coop:close-detached'

export const LOAN_APP_ROUTE = '#/loan-application'

export const SEARCH_SIZE = { w: 560, h: 520 }
export const FORM_SIZE = { w: 1120, h: 780 }

interface Start { branchCode?: number; clientId?: number; typeCode?: number; applicationId?: number }

/** Opens the loan application form in a window of its own. Returns false when the window could not be opened. */
export function openLoanApplicationWindow(start: Start): boolean {
  const q = new URLSearchParams()
  if (start.branchCode != null) q.set('b', String(start.branchCode))
  if (start.clientId != null) q.set('c', String(start.clientId))
  if (start.typeCode != null) q.set('t', String(start.typeCode))
  if (start.applicationId != null) q.set('a', String(start.applicationId))
  const hash = `${LOAN_APP_ROUTE}?${q}`
  // With no member yet it is only a search box, so the window starts small and grows when there is a loan to profile.
  const small = start.clientId == null && start.applicationId == null
  if (!inTauri) return window.open(`${window.location.pathname}${hash}`, '_blank', 'popup,width=900,height=760') != null
  import('@tauri-apps/api/webviewWindow').then(({ WebviewWindow }) => {
    const w = new WebviewWindow(`loanapp-${Date.now()}`, {
      url: `index.html${hash}`, title: 'New loan application', visible: false, width: small ? SEARCH_SIZE.w : FORM_SIZE.w, height: small ? SEARCH_SIZE.h : FORM_SIZE.h, minWidth: 480, minHeight: 400, decorations: true, center: true,
    })
    w.once('tauri://error', (e) => console.error('Could not open the loan application window', e))
  })
  return true
}

/** In the main window: gives the sign-in to detached windows that ask for it, and closes them when the person signs out. */
export async function serveDetachedWindows(onSaved: () => void): Promise<() => void> {
  if (!inTauri) {
    let ch: BroadcastChannel | null = null
    try { ch = new BroadcastChannel(SAVED); ch.onmessage = onSaved } catch { /* not supported */ }
    return () => ch?.close()
  }
  const { listen, emit } = await import('@tauri-apps/api/event')
  const offs = await Promise.all([
    listen(NEED_SESSION, () => { const token = currentCredentials(); if (token) emit(SESSION, token) }),
    listen(SAVED, onSaved),
  ])
  const signedOut = () => { emit(CLOSE_ALL) }
  window.addEventListener('coop:signed-out', signedOut)
  return () => { offs.forEach((f) => f()); window.removeEventListener('coop:signed-out', signedOut) }
}

/** In a detached window: asks the main window for the sign-in (a new window starts without it) and closes itself when the main window signs out. */
export async function adoptSessionFromMainWindow(): Promise<void> {
  if (currentCredentials() || !inTauri) return
  const { listen, emit } = await import('@tauri-apps/api/event')
  await listen(CLOSE_ALL, () => { void closeThisWindow() })
  await new Promise<void>((resolve) => {
    const timer = setTimeout(resolve, 2000) // no main window answered: the normal sign-in screen shows
    void listen<string>(SESSION, (e) => { adoptCredentials(e.payload); clearTimeout(timer); resolve() }).then(() => emit(NEED_SESSION))
  })
}

/** Tells the main window that the detached form saved an application, so its lists refresh. */
export function announceLoanApplicationSaved() {
  if (!inTauri) { try { new BroadcastChannel(SAVED).postMessage(1) } catch { /* not supported */ } return }
  void import('@tauri-apps/api/event').then(({ emit }) => emit(SAVED))
}

const reducedMotion = () => window.matchMedia('(prefers-reduced-motion: reduce)').matches
let resizing = 0

/**
 * Resizes this window to the given inner size, easing over about a fifth of a second and keeping its centre where it was.
 * The window system has no animated resize of its own, so it is stepped. Does nothing outside the desktop app.
 */
export async function resizeThisWindow(to: { w: number; h: number }): Promise<void> {
  if (!inTauri) return
  const { getCurrentWindow, LogicalSize, LogicalPosition } = await import('@tauri-apps/api/window')
  const win = getCurrentWindow()
  const scale = await win.scaleFactor()
  const from = (await win.innerSize()).toLogical(scale)
  const pos = (await win.outerPosition()).toLogical(scale)
  const dw = to.w - from.width, dh = to.h - from.height
  if (Math.abs(dw) < 2 && Math.abs(dh) < 2) return
  const run = ++resizing
  const place = (t: number) => Promise.all([
    win.setSize(new LogicalSize(Math.round(from.width + dw * t), Math.round(from.height + dh * t))),
    win.setPosition(new LogicalPosition(Math.round(pos.x - (dw * t) / 2), Math.max(0, Math.round(pos.y - (dh * t) / 2)))),
  ])
  if (reducedMotion()) { await place(1); return }
  const start = performance.now(), ms = 220
  await new Promise<void>((resolve) => {
    const step = async (now: number) => {
      if (run !== resizing) return resolve() // a newer resize took over
      const p = Math.min(1, (now - start) / ms)
      await place(1 - Math.pow(1 - p, 3))
      if (p < 1) requestAnimationFrame(step); else resolve()
    }
    requestAnimationFrame(step)
  })
}

/**
 * In a detached window, once the first screen has painted: shows the window (it is created hidden so there is no blank flash) and lets the
 * content ease in. If anything goes wrong the window is shown anyway after a few seconds.
 */
let revealed = false

export async function revealThisWindow(): Promise<void> {
  if (revealed) return
  revealed = true
  const root = document.documentElement
  if (!inTauri) { root.dataset.win = 'in'; return }
  const { getCurrentWindow } = await import('@tauri-apps/api/window')
  const win = getCurrentWindow()
  // Closing by the title bar goes through the same fade as the Close button.
  await win.onCloseRequested((e) => { e.preventDefault(); void closeThisWindow() })
  await win.show()
  root.dataset.win = 'in'
  await win.setFocus()
}

let closing = false

/** Eases the content out, then closes this window. Closes at once when the system asks for less motion. */
export async function closeThisWindow(): Promise<void> {
  if (closing) return
  closing = true
  if (!inTauri) { window.close(); return }
  if (!reducedMotion()) {
    document.documentElement.dataset.win = 'out'
    await new Promise((r) => setTimeout(r, 180))
  }
  const { getCurrentWindow } = await import('@tauri-apps/api/window')
  await getCurrentWindow().destroy()
}
