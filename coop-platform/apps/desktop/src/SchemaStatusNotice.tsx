import { Alert, AlertTitle, Box, Button, Dialog, DialogActions, DialogContent, DialogTitle, Stack, TextField, Typography } from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { FiDatabase } from 'react-icons/fi'
import { applySchemaChanges, getSchemaStatus, type SchemaApplyResult, type SchemaStatus } from './api'
import { Hinted } from './appleUi'

const TITLES: Record<string, string> = {
  BEHIND: 'This database is missing database changes',
  NEWER: 'This database is newer than this server',
  NO_LOG: 'This database cannot be checked',
}

/**
 * Says when the connected database lacks database changes this server needs (or is ahead of it), so it is known before screens fail with
 * "Something went wrong on the server". Shown on Settings and on Database connection, to administrators. Says nothing while all is well,
 * unless `showOk` (the Database connection screen confirms it); nothing either when it cannot be asked (not an administrator, an older server).
 * With `canApply` (super administrators) a database that is behind gets "Apply them now", which asks for a database administrator's login.
 */
export function SchemaStatusNotice({ showOk = false, canApply = false }: { showOk?: boolean; canApply?: boolean }) {
  const { data } = useQuery({ queryKey: ['schema-status'], queryFn: getSchemaStatus, staleTime: 60_000, retry: false })
  const [applying, setApplying] = useState(false)
  if (!data) return null
  if (data.state === 'UP_TO_DATE' || data.state === 'NOT_CHECKED') {
    if (!showOk) return null
    return (
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.75, mb: 2, fontSize: 13 }} color={data.state === 'UP_TO_DATE' ? 'success.main' : 'text.secondary'}>
        <FiDatabase aria-hidden /> <Typography component="span" sx={{ fontSize: 13 }}>{data.message}</Typography>
      </Box>
    )
  }
  const offerApply = canApply && data.state === 'BEHIND'
  return (
    <>
      <Alert severity={data.state === 'BEHIND' ? 'error' : 'warning'} icon={<FiDatabase />} sx={{ mb: 2 }} role="alert"
        action={offerApply ? <Button color="inherit" size="small" onClick={() => setApplying(true)} sx={{ whiteSpace: 'nowrap' }}>Apply them now</Button> : undefined}>
        <AlertTitle sx={{ fontSize: 13, fontWeight: 600, mb: 0.25 }}>{TITLES[data.state]}</AlertTitle>
        {data.message}
        {data.missing.length > 0 && (
          <Box component="ul" sx={{ m: 0, mt: 0.75, pl: 2.5, fontSize: 12 }}>
            {data.missing.map((m) => <li key={m}>{m.slice(m.indexOf(' ') + 1)}</li>)}
          </Box>
        )}
      </Alert>
      {applying && <ApplyDialog status={data} onClose={() => setApplying(false)} />}
    </>
  )
}

export function ApplyDialog({ status, onClose }: { status: SchemaStatus; onClose: () => void }) {
  const qc = useQueryClient()
  const [user, setUser] = useState('coop_admin')
  const [password, setPassword] = useState('')
  const [reason, setReason] = useState('')
  const [failed, setFailed] = useState<string | null>(null)
  const run = useMutation({
    mutationFn: () => applySchemaChanges(user.trim(), password, reason.trim()),
    meta: {
      done: (data: unknown) => {
        const r = data as SchemaApplyResult
        return r.failedFile ? null : `Applied ${r.applied.length} database change${r.applied.length === 1 ? '' : 's'}.`
      },
    },
    onSuccess: (r) => {
      setPassword('')
      qc.setQueryData(['schema-status'], r.status)
      // what the screens may now do changed (new tables and permissions): read everything again
      void qc.invalidateQueries()
      if (r.failedFile) setFailed(r.message)
      else onClose()
    },
    onError: () => setPassword(''),
  })
  const files = status.missing.map((m) => m.slice(m.indexOf(' ') + 1))
  const ready = !!user.trim() && !!password && reason.trim().length >= 3 && !run.isPending

  return (
    <Dialog open onClose={run.isPending ? undefined : onClose} maxWidth="sm" fullWidth>
      <DialogTitle>Apply the missing database changes</DialogTitle>
      <DialogContent>
        <Stack spacing={1.5} sx={{ pt: 1 }}>
          <Typography sx={{ fontSize: 13 }}>
            {files.length === 1 ? 'This change' : `These ${files.length} changes`} will be applied to {status.database ?? 'this database'}, lowest number first, each
            on its own: one that fails is rolled back and stops the rest. Each is recorded in the audit log with your reason.
          </Typography>
          <Box component="ul" sx={{ m: 0, pl: 2.5, fontSize: 12 }}>{files.map((f) => <li key={f}>{f}</li>)}</Box>
          <Typography sx={{ fontSize: 12 }} color="text.secondary">
            The application&apos;s own database user may not make these changes, so give the database administrator&apos;s login. It is used for this once and is
            not saved, logged or shown again.
          </Typography>
          {failed && <Alert severity="error">{failed}</Alert>}
          {run.error && <Alert severity="error">{(run.error as Error).message}</Alert>}
          <TextField label="Database administrator" value={user} onChange={(e) => setUser(e.target.value)} required slotProps={{ htmlInput: { autoComplete: 'off', spellCheck: false } }} />
          <TextField label="Password" type="password" value={password} onChange={(e) => setPassword(e.target.value)} required autoFocus
            slotProps={{ htmlInput: { autoComplete: 'new-password' } }} />
          <Hinted tip="Kept in the audit log with each change."><TextField label="Reason" value={reason} onChange={(e) => setReason(e.target.value)} required /></Hinted>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={run.isPending}>{failed ? 'Close' : 'Cancel'}</Button>
        <Button variant="contained" disabled={!ready} onClick={() => { setFailed(null); run.mutate() }}>{run.isPending ? 'Applying…' : 'Apply changes'}</Button>
      </DialogActions>
    </Dialog>
  )
}
