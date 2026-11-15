import { Alert, Box, Button, CircularProgress, IconButton, InputAdornment, TextField, Typography } from '@mui/material'
import { useState, type FormEvent } from 'react'
import { FiCheck, FiEye, FiEyeOff } from 'react-icons/fi'
import { changeOwnPassword, currentPassword, signIn, signOut, type Me } from './api'
import { appBackground, glass } from './glass'
import { Logo, WindowControls } from './TopBar'

const MIN = 8

function Rule({ ok, children }: { ok: boolean; children: string }) {
  return (
    <Box component="li" sx={{ display: 'flex', alignItems: 'center', gap: 1, fontSize: 13, color: ok ? 'success.main' : 'text.secondary', transition: 'color 200ms' }}>
      <Box aria-hidden sx={{ width: 16, height: 16, display: 'grid', placeItems: 'center', borderRadius: '50%', border: '1.5px solid currentColor', fontSize: 11, opacity: ok ? 1 : 0.5 }}>{ok && <FiCheck />}</Box>
      {children}
    </Box>
  )
}

/**
 * Shown instead of the app to a person who signed in with a temporary password. They choose their own; until they do nothing else opens.
 * The temporary password is the one they just signed in with, so it is not asked for again.
 */
export default function ChangePasswordScreen({ me, onDone }: { me: Me; onDone: (me: Me) => void }) {
  const temporary = currentPassword() ?? ''
  const [next, setNext] = useState('')
  const [again, setAgain] = useState('')
  const [show, setShow] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const longEnough = next.length >= MIN
  const mixed = /[A-Za-z]/.test(next) && /[0-9]/.test(next)
  const different = next.length > 0 && next !== temporary && next.toLowerCase() !== me.username.toLowerCase()
  const matches = again.length > 0 && again === next
  const ready = longEnough && mixed && different && matches

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (!ready) return
    setBusy(true)
    setError(null)
    try {
      await changeOwnPassword(temporary, next)
      onDone(await signIn(me.username, next)) // the saved sign-in now carries the new password
    } catch (err) {
      setError((err as Error).message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Box sx={(t) => ({ minHeight: '100dvh', display: 'flex', flexDirection: 'column', background: appBackground(t) })}>
      <Box data-tauri-drag-region="" sx={{ height: 44, display: 'flex', justifyContent: 'flex-end', alignItems: 'center' }}>
        <WindowControls />
      </Box>
      <Box sx={{ flex: 1, display: 'grid', placeItems: 'center', p: 2, pb: 10 }}>
        <Box component="form" onSubmit={submit} sx={(t) => ({ width: '100%', maxWidth: 400, p: 3.5, ...glass(t, { elevated: true }) })}>
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.25, mb: 1 }}>
            <Box sx={{ display: 'flex', '& svg': { width: 28, height: 28 } }}><Logo /></Box>
            <Typography component="h1" sx={{ fontSize: 20, fontWeight: 700, letterSpacing: '-0.02em' }}>Choose your password</Typography>
          </Box>
          <Typography sx={{ fontSize: 13, mb: 2.5 }} color="text.secondary">
            Welcome, {me.name}. You signed in with a temporary password. Choose one only you know to continue.
          </Typography>
          <TextField
            label="New password" type={show ? 'text' : 'password'} value={next} onChange={(e) => setNext(e.target.value)} fullWidth autoFocus
            autoComplete="new-password" sx={{ mb: 2 }}
            slotProps={{ input: { endAdornment: (
              <InputAdornment position="end">
                <IconButton size="small" edge="end" aria-label={show ? 'Hide password' : 'Show password'} onClick={() => setShow((s) => !s)}>{show ? <FiEyeOff /> : <FiEye />}</IconButton>
              </InputAdornment>
            ) } }}
          />
          <TextField label="Type it again" type={show ? 'text' : 'password'} value={again} onChange={(e) => setAgain(e.target.value)} fullWidth autoComplete="new-password" sx={{ mb: 2 }} />
          <Box component="ul" aria-label="Password requirements" sx={{ listStyle: 'none', m: 0, mb: 2.5, p: 0, display: 'grid', gap: 0.75 }}>
            <Rule ok={longEnough}>{`At least ${MIN} characters`}</Rule>
            <Rule ok={mixed}>Letters and numbers</Rule>
            <Rule ok={different}>Not your username or the temporary password</Rule>
            <Rule ok={matches}>Both entries match</Rule>
          </Box>
          {error && <Alert severity="error" sx={{ mb: 2 }} role="alert">{error}</Alert>}
          <Button type="submit" variant="contained" fullWidth disabled={busy || !ready} sx={{ py: 1 }}>
            {busy ? <CircularProgress size={20} /> : 'Continue'}
          </Button>
          <Button color="inherit" fullWidth onClick={() => signOut()} sx={{ mt: 1, color: 'text.secondary' }}>Sign out</Button>
        </Box>
      </Box>
    </Box>
  )
}
