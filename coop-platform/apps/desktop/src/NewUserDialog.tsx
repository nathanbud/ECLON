import { Alert, Autocomplete, Box, Button, Dialog, DialogActions, DialogContent, DialogTitle, MenuItem, Stack, TextField, ToggleButton, ToggleButtonGroup, Typography } from '@mui/material'
import { useMutation } from '@tanstack/react-query'
import { useState } from 'react'
import { createUser, type AdminUser, type CreatedUser, type NewUser } from './api'
import OneTimePassword from './OneTimePassword'
import { useMe } from './session'
import { CHOICES, DEFAULT_HOURS } from './TemporaryPasswordDialog'
import { BranchSelect } from './ui/BranchSelect'
import { nameCase } from './ui/format'
import { notifyError } from './ui/notices'
import { Hinted } from './appleUi'

const USERNAME = /^[A-Za-z0-9][A-Za-z0-9._-]{2,29}$/
const LEVEL_WORDS: Record<NewUser['level'], string> = {
  USER: 'User: works with the rights given to them',
  ADMIN: 'Administrator: manages users and settings',
  SUPERADMIN: 'Super administrator: also manages administrators',
}

/**
 * Creates a user (Settings > Users > New user): username, name, branch and level, optionally starting from another user's permissions, and a first temporary password
 * shown once. Only a super administrator may create an administrator; the server checks again.
 */
