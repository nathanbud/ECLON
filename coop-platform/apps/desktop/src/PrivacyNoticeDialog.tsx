import { Alert, Button, Dialog, DialogActions, DialogContent, DialogTitle, MenuItem, Stack, TextField, Typography } from '@mui/material'
import { useMutation, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { getMemberPrivacy, giveMemberNotice } from './api'

/** The privacy notice in force, whether this member has been given it, and (for staff who may edit members) a record that it was given. */
export default function PrivacyNoticeDialog({ branchCode, clientId, name, canRecord, onClose, onDone }: {
  branchCode: number; clientId: number; name: string; canRecord: boolean; onClose: () => void; onDone: (msg: string) => void
}) {
  const state = useQuery({ queryKey: ['member-privacy', branchCode, clientId], queryFn: () => getMemberPrivacy(branchCode, clientId) })
  const [how, setHow] = useState('VERBAL')
  const give = useMutation({ mutationFn: () => giveMemberNotice(branchCode, clientId, how), onSuccess: () => { void state.refetch(); onDone('Privacy notice recorded.') } })
  const s = state.data
  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>Privacy notice · {name}</DialogTitle>
      <DialogContent>
        <Stack spacing={1.5} sx={{ pt: 1 }}>
          {give.error && <Alert severity="error">{(give.error as Error).message}</Alert>}
          {s && !s.active && <Alert severity="info">No privacy notice is in force yet. A data protection officer sets one up in Settings, Data privacy.</Alert>}
          {s?.active && (
            <>
              <Typography sx={{ fontWeight: 600 }}>{s.active.title} (version {s.active.version})</Typography>
              <Typography sx={{ whiteSpace: 'pre-wrap', fontSize: 14 }}>{s.active.body}</Typography>
              {s.upToDate
                ? <Alert severity="success">Given to this member{s.latest ? ` (${s.latest.how.toLowerCase()}, recorded by ${s.latest.by})` : ''}.</Alert>
                : <Alert severity="warning">{s.latest ? `This member was given version ${s.latest.version}; the current version has not been given.` : 'This member has not been given the notice.'}</Alert>}
              {!s.upToDate && canRecord && (
                <TextField select size="small" label="How it was given" value={how} onChange={(e) => setHow(e.target.value)}>
                  <MenuItem value="VERBAL">Explained verbally</MenuItem><MenuItem value="WRITTEN">Given in writing</MenuItem><MenuItem value="ELECTRONIC">Sent electronically</MenuItem>
                </TextField>
              )}
            </>
          )}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Close</Button>
        {s?.active && !s.upToDate && canRecord && <Button variant="contained" disabled={give.isPending} onClick={() => give.mutate()}>Record as given</Button>}
      </DialogActions>
    </Dialog>
  )
}
