import { Alert, Box, Button, Dialog, DialogActions, DialogContent, DialogTitle, MenuItem, Stack, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography } from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useRef, useState } from 'react'
import { FiPaperclip, FiX } from 'react-icons/fi'
import { getMembershipPolicy, approveMemberImport, approveMemberRequest, listMemberImports, listMemberRequests, rejectMemberImport, withdrawMemberImport, type ImportBatch, rejectMemberRequest, requestClosure, requestReactivation, withdrawMemberRequest, type MemberRequest, getDocumentSetup, uploadDocument } from './api'
import { getMemberDefaults } from './api/memberDefaults'
import { glass } from './glass'
import { useMe } from './session'
import { ReasonDialog, ConfirmButton } from './ui/dialogs'
import { Gated } from './ui/Gated'

const when = (at: string) => new Date(at.replace(' ', 'T')).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })

const MAX_PROOFS = 5
const REASONS: Record<string, string> = { '1': 'Voluntary', '2': 'Transferred', '3': 'Bad debts', '4': 'Offset', '5': 'Zero balance', '6': 'Deceased' }
const fileSize = (n: number) => (n < 1024 * 1024 ? `${Math.max(1, Math.round(n / 1024))} KB` : `${(n / 1024 / 1024).toFixed(1)} MB`)

/**
 * Ask to close a membership. Every account must already be at zero. Proof documents (a letter, a death certificate, a transfer slip) can be attached: they are kept with the member's
 * documents, readable by the Membership Officers who decide, and are sent once the request itself is saved.
 */
