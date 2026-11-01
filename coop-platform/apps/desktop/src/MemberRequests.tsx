import { Alert, Box, Button, Dialog, DialogActions, DialogContent, DialogTitle, MenuItem, Stack, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography } from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { approveMemberImport, approveMemberRequest, listMemberImports, listMemberRequests, rejectMemberImport, withdrawMemberImport, type ImportBatch, rejectMemberRequest, requestClosure, requestReactivation, withdrawMemberRequest, type MemberRequest } from './api'
import { glass } from './glass'
import { useMe } from './session'
import { ReasonDialog } from './ui/dialogs'
import { Gated } from './ui/Gated'

const when = (at: string) => new Date(at.replace(' ', 'T')).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })

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

/** Ask to bring a dormant or closed member back on the same record. Accounts never formally closed are kept; missing ones are opened. */
export function ReactivateDialog({ branchCode, clientId, name, onClose, onDone }: { branchCode: number; clientId: number; name: string; onClose: () => void; onDone: (msg: string) => void }) {
  const qc = useQueryClient()
  const [clientType, setClientType] = useState('1')
  const [note, setNote] = useState('')
  const [error, setError] = useState<string | null>(null)
  const save = useMutation({
    mutationFn: () => requestReactivation({ branchCode, clientId, clientType: Number(clientType), note }),
    onSuccess: () => { void qc.invalidateQueries({ queryKey: ['member-requests'] }); onDone('Reactivation sent for approval'); onClose() },
    onError: (e) => setError((e as Error).message),
  })
  return (
    <Dialog open onClose={onClose} maxWidth="xs" fullWidth>
      <DialogTitle>Reactivate {name}</DialogTitle>
      <DialogContent>
        <Stack spacing={1.5} sx={{ pt: 1 }}>
          {error && <Alert severity="error">{error}</Alert>}
          <TextField select size="small" label="Membership" value={clientType} onChange={(e) => setClientType(e.target.value)}>
            <MenuItem value="1">Regular Individual</MenuItem><MenuItem value="2">Associate</MenuItem>
          </TextField>
          <TextField size="small" label="Reason" value={note} onChange={(e) => setNote(e.target.value)} />
          <Typography sx={{ fontSize: 12.5 }} color="text.secondary">The same member record comes back, with its history. A Membership Officer other than you approves; a member who left owing money (bad debts or offset) also needs an administrator. A deceased member cannot be reactivated. Check the cooperative's rule on paying share capital again before approving.</Typography>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" disabled={save.isPending || note.trim().length < 3} onClick={() => { setError(null); save.mutate() }}>Send for approval</Button>
      </DialogActions>
    </Dialog>
  )
}

