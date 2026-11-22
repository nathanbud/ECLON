import { Alert, Box, Button, Chip, MenuItem, Stack, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography } from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { cancelYearEndClose, listYearEndCloses, postYearEndClose, prepareYearEndClose, previewYearEndClose, type YearEndClose } from './api'
import { glass } from './glass'
import { useMe } from './session'
import { ReasonDialog, ConfirmButton } from './ui/dialogs'
import { money, tabular } from './ui/format'
import { labelOf } from './ui/labels'
import { AboutNote } from './appleUi'

const statusColor = { PREPARED: 'warning', POSTED: 'success', CANCELLED: 'default' } as const

/**
 * Accounting > Year-end close: the closing of a finished year, worked out from the ledger. One accountant prepares it, another posts it;
 * posting writes the Closing Entry dated 31 December (income and expenses to zero, the surplus to undivided net surplus) and closes the year.
 */
export default function YearEndCloseTab({ branchCode }: { branchCode: number }) {
  const me = useMe()
  const qc = useQueryClient()
  const lastYear = new Date().getFullYear() - 1
  const [year, setYear] = useState(lastYear)
  const [cancelling, setCancelling] = useState<YearEndClose | null>(null)
  const list = useQuery({ queryKey: ['year-end', branchCode], queryFn: () => listYearEndCloses(branchCode) })
  const preview = useQuery({ queryKey: ['year-end', branchCode, year], queryFn: () => previewYearEndClose(branchCode, year) })
  const refresh = () => qc.invalidateQueries({ queryKey: ['year-end'] })
  const prepare = useMutation({ meta: { done: 'Closing prepared. Another accountant can now post it.' }, mutationFn: () => prepareYearEndClose(branchCode, year), onSuccess: refresh })
  const post = useMutation({ meta: { done: 'Closing posted. The year is closed.' }, mutationFn: (id: number) => postYearEndClose(id), onSuccess: () => { refresh(); qc.invalidateQueries({ queryKey: ['periods'] }) } })
  const years = Array.from({ length: 6 }, (_, i) => lastYear + 1 - i)
  const p = preview.data
  const waiting = p?.waiting ?? null
  const error = (list.error ?? preview.error ?? prepare.error ?? post.error) as Error | null

  return (
    <Box>
      <AboutNote sx={{ mb: 2 }}>
        Closing a year brings every income and expense account to zero, department by department, and moves the difference to undivided net surplus,
        in one entry dated 31 December, as the old system does. One person prepares it and another posts it. After that nothing more can be posted into the year;
        a mistake found later is corrected with a journal in an open year.
      </AboutNote>
      <Stack direction="row" spacing={1.5} sx={{ mb: 2, alignItems: 'center' }}>
        <TextField select size="small" label="Year" value={year} onChange={(e) => setYear(Number(e.target.value))} sx={{ width: 120 }}>
          {years.map((y) => <MenuItem key={y} value={y}>{y}</MenuItem>)}
        </TextField>
        {list.data && <Typography color="text.secondary">Last closed year: {list.data.lastClosedYear ?? 'none'}</Typography>}
      </Stack>
      {error && <Alert severity="error" sx={{ mb: 2 }}>{error.message}</Alert>}
      {preview.isLoading && <Typography color="text.secondary">Working out the closing of {year} from the ledger…</Typography>}

      {p && (
        <Box sx={(t) => ({ p: 2, mb: 2, ...glass(t) })}>
          <Stack direction="row" spacing={4} useFlexGap sx={{ flexWrap: 'wrap', mb: 1.5 }}>
            <Figure label="Income" value={p.income} />
            <Figure label="Expenses" value={p.expenses} />
            <Figure label={p.surplus < 0 ? 'Net loss' : 'Net surplus'} value={p.surplus} strong />
            <Figure label="Closing lines" text={String(p.lineCount)} />
            <Figure label="Goes to" text={`${p.surplusAccount} on ${p.closingDate}`} />
          </Stack>
          {p.problems.length > 0 && (
            <Alert severity="info" sx={{ mb: 1.5 }}>
              {p.problems.map((m) => <div key={m}>{m}</div>)}
            </Alert>
          )}
          {waiting && (
            <Alert severity="warning" sx={{ mb: 1.5 }}
              action={(
                <Stack direction="row" spacing={1}>
                  <ConfirmButton size="small" variant="contained" disabled={post.isPending || waiting.preparedBy === me.username}
                    onConfirm={() => post.mutate(waiting.id)} title={`Post the closing of ${year}?`} confirmLabel="Post closing"
                    message={<>Every income and expense account of {year} goes to zero and the {p.surplus < 0 ? 'net loss' : 'net surplus'} of <b>{money(Math.abs(p.surplus))}</b> moves
                      to {p.surplusAccount}, dated {p.closingDate}. The year’s periods close and nothing more can be posted into {year}. This cannot be undone.</>}>
                    {post.isPending ? 'Posting…' : 'Post closing'}
                  </ConfirmButton>
                  <Button size="small" onClick={() => setCancelling(waiting)}>Cancel</Button>
                </Stack>
              )}>
              Prepared by {waiting.preparedBy} on {waiting.preparedAt.replace('T', ' ').slice(0, 16)}, waiting to be posted.
              {waiting.preparedBy === me.username && ' Another accountant must post it.'}
            </Alert>
          )}
          {!waiting && p.problems.length === 0 && (
            <Button variant="contained" disabled={prepare.isPending} onClick={() => prepare.mutate()} sx={{ mb: 1.5 }}>
              {prepare.isPending ? 'Preparing…' : `Prepare closing of ${year}`}
            </Button>
          )}
          {p.departments.length > 0 && (
            <Table size="small" aria-label={`Closing of ${year} by department`}>
              <TableHead>
                <TableRow>
                  <TableCell>Department</TableCell>
                  <TableCell align="right">Income</TableCell>
                  <TableCell align="right">Expenses</TableCell>
                  <TableCell align="right">Surplus</TableCell>
                  <TableCell align="right">Accounts</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {p.departments.map((d) => (
                  <TableRow key={d.dept}>
                    <TableCell>{d.dept}</TableCell>
                    <TableCell align="right" sx={tabular}>{money(d.income)}</TableCell>
                    <TableCell align="right" sx={tabular}>{money(d.expenses)}</TableCell>
                    <TableCell align="right" sx={{ ...tabular, color: d.surplus < 0 ? 'error.main' : undefined }}>{money(d.surplus)}</TableCell>
                    <TableCell align="right" sx={tabular}>{d.lines}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </Box>
      )}

      {list.data && list.data.closes.length > 0 && (
        <>
          <Typography sx={{ fontWeight: 600, mb: 1 }}>Closings made in this application</Typography>
          <Box sx={(t) => ({ overflow: 'auto', ...glass(t) })}>
            <Table size="small" aria-label="Year-end closings">
              <TableHead>
                <TableRow>
                  <TableCell>Year</TableCell><TableCell>Status</TableCell><TableCell align="right">Surplus</TableCell>
                  <TableCell>Prepared by</TableCell><TableCell>Posted or cancelled by</TableCell><TableCell>Entry</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {list.data.closes.map((c) => (
                  <TableRow key={c.id}>
                    <TableCell>{c.year}</TableCell>
                    <TableCell><Chip size="small" label={labelOf(c.status)} color={statusColor[c.status]} /></TableCell>
                    <TableCell align="right" sx={tabular}>{money(c.surplus)}</TableCell>
                    <TableCell>{c.preparedBy}</TableCell>
                    <TableCell>{c.decidedBy ?? ''}{c.reason ? ` (${c.reason})` : ''}</TableCell>
                    <TableCell>{c.controlNo != null ? `Closing Entry no. ${c.controlNo}` : ''}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </Box>
        </>
      )}

      {cancelling && (
        <ReasonDialog title={`Cancel the closing of ${cancelling.year}`} label="Reason"
          onRun={(reason) => cancelYearEndClose(cancelling.id, reason).then(refresh)} onClose={() => setCancelling(null)} />
      )}
    </Box>
  )
}

function Figure({ label, value, text, strong }: { label: string; value?: number; text?: string; strong?: boolean }) {
  return (
    <Box>
      <Typography variant="caption" color="text.secondary">{label}</Typography>
      <Typography sx={{ ...tabular, fontWeight: strong ? 700 : 400, color: value != null && value < 0 ? 'error.main' : undefined }}>
        {text ?? money(value ?? 0)}
      </Typography>
    </Box>
  )
}
