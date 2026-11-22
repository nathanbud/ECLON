import {
  Alert, Box, Button, Checkbox, Chip, FormControlLabel, MenuItem, Stack, Tab, Table, TableBody, TableCell, TableHead, TableRow, Tabs, TextField, Typography,
} from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import {
  approveCic, correctionCic, createCicBatch, downloadCicFile, generateCic, getCicBatch, listCicBatches, listCicFindings, listCicRecords, reconcileCic, resultCic, submitCic,
  SUBMISSION_TYPES, validateCic, type CicBatch, type CicStatus,
} from './api/cic'
import { appBackground, glass } from './glass'
import { labelOf, CIC_WORDS } from './ui/labels'
import { LoadingRow } from './ui/Loading'
import { Hinted } from './appleUi'

const STEPS: CicStatus[] = ['DRAFT', 'GENERATED', 'VALIDATED', 'APPROVED', 'SUBMITTED', 'RESULT', 'RECONCILED']
const today = () => new Date().toISOString().slice(0, 10)
const message = (e: unknown) => (e instanceof Error ? e.message : 'Something went wrong.')
const color = (s: string) => (s === 'ERROR' ? 'error' : s === 'WARNING' ? 'warning' : 'success')

/**
 * Credit Information Corporation submission (guide phase 11). Make a batch with its header, generate it from the books as at the cut-off date, read what validation found,
 * have someone else approve it, download the file, record that it was submitted, record the CIC's result and reconcile. Encryption, the Excel copy and the transmittal letter are not built.
 */