/** Lists of members uploaded in Advanced mode, each approved or rejected as a whole. */
function ImportBatches() {
  const me = useMe()
  const qc = useQueryClient()
  const list = useQuery({ queryKey: ['member-imports', 'PENDING'], queryFn: () => listMemberImports('PENDING'), refetchInterval: 30_000 })
  const [rejecting, setRejecting] = useState<ImportBatch | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [result, setResult] = useState<string | null>(null)
  const refresh = () => { void qc.invalidateQueries({ queryKey: ['member-imports'] }); void qc.invalidateQueries({ queryKey: ['member-requests'] }); void qc.invalidateQueries({ queryKey: ['members'] }) }
  const act = useMutation({
    mutationFn: ({ b, kind }: { b: ImportBatch; kind: 'approve' | 'withdraw' }) => (kind === 'approve' ? approveMemberImport(b.id) : withdrawMemberImport(b.id)),
    onSuccess: (b, v) => {
      setError(null)
      if (v.kind === 'approve') setResult(`${b.fileName}: ${b.openedCount ?? 0} member${b.openedCount === 1 ? '' : 's'} opened${b.failedCount ? `, ${b.failedCount} could not be opened and are left as separate requests (${b.decisionNote ?? ''})` : ''}.`)
      refresh()
    },
    onError: (e) => setError((e as Error).message),
  })
  const items = list.data ?? []
  if (items.length === 0 && !result && !list.error) return null
  return (
    <Box sx={{ mt: 3 }}>
      <Typography component="h2" sx={{ fontSize: 16, fontWeight: 700, mb: 0.5 }}>Member lists (Advanced mode)</Typography>
      {error && <Alert severity="error" sx={{ mb: 1 }} onClose={() => setError(null)}>{error}</Alert>}
      {result && <Alert severity="success" sx={{ mb: 1 }} onClose={() => setResult(null)}>{result}</Alert>}
      {items.length > 0 && (
        <Box sx={(t) => ({ overflow: 'auto', ...glass(t) })}>
          <Table size="small" aria-label="Member lists waiting for approval">
            <TableHead><TableRow><TableCell>List</TableCell><TableCell align="right">Members</TableCell><TableCell>Uploaded by</TableCell><TableCell>When</TableCell><TableCell /></TableRow></TableHead>
            <TableBody>
              {items.map((b) => (
                <TableRow key={b.id} hover>
                  <TableCell sx={{ fontWeight: 600 }}>{b.fileName}</TableCell>
                  <TableCell align="right">{b.okRows}{b.totalRows !== b.okRows ? ` of ${b.totalRows}` : ''}</TableCell>
                  <TableCell>{b.uploadedBy}</TableCell><TableCell sx={{ whiteSpace: 'nowrap' }}>{when(b.uploadedAt)}</TableCell>
                  <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                    {me.canApprove && <>
                      <Gated allowed={b.uploadedBy.toLowerCase() !== me.username.toLowerCase()} reason="You uploaded this list. Another Membership Officer must approve it.">
                        <Button size="small" disabled={act.isPending} onClick={() => act.mutate({ b, kind: 'approve' })}>{act.isPending ? 'Opening…' : 'Approve all'}</Button>
                      </Gated>
                      <Gated allowed={b.uploadedBy.toLowerCase() !== me.username.toLowerCase()} reason="You uploaded this list. Another Membership Officer must decide it.">
                        <Button size="small" color="error" disabled={act.isPending} onClick={() => setRejecting(b)}>Reject</Button>
                      </Gated>
                    </>}
                    {b.uploadedBy.toLowerCase() === me.username.toLowerCase() && <Button size="small" disabled={act.isPending} onClick={() => act.mutate({ b, kind: 'withdraw' })}>Withdraw</Button>}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Box>
      )}
      {rejecting && <ReasonDialog title={`Reject ${rejecting.fileName}`} label="Reason" onRun={(n) => rejectMemberImport(rejecting.id, n).then(refresh)} onClose={() => setRejecting(null)} />}
    </Box>
  )
}

/** New-member, closing and reactivation requests waiting for a decision. Officers decide; the person who asked can withdraw. */
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
  if (items.length === 0 && !list.error) return <ImportBatches />
  return (
    <>
    <Box sx={{ mt: 3 }}>
      <Typography component="h2" sx={{ fontSize: 16, fontWeight: 700, mb: 0.5 }}>New members and closings</Typography>
      {error && <Alert severity="error" sx={{ mb: 1 }} onClose={() => setError(null)}>{error}</Alert>}
      <Box sx={(t) => ({ overflow: 'auto', ...glass(t) })}>
        <Table size="small" aria-label="Member requests waiting for approval">
          <TableHead><TableRow><TableCell>Request</TableCell><TableCell>Asked by</TableCell><TableCell>When</TableCell><TableCell /></TableRow></TableHead>
          <TableBody>
            {items.map((r) => (
              <TableRow key={r.id} hover>
                <TableCell><Typography component="span" sx={{ fontWeight: 600 }}>{r.kind === 'REGISTER' ? 'New member' : r.kind === 'REACTIVATE' ? 'Reactivate' : r.kind === 'BENEF' ? 'Beneficiary' : 'Close'}</Typography>{' '}{r.summary}</TableCell>
                <TableCell>{r.requestedBy}</TableCell><TableCell sx={{ whiteSpace: 'nowrap' }}>{when(r.requestedAt)}</TableCell>
                <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                  {me.canApprove && <>
                    <Gated allowed={r.requestedBy.toLowerCase() !== me.username.toLowerCase()} reason="You made this request. Another Membership Officer must decide it.">
                      <Button size="small" disabled={act.isPending} onClick={() => act.mutate({ r, kind: 'approve' })}>Approve</Button>
                    </Gated>
                    <Gated allowed={r.requestedBy.toLowerCase() !== me.username.toLowerCase()} reason="You made this request. Another Membership Officer must decide it.">
                      <Button size="small" color="error" onClick={() => setRejecting(r)}>Reject</Button>
                    </Gated>
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
    <ImportBatches />
    </>
  )
}
