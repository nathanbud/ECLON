import {
  Alert, Box, Button, Chip, Collapse, IconButton, InputAdornment, MenuItem, Table, TableBody, TableCell, TableHead, TablePagination, TableRow, TextField, Typography,
} from '@mui/material'
import { DatabaseChangeNotice } from './DatabaseChangeNotice'
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Fragment, useEffect, useState } from 'react'
import { FiChevronDown, FiChevronRight, FiSearch } from 'react-icons/fi'
import { getAuditFacets, getAuditIntegrity, getAuditLog, sealAuditLog, verifyAuditLog, type AuditChange, type AuditEntry, type AuditFilter, type AuditVerification } from './api'
import { appBackground, glass } from './glass'
import { useMe } from './session'
import { ScreenTitle } from './appleUi'
import { notifyError } from './ui/notices'
import { BusyArea, Loading } from './ui/Loading'

const SIZE = 50
/** The date, and the time to the millisecond: 13 Nov 2026 · 05:16:17.874. Read from the text itself, so no time zone shifts it. */
export function when(s: string) {
  const m = /^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?/.exec(s)
  if (!m) return s
  const month = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'][Number(m[2]) - 1]
  const ms = (m[7] ?? '').padEnd(3, '0').slice(0, 3)
  return `${Number(m[3])} ${month} ${m[1]} · ${m[4]}:${m[5]}:${m[6]}.${ms}`
}

/** The changed fields of an entry in a few words, for the list. */
function changedFields(e: AuditEntry) {
  const names = e.changes.map((c) => c.label)
  if (names.length === 0) return '—'
  if (names.length === 1 && names[0] === 'Value') return e.previous && e.current ? 'Value changed' : e.current ? 'Added' : 'Removed'
  return names.length <= 3 ? names.join(', ') : `${names.slice(0, 3).join(', ')} and ${names.length - 3} more`
}

