import { Alert, Box, Button, Chip, Collapse, IconButton, InputAdornment, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { Fragment, useMemo, useState } from 'react'
import { FiChevronDown, FiChevronRight, FiRefreshCw, FiSearch } from 'react-icons/fi'
import { getSystemErrors, type SystemError } from './api'
import { appBackground, glass } from './glass'
import { LoadingRow } from './ui/Loading'
import { ScreenTitle } from './appleUi'

const when = (iso: string) => new Date(iso).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'medium' })

/** The kind of failure, in words, for the list. */
const KINDS: Record<string, string> = {
  VALUE_TOO_LONG: 'Value too long', DUPLICATE: 'Duplicate record', LINKED_RECORD: 'Linked record', MISSING_VALUE: 'Required value missing',
  NO_DATABASE_PERMISSION: 'Database permission', MISSING_TABLE: 'Missing table', MISSING_COLUMN: 'Missing column', DATABASE_UNREACHABLE: 'Database unreachable',
  BUSY: 'Records busy', TOO_SLOW: 'Too slow', BAD_REQUEST_BODY: 'Bad request', UNEXPECTED: 'Unexpected',
}

/** Kinds that are the setup's to fix (a change not applied, a permission, the database down), not the person's. */
const SETUP = new Set(['NO_DATABASE_PERMISSION', 'MISSING_TABLE', 'MISSING_COLUMN', 'DATABASE_UNREACHABLE', 'UNEXPECTED'])

function Detail({ e }: { e: SystemError }) {
  const row = (label: string, value: string | null) => (
    <Box sx={{ display: 'grid', gridTemplateColumns: '150px 1fr', gap: 1.5, py: 0.5 }}>
      <Typography sx={{ fontSize: 12, fontWeight: 600 }} color="text.secondary">{label}</Typography>
      <Typography sx={{ fontSize: 13, overflowWrap: 'anywhere' }}>{value || '—'}</Typography>
    </Box>
  )
  return (
    <Box sx={{ px: 2, py: 1.5 }}>
      {row('The person was told', e.userMessage)}
      {row('Cause', e.problem)}
      {row('What to do', e.fix)}
      {row('Request', `${e.method} ${e.path} (answered ${e.status})`)}
      {row('Error', `${e.exception}${e.detail ? `: ${e.detail}` : ''}`)}
      <Typography sx={{ fontSize: 12, fontWeight: 600, mt: 1, mb: 0.5 }} color="text.secondary">Trace</Typography>
      <Box component="pre" sx={(t) => ({ m: 0, p: 1.25, maxHeight: 280, overflow: 'auto', fontSize: 11, lineHeight: 1.45, borderRadius: '6px', bgcolor: t.palette.action.hover, whiteSpace: 'pre' })}>{e.trace}</Box>
    </Box>
  )
}

function ErrorRow({ e }: { e: SystemError }) {
  const [open, setOpen] = useState(false)
  return (
    <Fragment>
      <TableRow hover sx={{ '& > td': { borderBottom: open ? 'none' : undefined }, cursor: 'pointer' }} onClick={() => setOpen((o) => !o)}>
        <TableCell sx={{ width: 36, p: 0, pl: 0.5 }}>
          <IconButton size="small" aria-label={open ? `Hide the details of ${e.reference}` : `Show the details of ${e.reference}`} aria-expanded={open} onClick={(ev) => { ev.stopPropagation(); setOpen((o) => !o) }}>
            {open ? <FiChevronDown /> : <FiChevronRight />}
          </IconButton>
        </TableCell>
        <TableCell sx={{ whiteSpace: 'nowrap', fontSize: 12 }}>{when(e.at)}</TableCell>
        <TableCell sx={{ whiteSpace: 'nowrap', fontFamily: 'ui-monospace, monospace', fontSize: 12 }}>{e.reference}</TableCell>
        <TableCell><Chip size="small" color={SETUP.has(e.code) ? 'error' : 'default'} label={KINDS[e.code] ?? e.code} /></TableCell>
        <TableCell sx={{ fontSize: 13 }}>{e.problem}</TableCell>
        <TableCell sx={{ whiteSpace: 'nowrap', fontSize: 12 }}>{e.user ?? '—'}</TableCell>
      </TableRow>
      <TableRow>
        <TableCell colSpan={6} sx={{ p: 0, borderBottom: open ? undefined : 'none' }}>
          <Collapse in={open} unmountOnExit><Detail e={e} /></Collapse>
        </TableCell>
      </TableRow>
    </Fragment>
  )
}

