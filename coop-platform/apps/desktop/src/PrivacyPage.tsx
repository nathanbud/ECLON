import {
  Alert, Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle, MenuItem, Stack, Tab, Table, TableBody, TableCell, TableHead, TableRow, Tabs, TextField, Typography,
} from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import {
  activateNotice, addPrivacyRequest, addToRegister, changeRegister, downloadMemberPackage, erasePrivacyBiometrics, getPrivacyReview, listExports, listPrivacyRequests, listRegister,
  startPrivacyRequest, stepPrivacyRequest, type RegisterKey, type RequestStep, type Row,
} from './api'
import { appBackground, glass } from './glass'
import { ReasonDialog } from './ui/dialogs'

interface Field { key: string; label: string; kind?: 'text' | 'long' | 'date' | 'datetime' | 'number' | 'bool'; options?: string[]; required?: boolean }
interface Register { key: RegisterKey; label: string; note: string; fields: Field[]; columns: string[] }

const RISK = ['LOW', 'MEDIUM', 'HIGH']
const REGISTERS: Register[] = [
  {
    key: 'incidents', label: 'Incidents', note: 'Each incident has a 72-hour reporting deadline counted from when it was detected. Confirm with the data protection officer whether and when the regulator and those affected must be told.',
    columns: ['ref', 'detected_at', 'severity', 'status', 'notify_by', 'notified_at', 'description'],
    fields: [
      { key: 'detected_at', label: 'Detected', kind: 'datetime', required: true }, { key: 'description', label: 'What happened', kind: 'long', required: true },
      { key: 'data_affected', label: 'Data affected' }, { key: 'people_affected', label: 'People affected', kind: 'number' }, { key: 'severity', label: 'Severity', options: RISK, required: true },
      { key: 'status', label: 'Status', options: ['OPEN', 'CONTAINED', 'RESOLVED'] }, { key: 'actions', label: 'Actions taken', kind: 'long' }, { key: 'notified_at', label: 'Regulator and those affected told', kind: 'datetime' },
    ],
  },
  {
    key: 'sharing', label: 'Data sharing', note: 'Who receives member data, for what purpose and on what basis.', columns: ['recipient', 'purpose', 'data_categories', 'started_on', 'ended_on'],
    fields: [
      { key: 'recipient', label: 'Who it is shared with', required: true }, { key: 'purpose', label: 'Purpose', required: true }, { key: 'data_categories', label: 'What data', required: true },
      { key: 'legal_basis', label: 'Legal basis' }, { key: 'agreement_ref', label: 'Agreement reference' }, { key: 'started_on', label: 'Started', kind: 'date', required: true }, { key: 'ended_on', label: 'Ended', kind: 'date' },
    ],
  },
  {
    key: 'dpia', label: 'Impact assessments', note: 'Privacy impact assessments and where each document is kept.', columns: ['ref', 'title', 'risk_level', 'status', 'conducted_on', 'review_due'],
    fields: [
      { key: 'title', label: 'Title', required: true }, { key: 'scope', label: 'Scope', kind: 'long' }, { key: 'risk_level', label: 'Risk', options: RISK, required: true },
      { key: 'status', label: 'Status', options: ['PLANNED', 'IN_PROGRESS', 'DONE', 'REVIEW_DUE'] }, { key: 'conducted_on', label: 'Conducted', kind: 'date' }, { key: 'review_due', label: 'Review due', kind: 'date' },
      { key: 'owner', label: 'Owner' }, { key: 'reference', label: 'Where the document is kept' },
    ],
  },
  {
    key: 'notices', label: 'Privacy notices', note: 'One notice is in force at a time. An active notice cannot be rewritten: add a new version and activate it, then give it to members again.',
    columns: ['version', 'title', 'status', 'effective_from'],
    fields: [{ key: 'version', label: 'Version', required: true }, { key: 'title', label: 'Title', required: true }, { key: 'body', label: 'Text', kind: 'long', required: true }, { key: 'effective_from', label: 'Effective from', kind: 'date' }],
  },
  {
    key: 'retention', label: 'Retention', note: 'Starting rules are NOT confirmed. Review each against the Data Privacy Act and the cooperative\'s record-keeping rules before production.',
    columns: ['category', 'retain_years', 'starts_from', 'action', 'confirmed'],
    fields: [
      { key: 'category', label: 'Data', required: true }, { key: 'retain_years', label: 'Years', kind: 'number' }, { key: 'starts_from', label: 'Counted from' },
      { key: 'action', label: 'Then', options: ['KEEP', 'ARCHIVE', 'ANONYMIZE', 'DELETE'], required: true }, { key: 'legal_basis', label: 'Legal basis', kind: 'long' }, { key: 'notes', label: 'Notes', kind: 'long' },
      { key: 'confirmed', label: 'Confirmed', kind: 'bool' },
    ],
  },
  {
    key: 'classification', label: 'Classification', note: 'How sensitive each kind of data is and how it is handled. Starting entries are NOT reviewed.', columns: ['data_item', 'where_stored', 'classification', 'reviewed'],
    fields: [
      { key: 'data_item', label: 'Data', required: true }, { key: 'where_stored', label: 'Where it is kept', required: true }, { key: 'classification', label: 'Class', options: ['PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'SENSITIVE'], required: true },
      { key: 'handling', label: 'How it is handled', kind: 'long' }, { key: 'reviewed', label: 'Reviewed', kind: 'bool' },
    ],
  },
]

const label = (k: string) => k.replace(/_/g, ' ').replace(/^./, (c) => c.toUpperCase())
const show = (v: Row[string] | undefined) => (v == null || v === '' ? '—' : typeof v === 'number' ? (v === 1 || v === 0 ? v === 1 ? 'Yes' : 'No' : String(v)) : String(v).replace('T', ' '))
const shows = (k: string, v: Row[string] | undefined) => (['confirmed', 'reviewed'].includes(k) ? (v === 1 ? 'Yes' : 'No') : show(v))

function Cells({ row, columns }: { row: Row; columns: string[] }) {
  return <>{columns.map((c) => <TableCell key={c} sx={{ maxWidth: 280, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={String(row[c] ?? '')}>{shows(c, row[c])}</TableCell>)}</>
}

function RowForm({ fields, initial, title, onSave, onClose }: { fields: Field[]; initial?: Row; title: string; onSave: (v: Record<string, string>) => Promise<unknown>; onClose: () => void }) {
  const [v, setV] = useState<Record<string, string>>(() => Object.fromEntries(fields.map((f) => [f.key, initial?.[f.key] == null ? '' : f.kind === 'datetime' ? String(initial[f.key]).slice(0, 16) : String(initial[f.key])])))
  const [error, setError] = useState<string | null>(null)
  const set = (k: string, x: string) => setV((p) => ({ ...p, [k]: x }))
  const save = () => {
    const out: Record<string, string> = {}
    for (const f of fields) if (!initial || v[f.key] !== String(initial[f.key] ?? '').slice(0, f.kind === 'datetime' ? 16 : 9999)) out[f.key] = v[f.key]
    onSave(out).then(onClose, (e: Error) => setError(e.message))
  }
  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>{title}</DialogTitle>
      <DialogContent>
        <Stack spacing={1.5} sx={{ pt: 1 }}>
          {error && <Alert severity="error">{error}</Alert>}
          {fields.map((f) => f.kind === 'bool' ? (
            <TextField key={f.key} select size="small" label={f.label} value={v[f.key] === '1' ? '1' : '0'} onChange={(e) => set(f.key, e.target.value)}>
              <MenuItem value="0">No</MenuItem><MenuItem value="1">Yes</MenuItem>
            </TextField>
          ) : (
            <TextField
              key={f.key} size="small" label={f.label} required={f.required} value={v[f.key]} onChange={(e) => set(f.key, e.target.value)} select={!!f.options} multiline={f.kind === 'long'} minRows={f.kind === 'long' ? 3 : undefined}
              type={f.kind === 'date' ? 'date' : f.kind === 'datetime' ? 'datetime-local' : f.kind === 'number' ? 'number' : 'text'} slotProps={{ inputLabel: { shrink: f.kind === 'date' || f.kind === 'datetime' ? true : undefined } }}
            >
              {f.options?.map((o) => <MenuItem key={o} value={o}>{label(o)}</MenuItem>)}
            </TextField>
          ))}
        </Stack>
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" onClick={save}>Save</Button></DialogActions>
    </Dialog>
  )
}

function RegisterTab({ reg }: { reg: Register }) {
  const qc = useQueryClient()
  const rows = useQuery({ queryKey: ['privacy', reg.key], queryFn: () => listRegister(reg.key) })
  const [editing, setEditing] = useState<Row | 'new' | null>(null)
  const [error, setError] = useState<string | null>(null)
  const refresh = () => { void qc.invalidateQueries({ queryKey: ['privacy', reg.key] }); void qc.invalidateQueries({ queryKey: ['privacy-review'] }) }
  const activate = useMutation({ mutationFn: activateNotice, onSuccess: () => { setError(null); refresh() }, onError: (e) => setError((e as Error).message) })
  return (
    <Box>
      <Stack direction="row" spacing={1} sx={{ mb: 1.5, alignItems: 'center' }}>
        <Typography color="text.secondary" sx={{ flex: 1, fontSize: 13 }}>{reg.note}</Typography>
        <Button variant="contained" size="small" onClick={() => setEditing('new')}>Add</Button>
      </Stack>
      {error && <Alert severity="error" sx={{ mb: 1 }}>{error}</Alert>}
      <Box sx={(t) => ({ overflowX: 'auto', ...glass(t) })}>
        <Table size="small">
          <TableHead><TableRow>{reg.columns.map((c) => <TableCell key={c}>{label(c)}</TableCell>)}<TableCell /></TableRow></TableHead>
          <TableBody>
            {(rows.data ?? []).map((r) => (
              <TableRow key={String(r.id)} hover>
                <Cells row={r} columns={reg.columns} />
                <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                  {reg.key === 'notices' && r.status !== 'ACTIVE' && <Button size="small" onClick={() => activate.mutate(Number(r.id))}>Activate</Button>}
                  <Button size="small" onClick={() => setEditing(r)}>{reg.key === 'notices' && r.status === 'ACTIVE' ? 'View' : 'Edit'}</Button>
                </TableCell>
              </TableRow>
            ))}
            {rows.data?.length === 0 && <TableRow><TableCell colSpan={reg.columns.length + 1}><Typography color="text.secondary" sx={{ py: 2, textAlign: 'center' }}>Nothing recorded yet.</Typography></TableCell></TableRow>}
          </TableBody>
        </Table>
      </Box>
      {editing && (
        <RowForm
          fields={editing !== 'new' && reg.key === 'notices' && editing.status === 'ACTIVE' ? reg.fields.filter((f) => f.key !== 'body') : reg.fields} title={editing === 'new' ? `Add: ${reg.label}` : reg.label}
          initial={editing === 'new' ? undefined : editing} onClose={() => setEditing(null)}
          onSave={(v) => (editing === 'new' ? addToRegister(reg.key, v) : changeRegister(reg.key, Number(editing.id), v)).then(refresh)}
        />
      )}
    </Box>
  )
}

const TYPES = ['ACCESS', 'CORRECTION', 'ERASURE', 'OBJECTION', 'PORTABILITY', 'WITHDRAW_CONSENT', 'OTHER']

function NewRequest({ onClose }: { onClose: () => void }) {
  const qc = useQueryClient()
  const [f, setF] = useState({ requestType: 'ACCESS', branch: '1', client: '', name: '', contact: '', summary: '' })
  const [error, setError] = useState<string | null>(null)
  const set = (k: string, v: string) => setF((p) => ({ ...p, [k]: v }))
  const save = () => addPrivacyRequest({
    requestType: f.requestType, branchCode: f.client ? Number(f.branch) : undefined, clientId: f.client ? Number(f.client) : undefined, requesterName: f.name, contact: f.contact || undefined, summary: f.summary,
  }).then(() => { void qc.invalidateQueries({ queryKey: ['privacy-requests'] }); onClose() }, (e: Error) => setError(e.message))
  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>Log a request about someone's data</DialogTitle>
      <DialogContent>
        <Stack spacing={1.5} sx={{ pt: 1 }}>
          {error && <Alert severity="error">{error}</Alert>}
          <TextField select size="small" label="Request" value={f.requestType} onChange={(e) => set('requestType', e.target.value)}>{TYPES.map((t) => <MenuItem key={t} value={t}>{label(t)}</MenuItem>)}</TextField>
          <Stack direction="row" spacing={1}>
            <TextField size="small" label="Branch" value={f.branch} onChange={(e) => set('branch', e.target.value)} sx={{ width: 100 }} />
            <TextField size="small" label="Member number" value={f.client} onChange={(e) => set('client', e.target.value)} fullWidth />
          </Stack>
          <TextField size="small" label="Name of the person asking" required value={f.name} onChange={(e) => set('name', e.target.value)} />
          <TextField size="small" label="Contact" value={f.contact} onChange={(e) => set('contact', e.target.value)} />
          <TextField size="small" label="What is being asked" required multiline minRows={3} value={f.summary} onChange={(e) => set('summary', e.target.value)} />
        </Stack>
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" onClick={save}>Log request</Button></DialogActions>
    </Dialog>
  )
}

function RequestsTab() {
  const qc = useQueryClient()
  const rows = useQuery({ queryKey: ['privacy-requests'], queryFn: listPrivacyRequests })
  const [adding, setAdding] = useState(false)
  const [asking, setAsking] = useState<{ row: Row; step: RequestStep; title: string; label: string } | null>(null)
  const [error, setError] = useState<string | null>(null)
  const refresh = () => qc.invalidateQueries({ queryKey: ['privacy-requests'] })
  const run = (p: Promise<unknown>) => p.then(() => { setError(null); return refresh() }, (e: Error) => setError(e.message))
  const today = new Date().toISOString().slice(0, 10)
  return (
    <Box>
      <Stack direction="row" spacing={1} sx={{ mb: 1.5, alignItems: 'center' }}>
        <Typography color="text.secondary" sx={{ flex: 1, fontSize: 13 }}>
          Requests from people about their own data. The due date is 30 days from receipt unless the server is set otherwise; confirm the period that applies with the data protection officer. Check the person's identity before anything is handed over or erased.
        </Typography>
        <Button variant="contained" size="small" onClick={() => setAdding(true)}>Log a request</Button>
      </Stack>
      {error && <Alert severity="error" sx={{ mb: 1 }}>{error}</Alert>}
      <Stack spacing={1}>
        {(rows.data ?? []).map((r) => {
          const open = r.status === 'OPEN' || r.status === 'IN_PROGRESS'
          const late = open && String(r.due_on) < today
          const id = Number(r.id)
          const kind = String(r.request_type)
          return (
            <Box key={id} sx={(t) => ({ p: 1.5, ...glass(t) })}>
              <Stack direction="row" spacing={1} useFlexGap sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
                <Typography sx={{ fontWeight: 600 }}>{String(r.ref)}</Typography>
                <Chip size="small" label={label(kind)} />
                <Chip size="small" color={open ? 'warning' : r.status === 'FULFILLED' ? 'success' : 'default'} label={label(String(r.status))} />
                {late && <Chip size="small" color="error" label="Overdue" />}
                {open && r.identity_verified === 1 && <Chip size="small" variant="outlined" label="Identity checked" />}
                <Box sx={{ flex: 1 }} />
                <Typography color="text.secondary" sx={{ fontSize: 12.5 }}>Due {String(r.due_on)}</Typography>
              </Stack>
              <Typography sx={{ fontSize: 13.5, mt: 0.5 }}>{String(r.requester_name)}{r.client_id != null ? ` · member ${r.branch_code}-${r.client_id}` : ''} — {String(r.summary)}</Typography>
              {r.outcome != null && <Typography color="text.secondary" sx={{ fontSize: 13 }}>Outcome: {String(r.outcome)}</Typography>}
              {open && (
                <Stack direction="row" spacing={0.5} useFlexGap sx={{ mt: 1, flexWrap: 'wrap' }}>
                  {r.identity_verified !== 1 && <Button size="small" onClick={() => setAsking({ row: r, step: 'verify-identity', title: 'Identity checked', label: 'How was the identity checked?' })}>Check identity</Button>}
                  {r.status === 'OPEN' && <Button size="small" onClick={() => void run(startPrivacyRequest(id))}>Start</Button>}
                  {(kind === 'ACCESS' || kind === 'PORTABILITY') && r.identity_verified === 1 && <Button size="small" onClick={() => void run(downloadMemberPackage(id, String(r.ref)))}>Download member data</Button>}
                  {(kind === 'ERASURE' || kind === 'WITHDRAW_CONSENT') && r.identity_verified === 1 && <Button size="small" color="warning" onClick={() => void run(erasePrivacyBiometrics(id))}>Erase fingerprints</Button>}
                  <Button size="small" onClick={() => setAsking({ row: r, step: 'fulfil', title: 'Fulfil the request', label: 'What was done?' })}>Fulfil</Button>
                  <Button size="small" onClick={() => setAsking({ row: r, step: 'refuse', title: 'Refuse the request', label: 'Reason for refusing' })}>Refuse</Button>
                  <Button size="small" onClick={() => setAsking({ row: r, step: 'withdraw', title: 'Request withdrawn', label: 'Why was it withdrawn?' })}>Withdrawn</Button>
                </Stack>
              )}
            </Box>
          )
        })}
        {rows.data?.length === 0 && <Typography color="text.secondary" sx={{ py: 3, textAlign: 'center' }}>No requests logged.</Typography>}
      </Stack>
      {adding && <NewRequest onClose={() => setAdding(false)} />}
      {asking && <ReasonDialog title={asking.title} label={asking.label} onClose={() => setAsking(null)} onRun={(note) => stepPrivacyRequest(Number(asking.row.id), asking.step, note).then(refresh)} />}
    </Box>
  )
}

function ReviewTab() {
  const review = useQuery({ queryKey: ['privacy-review'], queryFn: getPrivacyReview })
  const exportsQ = useQuery({ queryKey: ['privacy-exports'], queryFn: listExports })
  return (
    <Stack spacing={2}>
      <Box sx={(t) => ({ overflowX: 'auto', ...glass(t) })}>
        <Table size="small">
          <TableHead><TableRow><TableCell>To look at</TableCell><TableCell align="right">Count</TableCell><TableCell>What to do</TableCell></TableRow></TableHead>
          <TableBody>
            {(review.data ?? []).map((r) => (
              <TableRow key={r.what}><TableCell>{r.what}</TableCell><TableCell align="right"><Chip size="small" color={r.count > 0 ? 'warning' : 'success'} label={r.count} /></TableCell><TableCell sx={{ color: 'text.secondary' }}>{r.note}</TableCell></TableRow>
            ))}
          </TableBody>
        </Table>
      </Box>
      <Typography sx={{ fontWeight: 600 }}>Export log</Typography>
      <Box sx={(t) => ({ overflowX: 'auto', ...glass(t) })}>
        <Table size="small">
          <TableHead><TableRow>{['at', 'username', 'export_type', 'subject', 'row_count', 'purpose'].map((c) => <TableCell key={c}>{label(c)}</TableCell>)}</TableRow></TableHead>
          <TableBody>
            {(exportsQ.data ?? []).map((r) => <TableRow key={String(r.id)}><Cells row={r} columns={['at', 'username', 'export_type', 'subject', 'row_count', 'purpose']} /></TableRow>)}
            {exportsQ.data?.length === 0 && <TableRow><TableCell colSpan={6}><Typography color="text.secondary" sx={{ py: 2, textAlign: 'center' }}>No exports logged yet.</Typography></TableCell></TableRow>}
          </TableBody>
        </Table>
      </Box>
    </Stack>
  )
}

/** The data protection officer's workspace: requests from people, incidents, sharing, assessments, notices, retention and classification, and the export log. */
export default function PrivacyPage() {
  const [tab, setTab] = useState('requests')
  return (
    <Box sx={(t) => ({ minHeight: '100%', p: { xs: 2, md: 3 }, background: appBackground(t) })}>
      <Typography variant="h5" sx={{ fontWeight: 700, mb: 0.5 }}>Data privacy</Typography>
      <Alert severity="info" sx={{ mb: 1.5 }}>The starting retention rules and classifications are a draft, not legal advice. Review them against the Data Privacy Act before production.</Alert>
      <Tabs value={tab} onChange={(_, v: string) => setTab(v)} variant="scrollable" scrollButtons="auto" sx={{ mb: 1.5 }}>
        <Tab value="requests" label="Requests" />
        {REGISTERS.map((r) => <Tab key={r.key} value={r.key} label={r.label} />)}
        <Tab value="review" label="Review and exports" />
      </Tabs>
      {tab === 'requests' && <RequestsTab />}
      {tab === 'review' && <ReviewTab />}
      {REGISTERS.filter((r) => r.key === tab).map((r) => <RegisterTab key={r.key} reg={r} />)}
    </Box>
  )
}
