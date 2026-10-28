import { Alert, Box, Button, Dialog, DialogActions, DialogContent, DialogTitle, MenuItem, Stack, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography } from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { approveMemberRequest, listMemberRequests, registerMember, rejectMemberRequest, requestClosure, withdrawMemberRequest, type Lookups, type MemberRequest } from './api'
import { glass } from './glass'
import { useMe } from './session'
import { ReasonDialog } from './ProcurementPage'

const when = (at: string) => new Date(at.replace(' ', 'T')).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })
const options = (m?: Record<string, string>) => Object.entries(m ?? {}).map(([k, v]) => <MenuItem key={k} value={k}>{v}</MenuItem>)

/** Register a new member. The request waits for a Membership Officer; the member number is given when it is approved. */
export function RegisterDialog({ lk, onClose, onDone }: { lk?: Lookups; onClose: () => void; onDone: (msg: string) => void }) {
  const qc = useQueryClient()
  const [clientType, setClientType] = useState('1')
  const [f, setF] = useState<Record<string, string>>({ Gender: '', CivilStatus: '' })
  const [error, setError] = useState<string | null>(null)
  const set = (k: string) => (e: { target: { value: string } }) => setF({ ...f, [k]: e.target.value })
  const save = useMutation({
    mutationFn: () => registerMember({ branchCode: 1, clientType: Number(clientType), fields: f }),
    onSuccess: (r) => { void qc.invalidateQueries({ queryKey: ['member-requests'] }); onDone(`Registration sent for approval: ${r.summary}`); onClose() },
    onError: (e) => setError((e as Error).message),
  })
  const text = (k: string, label: string, extra: object = {}) => <TextField size="small" label={label} value={f[k] ?? ''} onChange={set(k)} {...extra} />
  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>Register a new member</DialogTitle>
      <DialogContent>
        <Stack spacing={1.5} sx={{ pt: 1 }}>
          {error && <Alert severity="error">{error}</Alert>}
          <TextField select size="small" label="Membership" value={clientType} onChange={(e) => setClientType(e.target.value)}>
            <MenuItem value="1">Regular Individual</MenuItem><MenuItem value="2">Associate</MenuItem>
          </TextField>
          <Stack direction="row" spacing={1.5}>{text('LName', 'Last name', { autoFocus: true, sx: { flex: 1 } })}{text('FName', 'First name', { sx: { flex: 1 } })}{text('MName', 'Middle name', { sx: { flex: 1 } })}</Stack>
          <Stack direction="row" spacing={1.5}>
            {text('DateOfBirth', 'Date of birth', { type: 'date', slotProps: { inputLabel: { shrink: true } }, sx: { flex: 1 } })}
            <TextField select size="small" label="Gender" value={f.Gender} onChange={set('Gender')} sx={{ flex: 1 }}>{options(lk?.gender)}</TextField>
            <TextField select size="small" label="Civil status" value={f.CivilStatus} onChange={set('CivilStatus')} sx={{ flex: 1 }}>{options(lk?.civilStatus)}</TextField>
          </Stack>
          <Stack direction="row" spacing={1.5}>{text('CellNum', 'Mobile', { sx: { flex: 1 } })}{text('EmailAddress', 'Email', { sx: { flex: 1 } })}</Stack>
          {text('ResAddStreet', 'Street')}
          <Stack direction="row" spacing={1.5}>{text('ResAddCity', 'City / Municipality', { sx: { flex: 1 } })}{text('ResAddProvince', 'Province', { sx: { flex: 1 } })}</Stack>
          <Typography sx={{ fontSize: 12.5 }} color="text.secondary">A Membership Officer other than you approves it. Approval opens the member with the usual savings, share capital and fund accounts. More details can be added from the profile afterwards.</Typography>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" disabled={save.isPending} onClick={() => { setError(null); save.mutate() }}>Send for approval</Button>
      </DialogActions>
    </Dialog>
  )
}