/**
 * Settings > System > Error log (administrators): every unexpected failure, newest first, with the technical cause and fix that the person who met it
 * was not shown (they were told what happened in everyday words, with the reference). Search by the reference a person quotes, a user, a table, or
 * any word. Read only; kept on the server in logs/errors.jsonl.
 */
export default function SystemErrorsPage() {
  const errors = useQuery({ queryKey: ['system-errors'], queryFn: getSystemErrors })
  const [q, setQ] = useState('')
  const shown = useMemo(() => {
    const f = q.trim().toLowerCase()
    return (errors.data ?? []).filter((e) => !f || `${e.reference} ${e.user ?? ''} ${e.code} ${KINDS[e.code] ?? ''} ${e.problem} ${e.path} ${e.exception} ${e.detail}`.toLowerCase().includes(f))
  }, [errors.data, q])
  return (
    <Box component="main" sx={(t) => ({ minHeight: 'calc(100dvh - 44px)', p: 2, background: appBackground(t) })}>
      <Box sx={{ maxWidth: 1200, mx: 'auto' }}>
        <ScreenTitle title="Error log" sx={{ mb: 2 }}>
          Every unexpected failure, with its technical cause and what to do. The person who met it was told what happened in everyday words and given the
          reference, so search for the reference they quote. Red ones are the setup&apos;s to fix (a database change not applied, a permission, the database down).
        </ScreenTitle>
        <Box sx={{ display: 'flex', gap: 1.5, alignItems: 'center', mb: 1.5, flexWrap: 'wrap' }}>
          <TextField label="Search: reference, user, table or any word" value={q} onChange={(e) => setQ(e.target.value)} sx={{ width: 380, maxWidth: '100%' }}
            slotProps={{ input: { startAdornment: <InputAdornment position="start"><FiSearch /></InputAdornment> } }} />
          <Button startIcon={<FiRefreshCw />} onClick={() => void errors.refetch()} disabled={errors.isFetching}>{errors.isFetching ? 'Reading…' : 'Refresh'}</Button>
          {errors.data && <Typography sx={{ fontSize: 13 }} color="text.secondary">{shown.length} of {errors.data.length} shown</Typography>}
        </Box>
        {errors.data && errors.data.length === 0 && <Alert severity="success" sx={{ mb: 1.5 }}>No unexpected errors have been recorded.</Alert>}
        <Box sx={(t) => ({ overflow: 'auto', ...glass(t) })}>
          <Table size="small" aria-label="Error log">
            <TableHead>
              <TableRow>
                <TableCell />
                <TableCell sx={{ fontWeight: 700 }}>When</TableCell>
                <TableCell sx={{ fontWeight: 700 }}>Reference</TableCell>
                <TableCell sx={{ fontWeight: 700 }}>Kind</TableCell>
                <TableCell sx={{ fontWeight: 700 }}>Cause</TableCell>
                <TableCell sx={{ fontWeight: 700 }}>User</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              <LoadingRow q={errors} what="the error log" />
              {shown.map((e) => <ErrorRow key={e.reference} e={e} />)}
              {errors.data && errors.data.length > 0 && shown.length === 0 && (
                <TableRow><TableCell colSpan={6}><Typography sx={{ p: 2, textAlign: 'center' }} color="text.secondary">Nothing matches the search.</Typography></TableCell></TableRow>
              )}
            </TableBody>
          </Table>
        </Box>
      </Box>
    </Box>
  )
}
