import { Alert, AlertTitle, Box, Button, CircularProgress, IconButton, InputAdornment, TextField, Typography, type Theme } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { useEffect, useState, type FormEvent, type KeyboardEvent } from 'react'
import { FiAlertTriangle, FiDatabase, FiEye, FiEyeOff, FiLock } from 'react-icons/fi'
import { activeServer, apiUrl, getPublicConnections, signIn, type Me, takeSignOutReason } from './api'
import { ACCOUNT_DISABLED_SIGN_IN, ACCOUNT_DISABLED_SIGNED_OUT } from './api/http'

/** The notice for an account an administrator has switched off: a heading and a lock, so it is not taken for a mistyped password. */
function DisabledNotice({ text }: { text: string }) {
  return (
    <Alert severity="error" role="alert" icon={<FiLock />} sx={{ mb: 2 }}>
      <AlertTitle sx={{ fontSize: 13, fontWeight: 600, mb: 0.25 }}>Account disabled</AlertTitle>
      {text}
    </Alert>
  )
}
import { BrandBlock, Mark, SystemLine, useBranding } from './BrandBlock'
import { brand } from './brand'
import ConnectionDialog from './ConnectionDialog'
import { LIGHT } from './glass'
import { tileFill } from './theme'
import { WindowControls } from './TopBar'

const LAST_USER = 'coop.lastUser'
const remembered = () => {
  try { return localStorage.getItem(LAST_USER) ?? '' } catch { return '' }
}

/** The gap between the foot-of-window text and the window's edges, the same on the left and at the bottom, on both sides of the screen. */
const EDGE = 14

const dark = (t: Theme) => t.palette.mode === 'dark'

/** Whether the server answers. An unsigned request to /api/me is refused with 401 when the server is up; anything else (a gateway error, no answer) means it is not reachable. */
function useServerStatus(): 'checking' | 'online' | 'offline' {
  const [status, setStatus] = useState<'checking' | 'online' | 'offline'>('checking')
  useEffect(() => {
    let live = true
    const check = async () => {
      try {
        const res = await fetch(apiUrl('/api/me'), { cache: 'no-store', signal: AbortSignal.timeout(4000) })
        if (live) setStatus(res.status === 401 || res.ok ? 'online' : 'offline')
      } catch {
        if (live) setStatus('offline')
      }
    }
    void check()
    const timer = window.setInterval(check, 15_000)
    return () => { live = false; window.clearInterval(timer) }
  }, [])
  return status
}

/** The left half: the cooperative's logo, name and tagline. */
function BrandPanel() {
  const b = useBranding()
  return (
    <Box
      component="section" aria-label={`About ${b.name}`}
      sx={(t) => ({
        position: 'relative', overflow: 'hidden', display: { xs: 'none', md: 'flex' }, flexDirection: 'column', justifyContent: 'center', alignItems: 'center', p: { md: 6, lg: 8 },
        background: dark(t)
          ? 'linear-gradient(160deg, #15233d 0%, #101827 55%, #1d1630 100%)'
          : `linear-gradient(160deg, ${LIGHT.washBlue} 0%, #E9EDF4 55%, ${LIGHT.washLilac} 100%)`,
        borderRight: `1px solid ${dark(t) ? 'rgba(255,255,255,0.08)' : LIGHT.hairline}`,
      })}
    >
      {/* Soft colour that the panel seems to glow with. Decorative only. */}
      <Box aria-hidden sx={{ position: 'absolute', inset: 0, pointerEvents: 'none' }}>
        <Box sx={{ position: 'absolute', width: 460, height: 460, top: -160, left: -140, borderRadius: '50%', filter: 'blur(100px)', opacity: 0.28, bgcolor: tileFill.indigo }} />
        <Box sx={{ position: 'absolute', width: 420, height: 420, bottom: -170, right: -120, borderRadius: '50%', filter: 'blur(100px)', opacity: 0.2, bgcolor: tileFill.teal }} />
      </Box>
      <Box sx={{ position: 'relative', animation: 'signInRise 520ms cubic-bezier(.2,.8,.2,1) both', '@media (prefers-reduced-motion: reduce)': { animation: 'none' } }}>
        <BrandBlock name={b.name} tagline={b.tagline} logo={b.logo} custom={b.custom} />
      </Box>
      {/* At the foot of the left side, on the same line as the server status on the right, and aligned to the left edge. */}
      <Box sx={{ position: 'absolute', left: EDGE, right: EDGE, bottom: EDGE }}><SystemLine custom={b.custom} size={13} /></Box>
    </Box>
  )
}

