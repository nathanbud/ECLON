import { Alert, Box, Button, Dialog, DialogActions, DialogContent, DialogTitle, MenuItem, Skeleton, Stack, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography } from '@mui/material'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { FiUsers } from 'react-icons/fi'
import { getBeneficiaries, listMemberRequests, requestBeneficiary, withdrawMemberRequest, type Beneficiary, type Lookups, type MemberRequest } from '../api'
import { type Id } from '../clients'
import { useMe } from '../session'
import { ReasonDialog } from '../ui/dialogs'
import { EmptyState, TableCard } from './fields'
import { fmtDate } from './format'

const TYPES: [number, string][] = [[1, 'Principal'], [2, 'Secondary']]

/** The form to add a beneficiary or change one. Nothing is saved here: it sends a request that a Membership Officer other than the sender decides. */
function BeneficiaryDialog({ id, lk, existing, onClose, onSent }: { id: Id; lk?: Lookups; existing?: Beneficiary; onClose: () => void; onSent: (msg: string) => void }) {
  const [name, setName] = useState(existing?.name ?? '')
  const [relation, setRelation] = useState(existing?.relationCode == null ? '' : String(existing.relationCode))
  const [born, setBorn] = useState(existing?.dateOfBirth ?? '')
  const [job, setJob] = useState(existing?.occupation ?? '')
  const [type, setType] = useState(String(existing && existing.typeCode !== 99 ? existing.typeCode : 1))
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const relations = Object.entries(lk?.relation ?? {}).sort((a, b) => a[1].localeCompare(b[1]))
  const valid = name.trim().length >= 2 && relation !== ''
  const send = () => {
    setBusy(true)
    requestBeneficiary({
      branchCode: id.branchCode, clientId: id.clientId, action: existing ? 'EDIT' : 'ADD', benefId: existing?.id ?? null, name: name.trim(), relation: Number(relation),
      dateOfBirth: born || null, occupation: job.trim() || null, type: Number(type), note: null,
    }).then((r) => { onSent(`Sent for approval as request #${r.id}. A Membership Officer other than you decides it.`); onClose() }, (e: Error) => { setError(e.message); setBusy(false) })
  }
  return (
    <Dialog open onClose={busy ? undefined : onClose} maxWidth="xs" fullWidth>
      <DialogTitle>{existing ? 'Change beneficiary' : 'Add beneficiary'}</DialogTitle>
      <DialogContent>
        <Stack spacing={1.5} sx={{ pt: 1 }}>
          {error && <Alert severity="error">{error}</Alert>}
          <TextField autoFocus required label="Name" value={name} onChange={(e) => setName(e.target.value)} slotProps={{ htmlInput: { maxLength: 50 } }} />
          <TextField select required label="Relationship to the member" value={relation} onChange={(e) => setRelation(e.target.value)}>
            {relations.map(([code, label]) => <MenuItem key={code} value={code}>{label}</MenuItem>)}
          </TextField>
          <TextField type="date" label="Date of birth" value={born} onChange={(e) => setBorn(e.target.value)} slotProps={{ inputLabel: { shrink: true }, htmlInput: { max: new Date().toLocaleDateString('en-CA') } }} />
          <TextField label="Occupation" value={job} onChange={(e) => setJob(e.target.value)} slotProps={{ htmlInput: { maxLength: 35 } }} />
          <TextField select label="Type" value={type} onChange={(e) => setType(e.target.value)}>
            {TYPES.map(([code, label]) => <MenuItem key={code} value={String(code)}>{label}</MenuItem>)}
          </TextField>
          <Typography sx={{ fontSize: 12 }} color="text.secondary">The change takes effect only when a Membership Officer other than you approves it.</Typography>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={busy}>Cancel</Button>
        <Button variant="contained" disabled={!valid || busy} onClick={send}>Send for approval</Button>
      </DialogActions>
    </Dialog>
  )
}