export function CloseDialog({ branchCode, clientId, name, onClose, onDone }: { branchCode: number; clientId: number; name: string; onClose: () => void; onDone: (msg: string) => void }) {
  const qc = useQueryClient()
  const setup = useQuery({ queryKey: ['doc-setup'], queryFn: getDocumentSetup })
  const input = useRef<HTMLInputElement>(null)
  const [reason, setReason] = useState('1')
  const [died, setDied] = useState('')
  const [note, setNote] = useState('')
  const [proofs, setProofs] = useState<File[]>([])
  const [error, setError] = useState<string | null>(null)
  const maxMb = setup.data?.maxMegabytes ?? 10
  const extensions = setup.data?.extensions ?? []

  const add = (picked: FileList | null) => {
    if (!picked) return
    const next = [...proofs]
    const problems: string[] = []
    for (const f of Array.from(picked)) {
      const ext = f.name.includes('.') ? f.name.split('.').pop()!.toLowerCase() : ''
      if (next.length >= MAX_PROOFS) problems.push(`Only ${MAX_PROOFS} documents can be attached.`)
      else if (extensions.length > 0 && !extensions.includes(ext)) problems.push(`${f.name}: this kind of file cannot be kept.`)
      else if (f.size > maxMb * 1024 * 1024) problems.push(`${f.name}: larger than ${maxMb} MB.`)
      else if (f.size === 0) problems.push(`${f.name}: the file is empty.`)
      else if (next.some((x) => x.name === f.name && x.size === f.size)) problems.push(`${f.name} is already in the list.`)
      else next.push(f)
    }
    setProofs(next)
    setError(problems.length > 0 ? [...new Set(problems)].join(' ') : null)
  }

  const save = useMutation({
    mutationFn: async () => {
      await requestClosure({ branchCode, clientId, reason: Number(reason), dateDeceased: reason === '6' ? died || null : null, note: note || null })
      // the request is saved; now its proofs. One that fails does not undo the request, so say which did not go.
      const failed: string[] = []
      for (const f of proofs) {
        try {
          await uploadDocument(f, { entityType: 'MEMBER', entityId: String(clientId), title: `Closure proof: ${f.name}`.slice(0, 120), description: `Proof for closing the membership (${REASONS[reason]})`, classification: 'INTERNAL' })
        } catch (e) {
          failed.push(`${f.name} (${(e as Error).message})`)
        }
      }
      return failed
    },
    onSuccess: (failed) => {
      void qc.invalidateQueries({ queryKey: ['member-requests'] })
      void qc.invalidateQueries({ queryKey: ['documents'] })
      const sent = proofs.length > 0 ? `Closing sent for approval with ${proofs.length} document${proofs.length === 1 ? '' : 's'}` : 'Closing sent for approval'
      onDone(failed.length === 0 ? sent : `Closing sent for approval, but not attached: ${failed.join('; ')}. Attach them from the member's Documents tab.`)
      onClose()
    },
    onError: (e) => setError((e as Error).message),
  })
  return (
    <Dialog open onClose={save.isPending ? undefined : onClose} maxWidth="xs" fullWidth>
      <DialogTitle>Close membership of {name}</DialogTitle>
      <DialogContent>
        <Stack spacing={1.5} sx={{ pt: 1 }}>
          {error && <Alert severity="error">{error}</Alert>}
          <TextField select size="small" label="Reason" value={reason} onChange={(e) => setReason(e.target.value)}>
            {Object.entries(REASONS).map(([k, v]) => <MenuItem key={k} value={k}>{v}</MenuItem>)}
          </TextField>
          {reason === '6' && <TextField size="small" type="date" label="Date of death" value={died} onChange={(e) => setDied(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} />}
          <TextField size="small" label="Note (optional)" value={note} onChange={(e) => setNote(e.target.value)} />
          <Box>
            <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
              <Button size="small" variant="outlined" startIcon={<FiPaperclip />} disabled={proofs.length >= MAX_PROOFS || save.isPending} onClick={() => input.current?.click()}>Attach proof</Button>
              <Typography sx={{ fontSize: 12 }} color="text.secondary">Optional. PDF, PNG, JPEG, Word, Excel, CSV or text, up to {maxMb} MB each, at most {MAX_PROOFS}.</Typography>
            </Stack>
            <input ref={input} type="file" hidden multiple aria-label="Proof documents" accept={extensions.map((e) => `.${e}`).join(',')} onChange={(e) => { add(e.target.files); e.target.value = '' }} />
            {proofs.length > 0 && (
              <Stack component="ul" spacing={0.5} sx={{ listStyle: 'none', p: 0, mt: 1, mb: 0 }} aria-label="Documents to attach">
                {proofs.map((f) => (
                  <Box component="li" key={`${f.name}-${f.size}`} sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                    <Typography noWrap sx={{ fontSize: 13, flex: 1, minWidth: 0 }}>{f.name}</Typography>
                    <Typography sx={{ fontSize: 12 }} color="text.secondary">{fileSize(f.size)}</Typography>
                    <Button size="small" color="inherit" aria-label={`Remove ${f.name}`} disabled={save.isPending} sx={{ minWidth: 0, p: 0.5 }} onClick={() => setProofs(proofs.filter((x) => x !== f))}><FiX /></Button>
                  </Box>
                ))}
              </Stack>
            )}
          </Box>
          <Typography sx={{ fontSize: 12 }} color="text.secondary">The member is not deleted: the record stays, marked Closed. Share capital, savings and loans must be at zero first, and a Membership Officer other than you approves.</Typography>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={save.isPending}>Cancel</Button>
        <Button variant="contained" color="error" disabled={save.isPending} onClick={() => { setError(null); save.mutate() }}>{save.isPending ? 'Sending…' : 'Send for approval'}</Button>
      </DialogActions>
    </Dialog>
  )
}

/** Ask to bring a dormant or closed member back on the same record. Accounts never formally closed are kept; missing ones are opened. */
export function ReactivateDialog({ branchCode, clientId, name, onClose, onDone }: { branchCode: number; clientId: number; name: string; onClose: () => void; onDone: (msg: string) => void }) {
  const qc = useQueryClient()
  const types = useQuery({ queryKey: ['member-defaults'], queryFn: getMemberDefaults })
  const [picked, setClientType] = useState<string | null>(null)
  // the member types this cooperative registers (Settings > Membership > Member defaults); the first one until another is chosen
  const clientType = picked ?? String(types.data?.types[0]?.code ?? '')
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
            {(types.data?.types ?? []).map((t) => <MenuItem key={t.code} value={String(t.code)}>{t.name}</MenuItem>)}
          </TextField>
          <TextField size="small" label="Reason" value={note} onChange={(e) => setNote(e.target.value)} />
          <Typography sx={{ fontSize: 12 }} color="text.secondary">The same member record comes back, with its history. A Membership Officer other than you approves; a member who left owing money (bad debts or offset) also needs an administrator. A deceased member cannot be reactivated. Check the cooperative's rule on paying share capital again before approving.</Typography>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" disabled={save.isPending || !clientType || note.trim().length < 3} onClick={() => { setError(null); save.mutate() }}>Send for approval</Button>
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
      <Typography component="h2" sx={{ fontSize: 15, fontWeight: 700, mb: 0.5 }}>Member lists (Advanced mode)</Typography>
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
                        <ConfirmButton size="small" disabled={act.isPending} onConfirm={() => act.mutate({ b, kind: 'approve' })} title={`Approve all ${b.okRows} members?`} confirmLabel="Approve all"
                          message={<>It opens {b.okRows} new members from {b.fileName}, each with their share capital and savings accounts. They are not removed afterwards; a member opened by mistake has to be closed.</>}>
                          {act.isPending ? 'Opening…' : 'Approve all'}
                        </ConfirmButton>
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
  // While a super administrator lets Membership Officers register members directly, an officer may approve their own waiting registration
  // (including one sent before that was switched on). The server checks the same.
  const policy = useQuery({ queryKey: ['membership-policy'], queryFn: getMembershipPolicy, staleTime: 60_000, retry: false })
  const ownRegistrations = !!policy.data?.officerRegistersDirectly && me.roles.some((x) => x.replace('ROLE_', '') === 'MEMBERSHIP_OFFICER')
  const [rejecting, setRejecting] = useState<MemberRequest | null>(null)
  const [error, setError] = useState<string | null>(null)
  const refresh = () => { void qc.invalidateQueries({ queryKey: ['member-requests'] }) }
  const act = useMutation({ meta: { done: (_, v) => ((v as { kind: string }).kind === 'approve' ? 'Request approved.' : 'Request withdrawn.') },
    mutationFn: ({ r, kind }: { r: MemberRequest; kind: 'approve' | 'withdraw' }) => (kind === 'approve' ? approveMemberRequest(r.id) : withdrawMemberRequest(r.id)),
    onSuccess: () => { setError(null); refresh() },
    onError: (e) => setError((e as Error).message),
  })
  const items = list.data ?? []
  if (items.length === 0 && !list.error) return <ImportBatches />
  return (
    <>
    <Box sx={{ mt: 3 }}>
      <Typography component="h2" sx={{ fontSize: 15, fontWeight: 700, mb: 0.5 }}>New members and closings</Typography>
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
                    <Gated allowed={r.requestedBy.toLowerCase() !== me.username.toLowerCase() || (r.kind === 'REGISTER' && ownRegistrations)}
                      reason={r.kind === 'REGISTER' ? 'You made this request. Another Membership Officer must decide it, unless a super administrator lets officers register members directly (Membership policy).' : 'You made this request. Another Membership Officer must decide it.'}>
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
