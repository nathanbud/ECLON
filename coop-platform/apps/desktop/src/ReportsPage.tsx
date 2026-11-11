import { Alert, Box, Button, List, ListItemButton, ListItemText, ListSubheader, Skeleton, Stack, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography } from '@mui/material'
import { useMutation, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { listReports, logExport, runReport, type ReportColumn, type ReportInfo, type ReportResult } from './api'
import type { View } from './Dashboard'
import { appBackground, glass } from './glass'
import { useMe } from './session'

const fmt = (c: ReportColumn, v: string | number | null): string => {
  if (v === null || v === undefined || v === '') return ''
  if (typeof v === 'string') return v
  if (c.type === 'money') return v.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })
  if (c.type === 'percent') return `${v.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}%`
  return v.toLocaleString(undefined, { maximumFractionDigits: 3 })
}

const csvCell = (s: string) => (/[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s)

/** The rows as a CSV file (plain numbers, so a spreadsheet can add them up). */
function toCsv(r: ReportResult): string {
  const cols = r.table.columns
  const plain = (v: string | number | null) => (v === null || v === undefined ? '' : String(v))
  const lines = [cols.map((c) => csvCell(c.label)).join(',')]
  for (const row of r.table.rows) lines.push(row.map((v) => csvCell(plain(v))).join(','))
  if (r.table.totals) lines.push(r.table.totals.map((v) => csvCell(plain(v))).join(','))
  return lines.join('\r\n')
}

function download(name: string, text: string) {
  try {
    const url = URL.createObjectURL(new Blob(['﻿' + text], { type: 'text/csv;charset=utf-8' }))
    const a = document.createElement('a')
    a.href = url
    a.download = name
    a.click()
    URL.revokeObjectURL(url)
  } catch {
    // the browser refused the download; the table is still on screen
  }
}

function ResultTable({ r }: { r: ReportResult }) {
  const { columns, rows, totals } = r.table
  const align = (c: ReportColumn) => (c.type === 'text' || c.type === 'date' ? 'left' : 'right')
  return (
    <Box id="report-result">
      <Table size="small">
        <TableHead><TableRow>{columns.map((c) => <TableCell key={c.key} align={align(c)} sx={{ fontWeight: 700 }}>{c.label}</TableCell>)}</TableRow></TableHead>
        <TableBody>
          {rows.map((row, i) => <TableRow key={i} hover>{row.map((v, j) => <TableCell key={j} align={align(columns[j])} sx={{ fontVariantNumeric: 'tabular-nums' }}>{fmt(columns[j], v)}</TableCell>)}</TableRow>)}
          {rows.length === 0 && <TableRow><TableCell colSpan={columns.length}><Typography color="text.secondary">Nothing to show for these choices.</Typography></TableCell></TableRow>}
          {totals && <TableRow>{totals.map((v, j) => <TableCell key={j} align={align(columns[j])} sx={{ fontWeight: 700, fontVariantNumeric: 'tabular-nums', borderTop: '2px solid', borderColor: 'divider' }}>{fmt(columns[j], v)}</TableCell>)}</TableRow>}
        </TableBody>
      </Table>
      {r.table.note && <Typography sx={{ fontSize: 13, mt: 1.5 }} color="text.secondary">{r.table.note}</Typography>}
      <Typography sx={{ fontSize: 12, mt: 1 }} color="text.secondary">Run by {r.ranBy} on {r.ranAt.replace('T', ' ')}</Typography>
    </Box>
  )
}

/**
 * Reports (guide phase 20): the membership, loan, accounting, inventory and POS reports a person's roles allow. Each report says what it asks for, the screen builds the form,
 * shows the table, and can print it or save it as a CSV file for a spreadsheet. Running a report is written to the audit log.
 */
export default function ReportsPage({ onOpen }: { onOpen: (v: View) => void }) {
  const me = useMe()
  const accountant = me.admin || me.roles.some((r) => r.replace('ROLE_', '') === 'ACCOUNTANT')
  const list = useQuery({ queryKey: ['reports'], queryFn: listReports })
  const [picked, setPicked] = useState<ReportInfo | null>(null)
  const [values, setValues] = useState<Record<string, string>>({})
  const [error, setError] = useState<string | null>(null)
  const run = useMutation({ mutationFn: () => runReport(picked!.id, values), onError: (e) => setError((e as Error).message) })
  const groups = [...new Set((list.data ?? []).map((r) => r.group))]

  const pick = (r: ReportInfo) => {
    setPicked(r)
    setValues(Object.fromEntries(r.params.map((p) => [p.name, p.defaultValue])))
    setError(null)
    run.reset()
  }
  return (
    <Box component="main" sx={(t) => ({ minHeight: 'calc(100dvh - 44px)', p: 2, background: appBackground(t) })}>
      <Box sx={{ maxWidth: 1350, mx: 'auto', display: 'grid', gridTemplateColumns: { xs: '1fr', md: '300px 1fr' }, gap: 2, alignItems: 'start' }}>
        <Box sx={(t) => ({ p: 1, ...glass(t) })} className="no-print">
          {accountant && <Button fullWidth sx={{ justifyContent: 'flex-start', mb: 1 }} onClick={() => onOpen('accounting')}>Trial balance, statements, journals, memos, periods →</Button>}
          {list.isLoading && <Skeleton variant="rounded" height={160} />}
          {list.data?.length === 0 && <Typography sx={{ p: 1 }} color="text.secondary">No reports are open to your roles.</Typography>}
          <List dense disablePadding>
            {groups.map((g) => (
              <Box key={g}>
                <ListSubheader disableSticky sx={{ background: 'transparent', lineHeight: '30px', fontWeight: 700, letterSpacing: '0.06em', textTransform: 'uppercase', fontSize: 12 }}>{g}</ListSubheader>
                {(list.data ?? []).filter((r) => r.group === g).map((r) => (
                  <ListItemButton key={r.id} selected={picked?.id === r.id} onClick={() => pick(r)}><ListItemText primary={r.title} /></ListItemButton>
                ))}
              </Box>
            ))}
          </List>
        </Box>
        <Box sx={(t) => ({ p: 2, minHeight: 300, ...glass(t) })}>
          {!picked && <Typography color="text.secondary">Choose a report on the left.</Typography>}
          {picked && (
            <>
              <Typography component="h1" sx={{ fontSize: 20, fontWeight: 700, letterSpacing: '-0.02em' }}>{picked.title}</Typography>
              <Typography sx={{ fontSize: 13, mb: 1.5 }} color="text.secondary">{picked.description}</Typography>
              <Stack direction="row" spacing={1.5} sx={{ mb: 1.5, flexWrap: 'wrap', rowGap: 1.5, alignItems: 'flex-start' }} className="no-print">
                {picked.params.map((p) => (
                  <TextField key={p.name} size="small" label={p.label} type={p.type === 'date' ? 'date' : 'text'} value={values[p.name] ?? ''} onChange={(e) => setValues({ ...values, [p.name]: e.target.value })}
                    slotProps={p.type === 'date' ? { inputLabel: { shrink: true } } : undefined} sx={{ width: p.type === 'date' ? 170 : 220 }} />
                ))}
                <Button variant="contained" sx={{ mt: 0.25 }} disabled={run.isPending} onClick={() => { setError(null); run.mutate() }}>Run</Button>
                {run.data && <Button sx={{ mt: 0.25 }} onClick={() => { download(`${picked.id}.csv`, toCsv(run.data)); void logExport('REPORT', picked.id, run.data.table.rows.length) }}>Save as CSV</Button>}
                {run.data && <Button sx={{ mt: 0.25 }} onClick={() => window.print()}>Print</Button>}
              </Stack>
              {error && <Alert severity="error" sx={{ mb: 1 }} onClose={() => setError(null)}>{error}</Alert>}
              {run.isPending && <><Skeleton variant="rounded" height={120} /><Typography sx={{ fontSize: 12, mt: 1 }} color="text.secondary">Loan reports read the whole loan ledger and can take 10 to 30 seconds.</Typography></>}
              {run.data && <ResultTable r={run.data} />}
            </>
          )}
        </Box>
      </Box>
    </Box>
  )
}
