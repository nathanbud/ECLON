import {
  Alert, Box, Button, Chip, Collapse, IconButton, InputAdornment, MenuItem, Skeleton, Table, TableBody, TableCell, TableHead, TablePagination, TableRow, TextField, Typography,
} from '@mui/material'
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Fragment, useEffect, useState } from 'react'
import { FiChevronDown, FiChevronRight, FiSearch } from 'react-icons/fi'
import { getAuditFacets, getAuditIntegrity, getAuditLog, sealAuditLog, verifyAuditLog, type AuditFilter, type AuditVerification } from './api'
import { appBackground, glass } from './glass'
import { useMe } from './session'

const SIZE = 50
const when = (s: string) => new Date(s).toLocaleString('en-PH', { dateStyle: 'medium', timeStyle: 'medium' })

/** A stored value is often JSON; show it indented so a change reads line by line. */
function pretty(v: string | null) {
  if (v == null || v === '') return '—'
  try { return JSON.stringify(JSON.parse(v), null, 2) } catch { return v }
}


/** Whether the audit trail is intact: when it was last sealed and a check of every seal. Nothing here can change an audit row. */
function Integrity() {
  const qc = useQueryClient()
  const status = useQuery({ queryKey: ['audit-integrity'], queryFn: getAuditIntegrity, staleTime: 30_000 })
  const [result, setResult] = useState<AuditVerification | null>(null)
  const [error, setError] = useState<string | null>(null)
  const verify = useMutation({ mutationFn: verifyAuditLog, onSuccess: (r) => { setError(null); setResult(r) }, onError: (e) => setError((e as Error).message) })
  const seal = useMutation({ mutationFn: sealAuditLog, onSuccess: (s) => { setError(null); qc.setQueryData(['audit-integrity'], s); setResult(null) }, onError: (e) => setError((e as Error).message) })
  const s = status.data
  if (!s) return null
  const when = s.lastSealedAt ? new Date(s.lastSealedAt.replace(' ', 'T')).toLocaleString('en-PH', { dateStyle: 'medium', timeStyle: 'short' }) : null
  return (
    <Box sx={(t) => ({ p: 1.5, mb: 1.5, display: 'flex', alignItems: 'center', gap: 1.5, flexWrap: 'wrap', ...glass(t) })}>
      <Box sx={{ flex: 1, minWidth: 260 }}>
        <Typography sx={{ fontSize: 13, fontWeight: 600 }}>
          {!s.installed ? 'Tamper protection is not installed on this server.' : s.seals === 0 ? 'The audit log has not been sealed yet.' : `Sealed through entry #${s.lastSealedId}${when ? `, ${when}` : ''}.`}
        </Typography>
        {s.installed && (
          <Typography sx={{ fontSize: 12 }} color="text.secondary">
            {s.unsealedRows} newer entr{s.unsealedRows === 1 ? 'y is' : 'ies are'} not sealed yet. {s.nightly ? 'The log is sealed every night.' : 'The nightly seal is switched off.'} Each seal is chained to the one before, so a changed or removed entry can be found.
          </Typography>
        )}
      </Box>
      {s.installed && (
        <>
          <Button size="small" disabled={verify.isPending} onClick={() => verify.mutate()}>{verify.isPending ? 'Checking…' : 'Check the seals'}</Button>
          <Button size="small" disabled={seal.isPending || s.unsealedRows === 0} onClick={() => seal.mutate()}>Seal now</Button>
        </>
      )}
      {error && <Alert severity="error" sx={{ width: '100%' }}>{error}</Alert>}
      {result && (result.ok
        ? <Alert severity="success" sx={{ width: '100%' }}>All {result.sealsChecked} seal{result.sealsChecked === 1 ? '' : 's'} match: {result.rowsChecked.toLocaleString()} sealed entries are exactly as they were when sealed.</Alert>
        : <Alert severity="error" sx={{ width: '100%' }}>
            The audit log does not match its seals.
            <Box component="ul" sx={{ m: 0, pl: 2.5 }}>{result.problems.map((p) => <li key={p}>{p}</li>)}</Box>
          </Alert>)}
    </Box>
  )
}

