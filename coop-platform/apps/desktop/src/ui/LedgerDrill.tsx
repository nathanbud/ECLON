import { Box, Button, Dialog, DialogActions, DialogContent, DialogTitle, Table, TableBody, TableCell, TableFooter, TableHead, TableRow, Typography } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { runReport, type ReportTable } from '../api'
import { money } from './format'
import { LoadingBlock } from './Loading'

/** What a drill-down opens: an account and the period whose entries make up the figure clicked. */
export interface Drill { account: string; name?: string | null; from: string; to: string }

/**
 * The entries behind a figure: one account's opening balance, its postings in the period and the balance after each (the General ledger report). Opened from
 * the trial balance and the statements, so a figure can be traced to its entries.
 */
export function LedgerDrill({ drill, onClose }: { drill: Drill; onClose: () => void }) {
  const q = useQuery({ queryKey: ['ledger-drill', drill], queryFn: () => runReport('general-ledger', { account: drill.account, from: drill.from, to: drill.to }), retry: false })
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="md">
      <DialogTitle>
        {drill.account} {drill.name ?? ''}
        <Typography component="div" color="text.secondary">Entries from {drill.from} to {drill.to}</Typography>
      </DialogTitle>
      <DialogContent>
        <LoadingBlock q={q} what="the entries" />
        {q.data && <ReportGrid t={q.data.table} label={`Entries of ${drill.account}`} />}
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Close</Button></DialogActions>
    </Dialog>
  )
}

const cell = (v: string | number | null, type: string): ReactNode => (v == null || v === '' ? '' : type === 'money' && typeof v === 'number' ? money(v) : String(v))

function ReportGrid({ t, label }: { t: ReportTable; label: string }) {
  return (
    <Box>
      <Table size="small" aria-label={label}>
        <TableHead>
          <TableRow>{t.columns.map((c) => <TableCell key={c.key} align={c.type === 'money' || c.type === 'number' ? 'right' : 'left'}>{c.label}</TableCell>)}</TableRow>
        </TableHead>
        <TableBody>
          {t.rows.map((r, i) => (
            <TableRow key={i} hover>{r.map((v, j) => <TableCell key={j} align={t.columns[j]?.type === 'money' ? 'right' : 'left'} sx={{ whiteSpace: 'nowrap' }}>{cell(v, t.columns[j]?.type)}</TableCell>)}</TableRow>
          ))}
        </TableBody>
        {t.totals && (
          <TableFooter>
            <TableRow>{t.totals.map((v, j) => <TableCell key={j} align={t.columns[j]?.type === 'money' ? 'right' : 'left'} sx={{ fontWeight: 700 }}>{cell(v, t.columns[j]?.type)}</TableCell>)}</TableRow>
          </TableFooter>
        )}
      </Table>
      {t.note && <Typography variant="body2" sx={{ mt: 1 }} color="text.secondary">{t.note}</Typography>}
    </Box>
  )
}

/** An account code shown as a button that opens its entries. */
export function DrillCode({ code, onOpen }: { code: string; onOpen: () => void }) {
  return (
    <Box component="button" type="button" onClick={onOpen} aria-label={`Entries of ${code}`}
      sx={{ all: 'unset', cursor: 'pointer', color: 'primary.main', fontVariantNumeric: 'tabular-nums', '&:hover': { textDecoration: 'underline' }, '&:focus-visible': { outline: '2px solid', outlineColor: 'primary.main', borderRadius: '3px' } }}>
      {code}
    </Box>
  )
}
