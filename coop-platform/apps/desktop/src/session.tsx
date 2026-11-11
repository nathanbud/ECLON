import { Box, Button, CircularProgress, TextField, Typography } from '@mui/material'
import { useQueryClient } from '@tanstack/react-query'
import { createContext, useContext, useEffect, useState, type FormEvent, type ReactNode } from 'react'
import { restoreSession, signIn, signOut, SIGNED_OUT, type Me } from './api'
import { appBackground } from './glass'
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
  const initials = me.name.split(/\s+/).filter(Boolean).slice(0, 2).map((w) => w[0]!.toUpperCase()).join('') || me.username.slice(0, 2).toUpperCase()
  return (
    <Box
      role="dialog" aria-modal="true" aria-label="Screen locked"
      sx={(t) => ({
        position: 'fixed', inset: 0, zIndex: 2000, display: 'grid', placeItems: 'center', p: 2, background: appBackground(t),
        backdropFilter: 'blur(28px) saturate(1.4)', WebkitBackdropFilter: 'blur(28px) saturate(1.4)',
      })}
    >
      <Box component="form" onSubmit={submit} sx={{ width: '100%', maxWidth: 320, display: 'flex', flexDirection: 'column', alignItems: 'center', textAlign: 'center' }}>
        <Box
          aria-hidden
          sx={(t) => ({
            width: 96, height: 96, borderRadius: '50%', display: 'grid', placeItems: 'center', mb: 2, fontSize: 34, fontWeight: 500, letterSpacing: '0.02em', color: '#fff',
            background: t.palette.mode === 'dark' ? 'linear-gradient(160deg,#5f6672,#3a3f48)' : 'linear-gradient(160deg,#a6adbb,#7d8594)',
            boxShadow: '0 10px 30px rgba(0,0,0,0.22), inset 0 1px 0 rgba(255,255,255,0.35)',
          })}
        >{initials}</Box>
        <Typography component="h1" sx={{ fontSize: 22, fontWeight: 600, letterSpacing: '-0.02em' }}>{me.name}</Typography>
        <Typography sx={{ fontSize: 13, mt: 0.5, mb: 2.5 }} color="text.secondary">Locked after a while away. Nothing was lost.</Typography>
        <TextField
          type="password" label="Password" size="small" value={password} onChange={(e) => setPassword(e.target.value)} fullWidth autoFocus autoComplete="current-password"
        />
        <Button type="submit" variant="contained" fullWidth disabled={busy || !password} sx={{ mt: 1.5 }}>
          {busy ? <CircularProgress size={18} color="inherit" /> : 'Unlock'}
        </Button>
        {error && <Typography role="alert" sx={{ mt: 1.5, fontSize: 13 }} color="error">{error}</Typography>}
        <Button color="inherit" onClick={signOut} sx={{ mt: 3, fontSize: 13, fontWeight: 500, opacity: 0.7, textTransform: 'none', borderRadius: 999 }}>Sign out</Button>
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