/** The audit trail, newest first: who did what to which record, when, and why. Read only; administrators only. */
export default function AuditLogPage() {
  const me = useMe()
  const [filter, setFilter] = useState<AuditFilter>({})
  const [text, setText] = useState('')
  const [page, setPage] = useState(0)
  const [open, setOpen] = useState<number | null>(null)
  // The search text is sent a moment after typing stops.
  useEffect(() => {
    const h = setTimeout(() => { setFilter((f) => ({ ...f, q: text.trim() || undefined })); setPage(0) }, 350)
    return () => clearTimeout(h)
  }, [text])
  const set = (k: keyof AuditFilter, v: string) => { setFilter((f) => ({ ...f, [k]: v || undefined })); setPage(0) }

  const facets = useQuery({ queryKey: ['audit-facets'], queryFn: getAuditFacets, enabled: me.admin, staleTime: 60_000 })
  const { data, error, isLoading, isFetching } = useQuery({ queryKey: ['audit-log', filter, page], queryFn: () => getAuditLog(filter, page, SIZE), enabled: me.admin, placeholderData: keepPreviousData })
  const filtered = Object.values(filter).some(Boolean)

  const pick = (label: string, key: keyof AuditFilter, options: string[] = []) => (
    <TextField select label={label} value={filter[key] ?? ''} onChange={(e) => set(key, e.target.value)} sx={{ minWidth: 150 }}>
      <MenuItem value="">All</MenuItem>
      {options.map((o) => <MenuItem key={o} value={o}>{o}</MenuItem>)}
    </TextField>
  )

  return (
    <Box component="main" sx={(t) => ({ minHeight: 'calc(100dvh - 44px)', p: 2, background: appBackground(t) })}>
      <Box sx={{ maxWidth: 1200, mx: 'auto' }}>
        <Typography component="h1" sx={{ fontSize: 20, fontWeight: 700, letterSpacing: '-0.02em', mb: 0.5 }}>Audit log</Typography>
        <Typography color="text.secondary" sx={{ fontSize: 13, mb: 2 }}>Every change the app records, newest first. It can be read here but never changed.</Typography>
        {!me.admin ? <Alert severity="info">Only administrators can read the audit log.</Alert> : (
          <>
            <Integrity />
            <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1.25, mb: 1.5 }}>
              <TextField label="Search" value={text} onChange={(e) => setText(e.target.value)} sx={{ flex: '1 1 240px' }} placeholder="Record number, reason or value"
                slotProps={{ input: { startAdornment: <InputAdornment position="start"><FiSearch /></InputAdornment> } }} />
              {pick('Person', 'user', facets.data?.users)}
              {pick('Record', 'entity', facets.data?.entities)}
              {pick('Action', 'action', facets.data?.actions)}
              <TextField type="date" label="From" value={filter.from ?? ''} onChange={(e) => set('from', e.target.value)} slotProps={{ inputLabel: { shrink: true } }} />
              <TextField type="date" label="To" value={filter.to ?? ''} onChange={(e) => set('to', e.target.value)} slotProps={{ inputLabel: { shrink: true } }} />
              {filtered && <Button color="inherit" onClick={() => { setFilter({}); setText(''); setPage(0) }}>Clear</Button>}
            </Box>
            {error && <Alert severity="error" sx={{ mb: 1 }}>{(error as Error).message}</Alert>}
            <Box sx={(t) => ({ overflow: 'auto', opacity: isFetching && !isLoading ? 0.7 : 1, transition: 'opacity 150ms', ...glass(t) })}>
              <Table size="small" aria-label="Audit log">
                <TableHead>
                  <TableRow><TableCell width={36} /><TableCell>When</TableCell><TableCell>Person</TableCell><TableCell>Action</TableCell><TableCell>Record</TableCell><TableCell>Where</TableCell><TableCell>Reason</TableCell></TableRow>
                </TableHead>
                <TableBody>
                  {isLoading && Array.from({ length: 8 }, (_, i) => <TableRow key={i}><TableCell colSpan={7}><Skeleton /></TableCell></TableRow>)}
                  {data?.content.map((e) => (
                    <Fragment key={e.id}>
                      <TableRow hover onClick={() => setOpen(open === e.id ? null : e.id)} sx={{ cursor: 'pointer', '& > td': { borderBottom: open === e.id ? 0 : undefined } }}>
                        <TableCell sx={{ px: 0.5 }}>
                          <IconButton size="small" aria-label={open === e.id ? 'Hide details' : 'Show details'} aria-expanded={open === e.id}>{open === e.id ? <FiChevronDown /> : <FiChevronRight />}</IconButton>
                        </TableCell>
                        <TableCell sx={{ whiteSpace: 'nowrap', fontVariantNumeric: 'tabular-nums' }}>{when(e.at)}</TableCell>
                        <TableCell>{e.username}</TableCell>
                        <TableCell><Chip size="small" variant="outlined" label={e.action} /></TableCell>
                        <TableCell>{e.entity} · {e.entityId}</TableCell>
                        <TableCell sx={{ whiteSpace: 'nowrap', fontVariantNumeric: 'tabular-nums' }} title={e.clientInfo ?? undefined}>{e.clientIp ?? 'System'}</TableCell>
                        <TableCell sx={{ maxWidth: 320, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{e.reason ?? '—'}</TableCell>
                      </TableRow>
                      <TableRow>
                        <TableCell colSpan={7} sx={{ py: 0, border: 0 }}>
                          <Collapse in={open === e.id} unmountOnExit>
                            <Box sx={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 2, py: 1.25 }}>
                              {([['Before', e.previous], ['After', e.current]] as const).map(([label, v]) => (
                                <Box key={label}>
                                  <Typography sx={{ fontSize: 12 }} color="text.secondary">{label}</Typography>
                                  <Box component="pre" sx={{ m: 0, fontSize: 12, whiteSpace: 'pre-wrap', wordBreak: 'break-word', fontFamily: 'ui-monospace, Consolas, monospace' }}>{pretty(v)}</Box>
                                </Box>
                              ))}
                              {e.clientInfo && <Box sx={{ gridColumn: '1 / -1' }}><Typography sx={{ fontSize: 12 }} color="text.secondary">Device</Typography><Typography sx={{ fontSize: 13 }}>{e.clientInfo}</Typography></Box>}
                              {e.reason && <Box sx={{ gridColumn: '1 / -1' }}><Typography sx={{ fontSize: 12 }} color="text.secondary">Reason</Typography><Typography sx={{ fontSize: 13 }}>{e.reason}</Typography></Box>}
                            </Box>
                          </Collapse>
                        </TableCell>
                      </TableRow>
                    </Fragment>
                  ))}
                  {data && data.content.length === 0 && <TableRow><TableCell colSpan={7}><Typography color="text.secondary" sx={{ py: 2, textAlign: 'center' }}>{filtered ? 'Nothing matches these filters.' : 'The audit log is empty.'}</Typography></TableCell></TableRow>}
                </TableBody>
              </Table>
              <TablePagination component="div" count={data?.totalElements ?? 0} page={page} rowsPerPage={SIZE} rowsPerPageOptions={[SIZE]} onPageChange={(_, p) => { setPage(p); setOpen(null) }} />
            </Box>
          </>
        )}
      </Box>
    </Box>
  )
}