/** Whether the server answers and which database it works on, shown at the foot of the right side where the person is looking, with the way to change the connection. */
function ServerStatus({ status, onConnection }: { status: 'checking' | 'online' | 'offline'; onConnection: () => void }) {
  const server = activeServer()
  const dbs = useQuery({ queryKey: ['public-connections', server.id, server.url], queryFn: () => getPublicConnections(), retry: false, enabled: status === 'online', staleTime: 15_000 })
  const active = dbs.data?.connections.find((c) => c.active)
  return (
    <Box sx={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 1, pointerEvents: 'auto' }}>
      <Box role="status" sx={{ minWidth: 0 }}>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.75 }}>
          <Box aria-hidden sx={{ width: 8, height: 8, borderRadius: '50%', flexShrink: 0, bgcolor: status === 'online' ? 'success.main' : status === 'offline' ? 'error.main' : 'text.disabled' }} />
          <Typography variant="body2" color="text.secondary" noWrap>{status === 'online' ? 'Server online' : status === 'offline' ? 'Server not reachable' : 'Checking the server…'}</Typography>
        </Box>
        <Typography variant="body2" sx={{ pl: 2 }} color="text.secondary" noWrap>{server.name}{active ? ` · Database: ${active.name}${active.branch ? ` · ${active.branch}` : ''}` : ''}</Typography>
      </Box>
      <Button size="small" color="inherit" startIcon={<FiDatabase />} onClick={onConnection} sx={{ flexShrink: 0, fontSize: 12 }}>Connection</Button>
    </Box>
  )
}