/** The people the member named to receive their benefits, principal beneficiaries first. A person with edit permission asks to add, change or remove one; a Membership Officer approves. */
export function BeneficiariesTab({ id, lk }: { id: Id; lk?: Lookups }) {
  const me = useMe()
  const qc = useQueryClient()
  const [form, setForm] = useState<{ existing?: Beneficiary } | null>(null)
  const [removing, setRemoving] = useState<Beneficiary | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const { data, error: loadError, isLoading } = useQuery({ queryKey: ['member-beneficiaries', id], queryFn: () => getBeneficiaries(id.branchCode, id.clientId), staleTime: 60_000 })
  const waiting = useQuery({ queryKey: ['member-requests', 'PENDING'], queryFn: () => listMemberRequests('PENDING'), enabled: me.canEdit || me.canApprove, retry: false })
  const mine: MemberRequest[] = (waiting.data ?? []).filter((r) => r.kind === 'BENEF' && r.clientId === id.clientId && r.branchCode === id.branchCode)
  const refresh = () => { void qc.invalidateQueries({ queryKey: ['member-requests'] }); void qc.invalidateQueries({ queryKey: ['pending-count'] }) }
  const pendingFor = (b: Beneficiary) => mine.some((r) => r.payload.benefId === String(b.id))
  const withdraw = (r: MemberRequest) => withdrawMemberRequest(r.id).then(() => { setError(null); refresh() }, (e: Error) => setError(e.message))

  if (loadError) return <Alert severity="error">{(loadError as Error).message}</Alert>
  if (isLoading) return <Skeleton variant="rounded" height={140} />
  const rows = data ?? []
  return (
    <>
      {notice && <Alert severity="success" sx={{ mb: 1 }} onClose={() => setNotice(null)}>{notice}</Alert>}
      {error && <Alert severity="error" sx={{ mb: 1 }} onClose={() => setError(null)}>{error}</Alert>}
      {me.canEdit && <Box sx={{ display: 'flex', justifyContent: 'flex-end', mb: 1 }}><Button size="small" variant="contained" onClick={() => setForm({})}>Add beneficiary</Button></Box>}
      {rows.length === 0 ? (
        <EmptyState icon={<FiUsers />} title="No beneficiaries" hint="No beneficiary is recorded for this client." />
      ) : (
        <TableCard>
          <Table size="small" aria-label="Beneficiaries">
            <TableHead>
              <TableRow><TableCell>Name</TableCell><TableCell>Relationship</TableCell><TableCell>Date of birth</TableCell><TableCell>Occupation</TableCell><TableCell>Type</TableCell><TableCell>Added</TableCell>{me.canEdit && <TableCell />}</TableRow>
            </TableHead>
            <TableBody>
              {rows.map((b) => (
                <TableRow key={b.id} hover>
                  <TableCell>{b.name ?? '—'}</TableCell><TableCell>{b.relation ?? '—'}</TableCell><TableCell>{fmtDate(b.dateOfBirth) ?? '—'}</TableCell>
                  <TableCell>{b.occupation ?? '—'}</TableCell><TableCell>{b.type ?? '—'}</TableCell><TableCell>{fmtDate(b.added) ?? '—'}</TableCell>
                  {me.canEdit && (
                    <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                      {pendingFor(b)
                        ? <Typography component="span" sx={{ fontSize: 12 }} color="text.secondary">Change waiting</Typography>
                        : <><Button size="small" onClick={() => setForm({ existing: b })}>Change</Button><Button size="small" color="error" onClick={() => setRemoving(b)}>Remove</Button></>}
                    </TableCell>
                  )}
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableCard>
      )}
      {mine.length > 0 && (
        <Box sx={{ mt: 2 }}>
          <Typography sx={{ fontSize: 12, fontWeight: 700, letterSpacing: '0.06em', textTransform: 'uppercase', mb: 0.5 }} color="text.secondary">Waiting for approval</Typography>
          <Stack spacing={0.5}>
            {mine.map((r) => (
              <Box key={r.id} sx={{ display: 'flex', alignItems: 'center', gap: 1, fontSize: 13 }}>
                <Box sx={{ flex: 1 }}>{r.summary} <Typography component="span" sx={{ fontSize: 12 }} color="text.secondary">· asked by {r.requestedBy}</Typography></Box>
                {r.requestedBy.toLowerCase() === me.username.toLowerCase() && <Button size="small" onClick={() => void withdraw(r)}>Withdraw</Button>}
              </Box>
            ))}
          </Stack>
        </Box>
      )}
      {form && <BeneficiaryDialog id={id} lk={lk} existing={form.existing} onClose={() => setForm(null)} onSent={(msg) => { setNotice(msg); refresh() }} />}
      {removing && (
        <ReasonDialog
          title={`Remove ${removing.name ?? 'beneficiary'}?`} label="Reason for removing" onClose={() => setRemoving(null)}
          onRun={(note) => requestBeneficiary({ branchCode: id.branchCode, clientId: id.clientId, action: 'REMOVE', benefId: removing.id, name: null, relation: null, dateOfBirth: null, occupation: null, type: null, note })
            .then((r) => { setNotice(`Sent for approval as request #${r.id}. A Membership Officer other than you decides it.`); refresh() })}
        />
      )}
    </>
  )
}