export default function CicPage() {
  const qc = useQueryClient()
  const batches = useQuery({ queryKey: ['cic-batches'], queryFn: listCicBatches })
  const [selected, setSelected] = useState<number | null>(null)
  const [error, setError] = useState('')
  const [info, setInfo] = useState('')
  const [form, setForm] = useState({ submissionType: SUBMISSION_TYPES[0], cutOffDate: today(), providerCode: '', version: '1.0', remarks: '' })
  const [sample, setSample] = useState('')
  const [excludeErrors, setExcludeErrors] = useState(false)
  const [note, setNote] = useState('')
  const [reference, setReference] = useState('')
  const [counts, setCounts] = useState({ accepted: '', rejected: '' })
  const [tab, setTab] = useState<'records' | 'findings' | 'log'>('findings')
  const [type, setType] = useState('')
  const [rstatus, setRstatus] = useState('')
  const [page, setPage] = useState(0)

  const id = selected ?? batches.data?.[0]?.id ?? null
  const batch = useQuery({ queryKey: ['cic-batch', id], queryFn: () => getCicBatch(id as number), enabled: id !== null })
  const b = batch.data
  const records = useQuery({ queryKey: ['cic-records', id, type, rstatus, page, b?.status], queryFn: () => listCicRecords(id as number, type, rstatus, page), enabled: id !== null && tab === 'records' })
  const findings = useQuery({ queryKey: ['cic-findings', id, b?.status], queryFn: () => listCicFindings(id as number), enabled: id !== null && tab === 'findings' })

  const refresh = (nb?: CicBatch) => {
    if (nb) { setSelected(nb.id); qc.setQueryData(['cic-batch', nb.id], nb) }
    void qc.invalidateQueries({ queryKey: ['cic-batches'] })
    void qc.invalidateQueries({ queryKey: ['cic-records'] })
    void qc.invalidateQueries({ queryKey: ['cic-findings'] })
  }
  const run = useMutation({
    mutationFn: async (f: () => Promise<CicBatch | string>) => f(),
    onMutate: () => { setError(''); setInfo('') },
    onSuccess: (r) => { if (typeof r === 'string') setInfo(r); else refresh(r) },
    onError: (e) => setError(message(e)),
  })
  const act = (f: () => Promise<CicBatch | string>) => run.mutate(f)
  const busy = run.isPending

  return (
    <Box component="main" sx={(t) => ({ minHeight: 'calc(100dvh - 44px)', p: { xs: 2, md: 3 }, background: appBackground(t) })}>
      <Box sx={{ maxWidth: 1100, mx: 'auto' }}>
        <Typography component="h1" sx={{ fontSize: 20, fontWeight: 700, letterSpacing: '-0.02em', mb: 1 }}>CIC submission</Typography>
        {error && <Alert severity="error" sx={{ mb: 1.5 }} onClose={() => setError('')}>{error}</Alert>}
        {info && <Alert severity="success" sx={{ mb: 1.5 }} onClose={() => setInfo('')}>{info}</Alert>}

        <Box component="section" aria-labelledby="cic-new" sx={(t) => ({ p: 2, mb: 2, ...glass(t) })}>
          <Typography id="cic-new" sx={{ fontWeight: 600, mb: 1.5 }}>New batch</Typography>
          <Box sx={{ display: 'grid', gap: 1.5, gridTemplateColumns: { xs: '1fr', md: 'repeat(4, 1fr)' }, alignItems: 'start' }}>
            <TextField select label="Submission type" value={form.submissionType} onChange={(e) => setForm({ ...form, submissionType: e.target.value })}>
              {SUBMISSION_TYPES.map((s) => <MenuItem key={s} value={s}>{s}</MenuItem>)}
            </TextField>
            <TextField label="Cut-off date" type="date" value={form.cutOffDate} onChange={(e) => setForm({ ...form, cutOffDate: e.target.value })} slotProps={{ inputLabel: { shrink: true } }} />
            <Hinted tip="Assigned to the cooperative by the CIC"><TextField label="Provider code" value={form.providerCode} onChange={(e) => setForm({ ...form, providerCode: e.target.value })} /></Hinted>
            <TextField label="Version" value={form.version} onChange={(e) => setForm({ ...form, version: e.target.value })} />
            <TextField label="Remarks" value={form.remarks} onChange={(e) => setForm({ ...form, remarks: e.target.value })} sx={{ gridColumn: { md: 'span 3' } }} />
            <Button variant="contained" disabled={busy || !form.providerCode.trim()} onClick={() => act(() => createCicBatch(form))} sx={{ mt: { md: 0.5 } }}>Create batch</Button>
          </Box>
        </Box>

        <Box component="section" aria-labelledby="cic-batches" sx={(t) => ({ p: 2, mb: 2, ...glass(t) })}>
          <Typography id="cic-batches" sx={{ fontWeight: 600, mb: 1 }}>Batches</Typography>
          <Box sx={{ overflowX: 'auto', border: 1, borderColor: 'divider', borderRadius: '5px' }}>
            <Table size="small" aria-label="Batches">
              <TableHead><TableRow>{['Reference', 'Cut-off', 'Type', 'Status', 'Records', 'Created by'].map((c) => <TableCell key={c}>{c}</TableCell>)}</TableRow></TableHead>
              <TableBody>
                <LoadingRow q={batches} what="the batches" />
                {(batches.data ?? []).map((r) => (
                  <TableRow key={r.id} hover selected={r.id === id} onClick={() => { setSelected(r.id); setPage(0) }} sx={{ cursor: 'pointer' }}>
                    <TableCell>{r.ref}</TableCell><TableCell>{r.cut_off_date}</TableCell><TableCell>{SUBMISSION_TYPES[r.submission_type]}</TableCell>
                    <TableCell>{labelOf(r.status, CIC_WORDS)}</TableCell><TableCell>{r.subjects + r.installment_contracts + r.other_contracts}</TableCell><TableCell>{r.created_by}</TableCell>
                  </TableRow>
                ))}
                {batches.data?.length === 0 && <TableRow><TableCell colSpan={6}><Typography color="text.secondary" sx={{ py: 1, textAlign: 'center', fontSize: 13 }}>No batches yet. Fill in the form above and choose Create batch.</Typography></TableCell></TableRow>}
              </TableBody>
            </Table>
          </Box>
        </Box>

        {b && (
          <Box component="section" aria-labelledby="cic-batch" sx={(t) => ({ p: 2, ...glass(t) })}>
            <Typography id="cic-batch" sx={{ fontWeight: 600, mb: 1 }}>{b.ref} · cut-off {b.cut_off_date}</Typography>
            <Stack direction="row" spacing={0.5} useFlexGap sx={{ flexWrap: 'wrap', mb: 1.5 }} aria-label="Steps">
              {STEPS.map((s) => <Chip key={s} size="small" label={s} color={s === b.status ? 'primary' : 'default'} variant={STEPS.indexOf(s) <= STEPS.indexOf(b.status) ? 'filled' : 'outlined'} />)}
            </Stack>
            <Typography sx={{ fontSize: 13, mb: 1 }}>
              {b.subjects} borrowers · {b.installment_contracts} installment loans · {b.other_contracts} lump-sum loans · {b.errors} with errors · {b.warnings} with warnings
              {b.excluded > 0 ? ` · ${b.excluded} left out` : ''}{b.submission_reference ? ` · CIC reference ${b.submission_reference}` : ''}
            </Typography>
            {!b.mappingConfirmed && b.status !== 'DRAFT' && (
              <Alert severity="warning" sx={{ mb: 1.5 }}>
                The column mapping {b.mappingVersion} has not been checked against a file the CIC accepted, so the file can be previewed but not approved. Columns not yet mapped: {b.unverified.length}; see Findings.
              </Alert>
            )}

            <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap', alignItems: 'center', mb: 1.5 }}>
              {['DRAFT', 'GENERATED', 'VALIDATED'].includes(b.status) && (<>
                <TextField size="small" label="Sample (loans, empty for all)" value={sample} onChange={(e) => setSample(e.target.value.replace(/\D/g, ''))} sx={{ width: 190 }} />
                <Button variant="contained" disabled={busy} onClick={() => act(() => generateCic(b.id, Number(sample) || 0))}>{b.status === 'DRAFT' ? 'Generate records' : 'Generate again'}</Button>
              </>)}
              {['GENERATED', 'VALIDATED'].includes(b.status) && <Button variant="outlined" disabled={busy} onClick={() => act(() => validateCic(b.id))}>Validate again</Button>}
              {b.status !== 'DRAFT' && b.status !== 'RECONCILED' && <Button variant="outlined" disabled={busy} onClick={() => act(async () => `Downloaded ${await downloadCicFile(b.id)}`)}>{STEPS.indexOf(b.status) >= 3 ? 'Download file' : 'Download preview'}</Button>}
              {b.status === 'RECONCILED' && <Button variant="outlined" disabled={busy} onClick={() => act(async () => `Downloaded ${await downloadCicFile(b.id)}`)}>Download file</Button>}
              {['RESULT', 'RECONCILED'].includes(b.status) && <Button variant="outlined" disabled={busy} onClick={() => act(() => correctionCic(b.id))}>Start correction batch</Button>}
            </Stack>

            {b.status === 'VALIDATED' && (
              <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap', alignItems: 'center', mb: 1.5 }}>
                <TextField size="small" label="Approval note" value={note} onChange={(e) => setNote(e.target.value)} sx={{ width: 260 }} />
                {b.errors > 0 && <FormControlLabel control={<Checkbox checked={excludeErrors} onChange={(e) => setExcludeErrors(e.target.checked)} />} label="Leave out records with errors" />}
                <Button variant="contained" color="success" disabled={busy || (b.errors > 0 && !excludeErrors)} onClick={() => act(() => approveCic(b.id, excludeErrors, note))}>Approve</Button>
                <Typography color="text.secondary" sx={{ fontSize: 12 }}>Someone other than {b.created_by} must approve.</Typography>
              </Stack>
            )}
            {b.status === 'APPROVED' && (
              <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mb: 1.5 }}>
                <TextField size="small" label="CIC reference or receipt" value={reference} onChange={(e) => setReference(e.target.value)} sx={{ width: 260 }} />
                <Button variant="contained" disabled={busy || !reference.trim()} onClick={() => act(() => submitCic(b.id, reference))}>Record as submitted</Button>
              </Stack>
            )}
            {b.status === 'SUBMITTED' && (
              <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap', alignItems: 'center', mb: 1.5 }}>
                <TextField size="small" label="Accepted" value={counts.accepted} onChange={(e) => setCounts({ ...counts, accepted: e.target.value.replace(/\D/g, '') })} sx={{ width: 120 }} />
                <TextField size="small" label="Rejected" value={counts.rejected} onChange={(e) => setCounts({ ...counts, rejected: e.target.value.replace(/\D/g, '') })} sx={{ width: 120 }} />
                <Button variant="contained" disabled={busy || !counts.accepted || !counts.rejected} onClick={() => act(() => resultCic(b.id, Number(counts.accepted), Number(counts.rejected), note))}>Record the CIC’s result</Button>
              </Stack>
            )}
            {b.status === 'RESULT' && <Button variant="contained" disabled={busy} sx={{ mb: 1.5 }} onClick={() => act(() => reconcileCic(b.id))}>Reconcile</Button>}

            <Tabs value={tab} onChange={(_, v: typeof tab) => setTab(v)} sx={{ mb: 1 }}>
              <Tab value="findings" label="Findings" /><Tab value="records" label="Records" /><Tab value="log" label="Status log" />
            </Tabs>

            {tab === 'findings' && (
              <Box sx={{ overflowX: 'auto', border: 1, borderColor: 'divider', borderRadius: '5px' }}>
                <Table size="small" aria-label="Findings">
                  <TableHead><TableRow>{['', 'Record', 'About', 'What is wrong'].map((c) => <TableCell key={c}>{c}</TableCell>)}</TableRow></TableHead>
                  <TableBody>
                    <LoadingRow q={findings} what="the findings" />
                    {(findings.data ?? []).map((f, i) => (
                      <TableRow key={i}>
                        <TableCell><Chip size="small" color={color(f.severity)} label={f.severity} /></TableCell>
                        <TableCell>{f.record_type ? `${f.record_type} ${f.contract_no ?? f.subject_key}` : 'Whole batch'}</TableCell>
                        <TableCell>{f.field}</TableCell><TableCell>{f.message}</TableCell>
                      </TableRow>
                    ))}
                    {findings.data?.length === 0 && <TableRow><TableCell colSpan={4}><Typography color="text.secondary" sx={{ py: 1, textAlign: 'center', fontSize: 13 }}>{b.status === 'DRAFT' ? 'Generate the records first.' : 'Nothing found.'}</Typography></TableCell></TableRow>}
                  </TableBody>
                </Table>
              </Box>
            )}
            {tab === 'records' && (<>
              <Stack direction="row" spacing={1} sx={{ mb: 1 }}>
                <TextField select size="small" label="Type" value={type} onChange={(e) => { setType(e.target.value); setPage(0) }} sx={{ width: 150 }}>
                  <MenuItem value="">All</MenuItem><MenuItem value="ID">Borrowers (ID)</MenuItem><MenuItem value="CI">Installment (CI)</MenuItem><MenuItem value="CN">Lump sum (CN)</MenuItem>
                </TextField>
                <TextField select size="small" label="Status" value={rstatus} onChange={(e) => { setRstatus(e.target.value); setPage(0) }} sx={{ width: 150 }}>
                  <MenuItem value="">All</MenuItem><MenuItem value="ERROR">Errors</MenuItem><MenuItem value="WARNING">Warnings</MenuItem><MenuItem value="VALID">Valid</MenuItem>
                </TextField>
                <Button disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</Button>
                <Button disabled={(page + 1) * 25 >= (records.data?.total ?? 0)} onClick={() => setPage(page + 1)}>Next</Button>
                <Typography sx={{ alignSelf: 'center', fontSize: 13 }}>{records.data?.total ?? 0} records</Typography>
              </Stack>
              <Box sx={{ overflowX: 'auto', border: 1, borderColor: 'divider', borderRadius: '5px' }}>
                <Table size="small" aria-label="Records">
                  <TableHead><TableRow>{['Type', 'Subject', 'Contract', 'Status', 'In file'].map((c) => <TableCell key={c}>{c}</TableCell>)}</TableRow></TableHead>
                  <TableBody>
                    {(records.data?.items ?? []).map((r) => (
                      <TableRow key={r.id}><TableCell>{r.record_type}</TableCell><TableCell>{r.subject_key}</TableCell><TableCell>{r.contract_no ?? '—'}</TableCell>
                        <TableCell><Chip size="small" color={color(r.status)} label={labelOf(r.status)} /></TableCell><TableCell>{r.excluded ? 'Left out' : 'Yes'}</TableCell></TableRow>
                    ))}
                  </TableBody>
                </Table>
              </Box>
            </>)}
            {tab === 'log' && (
              <Box role="log" aria-label="Status log" tabIndex={0} sx={{ bgcolor: 'common.black', color: 'common.white', fontFamily: 'Consolas, "Courier New", monospace', fontSize: 12, p: 1, height: 220, overflow: 'auto', borderRadius: '3px', whiteSpace: 'pre-wrap' }}>
                {[...b.logs.MEMBERS, ...b.logs.LOANS, ...b.logs.FILES].map((l, i) => <div key={i}>{l}</div>)}
              </Box>
            )}
            <Typography color="text.secondary" sx={{ fontSize: 12, mt: 1.5 }}>Encryption, the Excel copy and the transmittal letter are not built: the CIC has not given their layouts.</Typography>
          </Box>
        )}
      </Box>
    </Box>
  )
}