/** What changed, field by field: the field, its value before and its value after. */
function Changes({ changes }: { changes: AuditChange[] }) {
  const empty = <Typography variant="body2" component="span" color="text.disabled" sx={{ fontStyle: 'italic' }}>none</Typography>
  return (
    <Table size="small" aria-label="What changed" sx={(t) => ({ '& td, & th': { py: 0.5, verticalAlign: 'top' }, '& th': { color: t.palette.text.secondary, fontWeight: 600 } })}>
      <TableHead><TableRow><TableCell sx={{ width: '24%' }}>Field</TableCell><TableCell sx={{ width: '38%' }}>Before</TableCell><TableCell>After</TableCell></TableRow></TableHead>
      <TableBody>
        {changes.map((c, i) => (
          <TableRow key={`${c.field}-${i}`}>
            <TableCell title={c.field && c.field !== c.label ? c.field : undefined}>{c.label}</TableCell>
            <TableCell sx={(t) => ({ whiteSpace: 'pre-wrap', wordBreak: 'break-word', color: c.before == null ? undefined : t.palette.error.main, textDecoration: c.before != null && c.after != null ? 'line-through' : undefined })}>{c.before ?? empty}</TableCell>
            <TableCell sx={(t) => ({ whiteSpace: 'pre-wrap', wordBreak: 'break-word', color: c.after == null ? undefined : t.palette.success.main, fontWeight: 500 })}>{c.after ?? empty}</TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  )
}

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
  const verify = useMutation({ mutationFn: verifyAuditLog, onSuccess: (r) => { setResult(r) }, onError: (e) => notifyError((e as Error).message) })
  const seal = useMutation({ mutationFn: sealAuditLog, onSuccess: (s) => { qc.setQueryData(['audit-integrity'], s); setResult(null) }, onError: (e) => notifyError((e as Error).message) })
  const s = status.data
  if (!s) return null
  const when = s.lastSealedAt ? new Date(s.lastSealedAt.replace(' ', 'T')).toLocaleString('en-PH', { dateStyle: 'medium', timeStyle: 'short' }) : null
  return (
    <Box sx={(t) => ({ p: 1.5, mb: 1.5, display: 'flex', alignItems: 'center', gap: 1.5, flexWrap: 'wrap', ...glass(t) })}>
      <Box sx={{ flex: 1, minWidth: 260 }}>
        <Typography variant="subtitle2" component="p">
          {!s.installed ? 'Tamper protection is not set up on this database yet.' : s.seals === 0 ? 'The audit log has not been sealed yet.' : `Sealed through entry #${s.lastSealedId}${when ? `, ${when}` : ''}.`}
        </Typography>
        {!s.installed && <DatabaseChangeNotice>Entries are kept, but cannot be sealed against changes until it is.</DatabaseChangeNotice>}
        {s.installed && (
          <Typography variant="body2" color="text.secondary">
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

  // administrators and auditors read the log; only administrators seal it
  const reader = me.admin || me.roles.some((r) => r.replace('ROLE_', '') === 'AUDITOR')
  const facets = useQuery({ queryKey: ['audit-facets'], queryFn: getAuditFacets, enabled: reader, staleTime: 60_000 })
  const { data, error, isLoading, isFetching } = useQuery({ queryKey: ['audit-log', filter, page], queryFn: () => getAuditLog(filter, page, SIZE), enabled: reader, placeholderData: keepPreviousData })
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
        <ScreenTitle title="Audit log" sx={{ mb: 2 }}>Every change the app records, newest first. It can be read here but never changed.</ScreenTitle>
        {!reader ? <Alert severity="info">Only administrators and auditors can read the audit log.</Alert> : (
          <>
            {me.admin && <Integrity />}
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
            <BusyArea busy={isFetching && !isLoading} label="Loading the audit log"><Box sx={(t) => ({ overflow: 'auto', ...glass(t) })}>
              <Table size="small" aria-label="Audit log">
                <TableHead>
                  <TableRow><TableCell width={36} /><TableCell>When</TableCell><TableCell>Person</TableCell><TableCell>Action</TableCell><TableCell>Record</TableCell><TableCell>What changed</TableCell><TableCell>Where</TableCell><TableCell>Reason</TableCell></TableRow>
                </TableHead>
                <TableBody>
                  {isLoading && <TableRow><TableCell colSpan={8} sx={{ borderBottom: 0 }}><Loading what="the audit log" /></TableCell></TableRow>}
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
                        <TableCell sx={{ maxWidth: 260, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{changedFields(e)}</TableCell>
                        <TableCell sx={{ whiteSpace: 'nowrap', fontVariantNumeric: 'tabular-nums' }} title={e.clientInfo ?? undefined}>{e.clientIp ?? 'System'}</TableCell>
                        <TableCell sx={{ maxWidth: 320, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{e.reason ?? '—'}</TableCell>
                      </TableRow>
                      <TableRow>
                        <TableCell colSpan={8} sx={{ py: 0, border: 0 }}>
                          <Collapse in={open === e.id} unmountOnExit>
                            <Box sx={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 2, py: 1.25 }}>
                              <Box sx={{ gridColumn: '1 / -1' }}>
                                <Typography variant="body2" color="text.secondary">When, to the millisecond</Typography>
                                <Typography sx={{ fontVariantNumeric: 'tabular-nums' }}>{when(e.at)}</Typography>
                              </Box>
                              <Box sx={{ gridColumn: '1 / -1' }}>
                                {e.changes.length > 0 ? <Changes changes={e.changes} /> : <Typography color="text.secondary">No values were recorded for this entry.</Typography>}
                              </Box>
                              {(e.previous || e.current) && (
                                <Box component="details" sx={{ gridColumn: '1 / -1', '& summary': { cursor: 'pointer', fontSize: 12, color: 'text.secondary' } }}>
                                  <summary>The values exactly as stored</summary>
                                  <Box sx={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 2, mt: 1 }}>
                                    {([['Before', e.previous], ['After', e.current]] as const).map(([label, v]) => (
                                      <Box key={label}>
                                        <Typography variant="body2" color="text.secondary">{label}</Typography>
                                        <Box component="pre" sx={{ m: 0, fontSize: 12, whiteSpace: 'pre-wrap', wordBreak: 'break-word', fontFamily: 'ui-monospace, Consolas, monospace' }}>{pretty(v)}</Box>
                                      </Box>
                                    ))}
                                  </Box>
                                </Box>
                              )}
                              {e.clientInfo && <Box sx={{ gridColumn: '1 / -1' }}><Typography variant="body2" color="text.secondary">Device</Typography><Typography>{e.clientInfo}</Typography></Box>}
                              {e.reason && <Box sx={{ gridColumn: '1 / -1' }}><Typography variant="body2" color="text.secondary">Reason</Typography><Typography>{e.reason}</Typography></Box>}
                            </Box>
                          </Collapse>
                        </TableCell>
                      </TableRow>
                    </Fragment>
                  ))}
                  {data && data.content.length === 0 && <TableRow><TableCell colSpan={8}><Typography color="text.secondary" sx={{ py: 2, textAlign: 'center' }}>{filtered ? 'Nothing matches these filters.' : 'The audit log is empty.'}</Typography></TableCell></TableRow>}
                </TableBody>
              </Table>
              <TablePagination component="div" count={data?.totalElements ?? 0} page={page} rowsPerPage={SIZE} rowsPerPageOptions={[SIZE]} onPageChange={(_, p) => { setPage(p); setOpen(null) }} />
            </Box></BusyArea>
          </>
        )}
      </Box>
    </Box>
  )
}
