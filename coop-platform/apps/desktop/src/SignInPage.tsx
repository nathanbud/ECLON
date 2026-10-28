import { Alert, Box, Button, CircularProgress, IconButton, InputAdornment, TextField, Typography, type Theme } from '@mui/material'
import { useEffect, useState, type FormEvent, type KeyboardEvent } from 'react'
import { FiAlertTriangle, FiBookOpen, FiEye, FiEyeOff, FiLock, FiPieChart, FiUsers } from 'react-icons/fi'
import { signIn, type Me } from './api'
import { brand } from './brand'
import { LIGHT } from './glass'
import { tileFill } from './theme'
import { Logo, WindowControls } from './TopBar'

const LAST_USER = 'coop.lastUser'
const remembered = () => {
  try { return localStorage.getItem(LAST_USER) ?? '' } catch { return '' }
}

const dark = (t: Theme) => t.palette.mode === 'dark'
const ICONS = [<FiUsers key="m" />, <FiPieChart key="s" />, <FiBookOpen key="t" />, <FiLock key="c" />]
const TINTS = [tileFill.orange, tileFill.teal, tileFill.indigo, tileFill.green]

/** Whether the server answers. An unsigned request to /api/me is refused with 401 when the server is up; anything else (a gateway error, no answer) means it is not reachable. */
function useServerStatus(): 'checking' | 'online' | 'offline' {
  const [status, setStatus] = useState<'checking' | 'online' | 'offline'>('checking')
  useEffect(() => {
    let live = true
    const check = async () => {
      try {
        const res = await fetch('/api/me', { cache: 'no-store', signal: AbortSignal.timeout(4000) })
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

/** The cooperative's mark: its own logo when one is set, otherwise the Coop Platform mark. */
function Mark({ size }: { size: number }) {
  if (brand.logoUrl) return <Box component="img" src={brand.logoUrl} alt={`${brand.coop} logo`} sx={{ width: size, height: size, objectFit: 'contain' }} />
  return <Box sx={{ display: 'flex', '& svg': { width: size, height: size } }}><Logo /></Box>
}

/** The left half: who this is for, what the system does, and whether the server is up. */
function BrandPanel({ status }: { status: 'checking' | 'online' | 'offline' }) {
  return (
    <Box
      component="section" aria-label={`About ${brand.product}`}
      sx={(t) => ({
        position: 'relative', overflow: 'hidden', display: { xs: 'none', md: 'flex' }, flexDirection: 'column', justifyContent: 'space-between', p: { md: 6, lg: 8 },
        background: dark(t)
          ? 'linear-gradient(160deg, #15233d 0%, #101827 55%, #1d1630 100%)'
          : `linear-gradient(160deg, ${LIGHT.washBlue} 0%, #E9EDF4 55%, ${LIGHT.washLilac} 100%)`,
        borderRight: `1px solid ${dark(t) ? 'rgba(255,255,255,0.08)' : LIGHT.hairline}`,
      })}
    >
      {/* Soft colour that the panel seems to glow with. Decorative only. */}
      <Box aria-hidden sx={{ position: 'absolute', inset: 0, pointerEvents: 'none' }}>
        <Box sx={{ position: 'absolute', width: 420, height: 420, top: -140, left: -120, borderRadius: '50%', filter: 'blur(90px)', opacity: 0.28, bgcolor: tileFill.indigo }} />
        <Box sx={{ position: 'absolute', width: 380, height: 380, bottom: -150, right: -100, borderRadius: '50%', filter: 'blur(100px)', opacity: 0.2, bgcolor: tileFill.teal }} />
      </Box>

      <Box sx={{ position: 'relative', display: 'flex', alignItems: 'center', gap: 1.5 }}>
        <Mark size={44} />
        <Box>
          <Typography component="h1" sx={{ fontSize: 20, fontWeight: 700, letterSpacing: '-0.02em', lineHeight: 1.15 }}>{brand.product}</Typography>
          <Typography sx={{ fontSize: 12.5, letterSpacing: '0.08em', textTransform: 'uppercase', fontWeight: 600 }} color="text.secondary">{brand.coop}</Typography>
        </Box>
      </Box>

      <Box sx={{ position: 'relative', maxWidth: 560, my: 4, animation: 'signInRise 520ms cubic-bezier(.2,.8,.2,1) both', '@media (prefers-reduced-motion: reduce)': { animation: 'none' } }}>
        <Typography component="p" sx={{ fontSize: { md: 38, lg: 48 }, fontWeight: 700, letterSpacing: '-0.035em', lineHeight: 1.08, mb: 2 }}>{brand.tagline}</Typography>
        <Typography sx={{ fontSize: 17, lineHeight: 1.5, mb: 4, maxWidth: 480 }} color="text.secondary">{brand.summary}</Typography>
        <Box component="ul" sx={{ listStyle: 'none', m: 0, p: 0, display: 'grid', gap: 2 }}>
          {brand.facts.map((f, i) => (
            <Box component="li" key={f.title} sx={{ display: 'flex', gap: 1.75, alignItems: 'flex-start' }}>
              <Box aria-hidden sx={{ width: 34, height: 34, borderRadius: '5px', display: 'grid', placeItems: 'center', flexShrink: 0, color: '#fff', bgcolor: TINTS[i % TINTS.length], '& svg': { width: 17, height: 17 } }}>{ICONS[i % ICONS.length]}</Box>
              <Box>
                <Typography sx={{ fontSize: 15, fontWeight: 600, lineHeight: 1.3 }}>{f.title}</Typography>
                <Typography sx={{ fontSize: 13.5, lineHeight: 1.45 }} color="text.secondary">{f.text}</Typography>
              </Box>
            </Box>
          ))}
        </Box>
      </Box>

      <Box sx={{ position: 'relative', display: 'flex', alignItems: 'center', gap: 1.5, flexWrap: 'wrap' }}>
        <Typography sx={{ fontSize: 12.5, flex: 1 }} color="text.secondary">{brand.system}</Typography>
        <Box role="status" sx={{ display: 'flex', alignItems: 'center', gap: 0.75 }}>
          <Box aria-hidden sx={{ width: 8, height: 8, borderRadius: '50%', bgcolor: status === 'online' ? 'success.main' : status === 'offline' ? 'error.main' : 'text.disabled' }} />
          <Typography sx={{ fontSize: 12.5 }} color="text.secondary">{status === 'online' ? 'Server online' : status === 'offline' ? 'Server not reachable' : 'Checking the server…'}</Typography>
        </Box>
      </Box>
    </Box>
  )
}

/** The sign-in screen: the cooperative and the system on the left, the form on the right. On a narrow window only the form is shown, under a small header. */
export default function SignInPage({ onSignedIn }: { onSignedIn: (me: Me) => void }) {
  const status = useServerStatus()
  const [username, setUsername] = useState(remembered)
  const [password, setPassword] = useState('')
  const [reveal, setReveal] = useState(false)
  const [capsLock, setCapsLock] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

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

      <BrandPanel status={status} />

      <Box component="main" sx={(t) => ({ display: 'grid', placeItems: 'center', p: { xs: 2, md: 6 }, pt: { xs: 8, md: 6 }, background: dark(t) ? 'radial-gradient(900px 600px at 100% 100%, rgba(94,92,230,0.14) 0%, transparent 60%)' : `radial-gradient(900px 600px at 100% 100%, ${LIGHT.washLilac} 0%, transparent 60%)` })}>
        <Box component="form" onSubmit={submit} noValidate sx={{ width: '100%', maxWidth: 400, animation: 'signInRise 460ms cubic-bezier(.2,.8,.2,1) both', '@media (prefers-reduced-motion: reduce)': { animation: 'none' } }}>
          {/* Shown instead of the left half when the window is narrow. */}
          <Box sx={{ display: { xs: 'flex', md: 'none' }, alignItems: 'center', gap: 1.25, mb: 3 }}>
            <Mark size={32} />
            <Box>
              <Typography component="p" sx={{ fontSize: 17, fontWeight: 700, letterSpacing: '-0.02em', lineHeight: 1.1 }}>{brand.product}</Typography>
              <Typography sx={{ fontSize: 11.5, letterSpacing: '0.08em', textTransform: 'uppercase', fontWeight: 600 }} color="text.secondary">{brand.coop}</Typography>
            </Box>
          </Box>

          <Typography component="h2" sx={{ fontSize: 30, fontWeight: 700, letterSpacing: '-0.03em', lineHeight: 1.15, mb: 0.75 }}>Welcome back</Typography>
          <Typography sx={{ fontSize: 15, mb: 3.5 }} color="text.secondary">Sign in to {brand.product} with your own account.</Typography>

          {status === 'offline' && (
            <Alert severity="warning" icon={<FiAlertTriangle />} sx={{ mb: 2 }}>The server cannot be reached right now. Check the connection, or ask an administrator.</Alert>
          )}

          <TextField
            label="Username" size="medium" value={username} onChange={(e) => setUsername(e.target.value)} fullWidth autoFocus={!username}
            autoComplete="username" sx={{ mb: 2, '& .MuiOutlinedInput-root': { fontSize: 16 }, '& .MuiInputLabel-root': { fontSize: 15 } }}
          />
          <TextField
            label="Password" size="medium" type={reveal ? 'text' : 'password'} value={password} onChange={(e) => setPassword(e.target.value)} onKeyUp={watchCaps} onKeyDown={watchCaps}
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
            sx={{ mb: 2.5, '& .MuiOutlinedInput-root': { fontSize: 16 }, '& .MuiInputLabel-root': { fontSize: 15 } }}
          />

          {error && <Alert severity="error" sx={{ mb: 2 }} role="alert">{error}</Alert>}

          <Button type="submit" variant="contained" fullWidth size="large" disabled={busy || !username.trim() || !password} sx={{ py: 1.4, fontSize: 16, mb: 2.5 }}>
            {busy ? <CircularProgress size={22} color="inherit" aria-label="Signing in" /> : 'Sign in'}
          </Button>

          <Typography sx={{ fontSize: 12.5, lineHeight: 1.5 }} color="text.secondary">
            No password yet, or yours expired? Ask an administrator for a temporary one. Everything you do here is recorded under your name.
          </Typography>
        </Box>
      </Box>
    </Box>
  )
}