export default function NewUserDialog({ open, users, onClose, onCreated }: { open: boolean; users: AdminUser[]; onClose: () => void; onCreated: (u: CreatedUser) => void }) {
  const me = useMe()
  const superAdmin = me.roles.includes('SUPERADMIN')
  const blank = { username: '', firstName: '', middleName: '', lastName: '', branchCode: me.branchCode || 1, level: 'USER' as NewUser['level'], copyFrom: null as AdminUser | null, validityHours: DEFAULT_HOURS }
  const [f, setF] = useState(blank)
  const [touched, setTouched] = useState<Record<string, boolean>>({})
  const create = useMutation({
    mutationFn: () => createUser({
      username: f.username.trim(), firstName: f.firstName.trim(), middleName: f.middleName.trim() || null, lastName: f.lastName.trim(),
      branchCode: f.branchCode, level: f.level, copyFrom: f.copyFrom?.username ?? null, validityHours: f.validityHours,
    }),
    onSuccess: (u) => onCreated(u),
    // A refusal (a name already used, the bootstrap sign-in's name, an administrator by someone who may not) pops up in the middle of the window.
    onError: notifyError,
  })
  const reset = () => { setF(blank); setTouched({}); create.reset() }
  const taken = users.some((u) => u.username.toLowerCase() === f.username.trim().toLowerCase())
  const usernameProblem = !f.username.trim() ? 'Give a username.' : !USERNAME.test(f.username.trim()) ? '3 to 30 letters, digits, dots, dashes or underscores, starting with a letter or digit.'
    : taken ? 'This username is already taken.' : null
  const ready = !usernameProblem && !!f.firstName.trim() && !!f.lastName.trim() && !create.isPending
  const show = (k: string, problem: string | null) => (touched[k] ? problem : null)
  const done = create.data

  return (
    <Dialog open={open} onClose={create.isPending ? undefined : onClose} fullWidth maxWidth="sm" aria-labelledby="new-user-title" slotProps={{ transition: { onExited: reset } }}>
      <DialogTitle id="new-user-title" sx={{ pb: 0.5 }}>{done ? 'User created' : 'New user'}</DialogTitle>
      {!done && (
        <form onSubmit={(e) => { e.preventDefault(); if (ready) create.mutate() }}>
          <DialogContent>
            <Typography sx={{ mb: 2 }} color="text.secondary">
              The person is added to the old system&apos;s users too. They sign in with a temporary password shown on the next step, and choose their own at first sign-in.
            </Typography>
            <Stack sx={{ gap: 1.75 }}>
              <Hinted tip="What they type to sign in."><TextField label="Username" required autoFocus value={f.username} onChange={(e) => setF({ ...f, username: e.target.value })}
                onBlur={() => setTouched({ ...touched, username: true })} error={!!show('username', usernameProblem)}
                helperText={show('username', usernameProblem)} slotProps={{ htmlInput: { maxLength: 30, autoComplete: 'off', spellCheck: false } }} /></Hinted>
              <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: '1fr 0.7fr 1fr' }, gap: 1.5 }}>
                <TextField label="First name" required value={f.firstName} onChange={(e) => setF({ ...f, firstName: e.target.value })} onBlur={() => setTouched({ ...touched, first: true })}
                  error={!!show('first', f.firstName.trim() ? null : 'Give the first name.')} helperText={show('first', f.firstName.trim() ? null : 'Give the first name.')} slotProps={{ htmlInput: { maxLength: 45 } }} />
                <TextField label="Middle name" value={f.middleName} onChange={(e) => setF({ ...f, middleName: e.target.value })} slotProps={{ htmlInput: { maxLength: 45 } }} />
                <TextField label="Last name" required value={f.lastName} onChange={(e) => setF({ ...f, lastName: e.target.value })} onBlur={() => setTouched({ ...touched, last: true })}
                  error={!!show('last', f.lastName.trim() ? null : 'Give the last name.')} helperText={show('last', f.lastName.trim() ? null : 'Give the last name.')} slotProps={{ htmlInput: { maxLength: 45 } }} />
              </Box>
              <Stack direction={{ xs: 'column', sm: 'row' }} sx={{ gap: 1.5 }}>
                <BranchSelect value={f.branchCode} onChange={(b) => setF({ ...f, branchCode: b })} sx={{ flex: 1 }} />
                <TextField select label="Level" value={f.level} onChange={(e) => setF({ ...f, level: e.target.value as NewUser['level'] })} sx={{ flex: 1 }}
                  helperText={superAdmin ? undefined : 'Only a super administrator can create an administrator.'}>
                  {(Object.keys(LEVEL_WORDS) as NewUser['level'][]).map((l) => <MenuItem key={l} value={l} disabled={l !== 'USER' && !superAdmin}>{LEVEL_WORDS[l]}</MenuItem>)}
                </TextField>
              </Stack>
              <Autocomplete
                options={users.filter((u) => u.enabled)} value={f.copyFrom} onChange={(_, v) => setF({ ...f, copyFrom: v })}
                getOptionLabel={(u) => `${u.username}${u.name ? ` (${nameCase(u.name)})` : ''}`} isOptionEqualToValue={(a, b) => a.username === b.username}
                renderInput={(p) => <TextField {...p} label="Copy permissions from (optional)"
                  helperText={f.copyFrom
                    ? `Starts with ${f.copyFrom.username}'s permissions and per-transaction rights${f.copyFrom.roles.length ? `, and the roles ${f.copyFrom.roles.join(', ')}` : ''}. Never their name, password or member number. Change anything afterwards in their account setup.`
                    : 'Without a copy the person starts with no permissions; set them afterwards in their account setup.'} />}
              />
              <Box>
                <Typography variant="body2" id="first-password-label" sx={{ fontWeight: 600, mb: 0.75 }} color="text.secondary">First password valid for</Typography>
                <ToggleButtonGroup exclusive fullWidth size="small" value={f.validityHours} onChange={(_, v) => v != null && setF({ ...f, validityHours: v })} aria-labelledby="first-password-label">
                  {CHOICES.map((c) => <ToggleButton key={c.hours} value={c.hours} sx={{ textTransform: 'none' }}>{c.label}</ToggleButton>)}
                </ToggleButtonGroup>
              </Box>
            </Stack>
          </DialogContent>
          <DialogActions sx={{ px: 3, pb: 2 }}>
            <Button onClick={onClose} disabled={create.isPending}>Cancel</Button>
            <Button type="submit" variant="contained" disabled={!ready}>{create.isPending ? 'Creating…' : 'Create user'}</Button>
          </DialogActions>
        </form>
      )}
      {done && (
        <>
          <DialogContent>
            <Typography sx={{ mb: 2 }} color="text.secondary">
              <strong>{done.username}</strong> was created as {done.level === 'USER' ? 'a user' : done.level === 'ADMIN' ? 'an administrator' : 'a super administrator'}
              {done.copiedFrom ? ` with ${done.copiedFrom}'s permissions (${done.transactionRights} transaction types${done.roles.length ? `; roles ${done.roles.join(', ')}` : ''})` : ', with no permissions yet'}.
              {' '}Give them this temporary password in person or by phone. It is shown once and cannot be looked up again.
            </Typography>
            <OneTimePassword password={done.password.password} />
            <Alert severity="info" icon={false} sx={{ mt: 2 }}>
              Works until <strong>{new Date(done.password.expiresAt).toLocaleString(undefined, { weekday: 'short', month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' })}</strong>.
              {' '}{done.username} will be asked to choose their own password at first sign-in.
            </Alert>
          </DialogContent>
          <DialogActions sx={{ px: 3, pb: 2 }}>
            <Button variant="contained" onClick={onClose}>Done</Button>
          </DialogActions>
        </>
      )}
    </Dialog>
  )
}
