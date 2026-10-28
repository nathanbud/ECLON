import { Alert, Box, Button, CircularProgress, TextField, Typography } from '@mui/material'
import { useQueryClient } from '@tanstack/react-query'
import { createContext, useContext, useEffect, useState, type FormEvent, type ReactNode } from 'react'
import { restoreSession, signIn, signOut, SIGNED_OUT, type Me } from './api'
import { appBackground, glass } from './glass'
import ChangePasswordScreen from './ChangePasswordScreen'
import SignInPage from './SignInPage'

const SessionCtx = createContext<Me | null>(null)

/** The signed-in person. Only available inside {@link SessionGate}. */
export function useMe(): Me {
  const me = useContext(SessionCtx)
  if (!me) throw new Error('useMe must be used inside SessionGate')
  return me
}

/** Minutes without a key or a click before the screen locks. Unsaved work stays where it is; the same person signs back in with their password. */
const IDLE_MINUTES = 10

function LockScreen({ me, onUnlock }: { me: Me; onUnlock: () => void }) {
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (!password) return
    setBusy(true)
    setError(null)
    try {
      await signIn(me.username, password)
      onUnlock()
    } catch (err) {
      setError((err as Error).message)
      setPassword('')
    } finally {
      setBusy(false)
    }
  }
  return (
    <Box role="dialog" aria-modal="true" aria-label="Screen locked" sx={(t) => ({ position: 'fixed', inset: 0, zIndex: 2000, display: 'grid', placeItems: 'center', p: 2, background: appBackground(t) })}>
      <Box component="form" onSubmit={submit} sx={(t) => ({ width: '100%', maxWidth: 360, p: 3.5, ...glass(t, { elevated: true }) })}>
        <Typography component="h1" sx={{ fontSize: 19, fontWeight: 700, letterSpacing: '-0.02em', mb: 0.5 }}>Locked</Typography>
        <Typography sx={{ fontSize: 13.5, mb: 2 }} color="text.secondary">You were away for a while. Enter the password for {me.name} ({me.username}) to continue. Nothing was lost.</Typography>
        <TextField label="Password" type="password" value={password} onChange={(e) => setPassword(e.target.value)} fullWidth autoFocus autoComplete="current-password" sx={{ mb: 2 }} />
        {error && <Alert severity="error" sx={{ mb: 2 }} role="alert">{error}</Alert>}
        <Box sx={{ display: 'flex', gap: 1 }}>
          <Button type="submit" variant="contained" fullWidth disabled={busy || !password}>{busy ? <CircularProgress size={20} /> : 'Unlock'}</Button>
          <Button color="inherit" onClick={signOut}>Sign out</Button>
        </Box>
      </Box>
    </Box>
  )
}

/** Locks the screen after a period without activity, so a drawer left open at a counter cannot be used by someone else. */
function IdleLock({ me, children }: { me: Me; children: ReactNode }) {
  const [locked, setLocked] = useState(false)
  useEffect(() => {
    if (locked) return
    let timer = window.setTimeout(() => setLocked(true), IDLE_MINUTES * 60_000)
    const reset = () => { window.clearTimeout(timer); timer = window.setTimeout(() => setLocked(true), IDLE_MINUTES * 60_000) }
    const events = ['pointerdown', 'pointermove', 'keydown', 'wheel', 'touchstart'] as const
    events.forEach((ev) => window.addEventListener(ev, reset, { passive: true }))
    return () => { window.clearTimeout(timer); events.forEach((ev) => window.removeEventListener(ev, reset)) }
  }, [locked])
  return (
    <>
      {/* While locked the page behind is inert: it cannot be clicked, tabbed into or read by a screen reader. */}
      <div style={{ display: 'contents' }} inert={locked} aria-hidden={locked}>{children}</div>
      {locked && <LockScreen me={me} onUnlock={() => setLocked(false)} />}
    </>
  )
}

/** Shows the sign-in screen until someone is signed in, and returns to it when they sign out. */
export function SessionGate({ children }: { children: ReactNode }) {
  const qc = useQueryClient()
  const [state, setState] = useState<{ status: 'checking' } | { status: 'out' } | { status: 'in'; me: Me }>({ status: 'checking' })

  useEffect(() => {
    let live = true
    restoreSession().then((me) => { if (live) setState(me ? { status: 'in', me } : { status: 'out' }) })
    const out = () => {
      qc.clear() // nothing the last person loaded may be shown to the next
      setState({ status: 'out' })
    }
    window.addEventListener(SIGNED_OUT, out)
    return () => { live = false; window.removeEventListener(SIGNED_OUT, out) }
  }, [qc])

  if (state.status === 'checking') {
    return <Box sx={{ minHeight: '100dvh', display: 'grid', placeItems: 'center' }}><CircularProgress aria-label="Loading" /></Box>
  }
  if (state.status === 'out') return <SignInPage onSignedIn={(me) => setState({ status: 'in', me })} />
  if (state.me.mustChangePassword) return <ChangePasswordScreen me={state.me} onDone={(me) => setState({ status: 'in', me })} />
  return <SessionCtx.Provider value={state.me}><IdleLock me={state.me}>{children}</IdleLock></SessionCtx.Provider>
}