/** Ask to close a membership. Every account must already be at zero. */
export function CloseDialog({ branchCode, clientId, name, onClose, onDone }: { branchCode: number; clientId: number; name: string; onClose: () => void; onDone: (msg: string) => void }) {
  const qc = useQueryClient()
  const [reason, setReason] = useState('1')
  const [died, setDied] = useState('')
  const [note, setNote] = useState('')
  const [error, setError] = useState<string | null>(null)
  const save = useMutation({
    mutationFn: () => requestClosure({ branchCode, clientId, reason: Number(reason), dateDeceased: reason === '6' ? died || null : null, note: note || null }),
    onSuccess: () => { void qc.invalidateQueries({ queryKey: ['member-requests'] }); onDone('Closing sent for approval'); onClose() },
    onError: (e) => setError((e as Error).message),
  })
  return (
    <Dialog open onClose={onClose} maxWidth="xs" fullWidth>
      <DialogTitle>Close membership of {name}</DialogTitle>
      <DialogContent>
        <Stack spacing={1.5} sx={{ pt: 1 }}>
          {error && <Alert severity="error">{error}</Alert>}
          <TextField select size="small" label="Reason" value={reason} onChange={(e) => setReason(e.target.value)}>
            <MenuItem value="1">Voluntary</MenuItem><MenuItem value="2">Transferred</MenuItem><MenuItem value="3">Bad debts</MenuItem>
            <MenuItem value="4">Offset</MenuItem><MenuItem value="5">Zero balance</MenuItem><MenuItem value="6">Deceased</MenuItem>
          </TextField>
          {reason === '6' && <TextField size="small" type="date" label="Date of death" value={died} onChange={(e) => setDied(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} />}
          <TextField size="small" label="Note (optional)" value={note} onChange={(e) => setNote(e.target.value)} />
          <Typography sx={{ fontSize: 12.5 }} color="text.secondary">The member is not deleted: the record stays, marked Closed. Share capital, savings and loans must be at zero first, and a Membership Officer other than you approves.</Typography>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" color="error" disabled={save.isPending} onClick={() => { setError(null); save.mutate() }}>Send for approval</Button>
      </DialogActions>
    </Dialog>
  )
}

/** New-member and closing requests waiting for a decision. Officers decide; the person who asked can withdraw. */
export default function MemberRequestsPanel() {
  const me = useMe()
  const qc = useQueryClient()
  const list = useQuery({ queryKey: ['member-requests', 'PENDING'], queryFn: () => listMemberRequests('PENDING'), refetchInterval: 30_000 })
  const [rejecting, setRejecting] = useState<MemberRequest | null>(null)
  const [error, setError] = useState<string | null>(null)
  const refresh = () => { void qc.invalidateQueries({ queryKey: ['member-requests'] }) }
  const act = useMutation({
    mutationFn: ({ r, kind }: { r: MemberRequest; kind: 'approve' | 'withdraw' }) => (kind === 'approve' ? approveMemberRequest(r.id) : withdrawMemberRequest(r.id)),
    onSuccess: () => { setError(null); refresh() },
    onError: (e) => setError((e as Error).message),
  })
  const items = list.data ?? []
  if (items.length === 0 && !list.error) return null
  return (
    <Box sx={{ mt: 3 }}>
      <Typography component="h2" sx={{ fontSize: 16, fontWeight: 700, mb: 0.5 }}>New members and closings</Typography>
      {error && <Alert severity="error" sx={{ mb: 1 }} onClose={() => setError(null)}>{error}</Alert>}
      <Box sx={(t) => ({ overflow: 'auto', ...glass(t) })}>
        <Table size="small" aria-label="Member requests waiting for approval">
          <TableHead><TableRow><TableCell>Request</TableCell><TableCell>Asked by</TableCell><TableCell>When</TableCell><TableCell /></TableRow></TableHead>
          <TableBody>
            {items.map((r) => (
              <TableRow key={r.id} hover>
                <TableCell><Typography component="span" sx={{ fontWeight: 600 }}>{r.kind === 'REGISTER' ? 'New member' : 'Close'}</Typography>{' '}{r.summary}</TableCell>
                <TableCell>{r.requestedBy}</TableCell><TableCell sx={{ whiteSpace: 'nowrap' }}>{when(r.requestedAt)}</TableCell>
                <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                  {me.canApprove && r.requestedBy.toLowerCase() !== me.username.toLowerCase() && <>
                    <Button size="small" disabled={act.isPending} onClick={() => act.mutate({ r, kind: 'approve' })}>Approve</Button>
                    <Button size="small" color="error" onClick={() => setRejecting(r)}>Reject</Button>
                  </>}
                  {r.requestedBy.toLowerCase() === me.username.toLowerCase() && <Button size="small" onClick={() => act.mutate({ r, kind: 'withdraw' })}>Withdraw</Button>}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </Box>
      {rejecting && <ReasonDialog title={`Reject: ${rejecting.summary}`} label="Reason" onRun={(n) => rejectMemberRequest(rejecting.id, n).then(refresh)} onClose={() => setRejecting(null)} />}
    </Box>
  )
}