/** The sign-in screen: the cooperative and the system on the left, the form on the right. On a narrow window only the form is shown, under a small header. */
export default function SignInPage({ onSignedIn }: { onSignedIn: (me: Me) => void }) {
  const status = useServerStatus()
  const b = useBranding()
  const [username, setUsername] = useState(remembered)
  const [password, setPassword] = useState('')
  const [reveal, setReveal] = useState(false)
  const [capsLock, setCapsLock] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [connectionOpen, setConnectionOpen] = useState(false)
  // why the last sign-in ended without the person asking (an administrator switched the account off, reset the password ...), shown once
  const [endedBecause] = useState(() => takeSignOutReason())

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (!username.trim() || !password) return
    setBusy(true)
    setError(null)
    try {
      const me = await signIn(username.trim(), password)
      try { localStorage.setItem(LAST_USER, username.trim()) } catch { /* storage unavailable */ }
      onSignedIn(me)
    } catch (err) {
      setError((err as Error).message)
      setPassword('')
    } finally {
      setBusy(false)
    }
  }
  const watchCaps = (e: KeyboardEvent) => setCapsLock(e.getModifierState('CapsLock'))

  return (
    <Box sx={(t) => ({ minHeight: '100dvh', position: 'relative', display: 'grid', gridTemplateColumns: { xs: '1fr', md: 'minmax(0, 1.15fr) minmax(0, 1fr)' }, background: t.palette.background.default })}>
      {/* No native title bar, so the window can still be moved and closed from here. */}
      <Box data-tauri-drag-region="" sx={{ position: 'absolute', top: 0, left: 0, right: 0, height: 44, zIndex: 2, display: 'flex', justifyContent: 'flex-end', alignItems: 'center' }}>
        <WindowControls />
      </Box>

      <BrandPanel />

      <Box component="main" sx={(t) => ({ position: 'relative', display: 'grid', gridTemplateRows: '1fr', justifyItems: 'center', alignItems: 'center', p: { xs: 2, md: 6 }, pt: { xs: 8, md: 6 }, background: dark(t) ? 'radial-gradient(900px 600px at 100% 100%, rgba(94,92,230,0.14) 0%, transparent 60%)' : `radial-gradient(900px 600px at 100% 100%, ${LIGHT.washLilac} 0%, transparent 60%)` })}>
        <Box component="form" onSubmit={submit} noValidate sx={{ width: '100%', maxWidth: 400, animation: 'signInRise 460ms cubic-bezier(.2,.8,.2,1) both', '@media (prefers-reduced-motion: reduce)': { animation: 'none' } }}>
          {/* Shown instead of the left half when the window is narrow. */}
          <Box sx={{ display: { xs: 'flex', md: 'none' }, alignItems: 'center', gap: 1.25, mb: 3 }}>
            <Mark size={32} logo={b.logo} alt={b.name} />
            <Box>
              <Typography variant="h5" component="p" sx={{ lineHeight: 1.1 }}>{b.name}</Typography>
              {!b.custom && <Typography variant="caption" sx={{ letterSpacing: '0.08em', textTransform: 'uppercase', fontWeight: 600 }} color="text.secondary">{brand.coop}</Typography>}
            </Box>
          </Box>

          <Typography component="h2" sx={{ fontSize: 28, fontWeight: 700, letterSpacing: '-0.03em', lineHeight: 1.15, mb: 0.75 }}>Welcome back</Typography>
          <Typography variant="h6" component="p" sx={{ mb: 3.5 }} color="text.secondary">Sign in to {brand.product} with your own account.</Typography>

          {endedBecause === ACCOUNT_DISABLED_SIGNED_OUT ? <DisabledNotice text={endedBecause} /> : endedBecause && <Alert severity="info" sx={{ mb: 2 }}>{endedBecause}</Alert>}
          {status === 'offline' && (
            <Alert severity="warning" icon={<FiAlertTriangle />} sx={{ mb: 2 }}>The server cannot be reached right now. Check the connection, or ask an administrator.</Alert>
          )}

          <TextField
            label="Username" size="small" value={username} onChange={(e) => setUsername(e.target.value)} fullWidth autoFocus={!username}
            autoComplete="username" sx={{ mb: 2, '& .MuiOutlinedInput-root': { fontSize: 15 }, '& .MuiInputLabel-root': { fontSize: 15 } }}
          />
          <TextField
            label="Password" size="small" type={reveal ? 'text' : 'password'} value={password} onChange={(e) => setPassword(e.target.value)} onKeyUp={watchCaps} onKeyDown={watchCaps}
            fullWidth autoFocus={!!username} autoComplete="current-password"
            helperText={capsLock ? 'Caps Lock is on.' : undefined}
            slotProps={{
              input: {
                endAdornment: (
                  <InputAdornment position="end">
                    <IconButton size="small" edge="end" aria-label={reveal ? 'Hide typed characters' : 'Show typed characters'} aria-pressed={reveal} onClick={() => setReveal((v) => !v)} tabIndex={-1}>
                      {reveal ? <FiEyeOff size={18} /> : <FiEye size={18} />}
                    </IconButton>
                  </InputAdornment>
                ),
              },
              formHelperText: { sx: { color: 'warning.main', fontWeight: 600 } },
            }}
            sx={{ mb: 2.5, '& .MuiOutlinedInput-root': { fontSize: 15 }, '& .MuiInputLabel-root': { fontSize: 15 } }}
          />

          {error === ACCOUNT_DISABLED_SIGN_IN ? <DisabledNotice text={error} /> : error && <Alert severity="error" sx={{ mb: 2 }} role="alert">{error}</Alert>}

          <Button type="submit" variant="contained" fullWidth size="large" disabled={busy || !username.trim() || !password} sx={{ py: 1.4, fontSize: 15, mb: 2.5 }}>
            {busy ? <CircularProgress size={22} color="inherit" aria-label="Signing in" /> : 'Sign in'}
          </Button>

          <Typography variant="body2" sx={{ lineHeight: 1.5 }} color="text.secondary">
            No password yet, or yours expired? Ask an administrator for a temporary one. Everything you do here is recorded under your name.
          </Typography>
        </Box>
        {/* Left edge in line with the form, and right at the foot of the window. */}
        <Box sx={{ position: 'absolute', left: 0, right: 0, bottom: EDGE, px: { xs: 2, md: 6 }, display: 'flex', justifyContent: 'center', pointerEvents: 'none' }}>
          <Box sx={{ width: '100%', maxWidth: 400 }}><ServerStatus status={status} onConnection={() => setConnectionOpen(true)} /></Box>
        </Box>
      </Box>
      {connectionOpen && <ConnectionDialog onClose={() => setConnectionOpen(false)} />}
    </Box>
  )
}
