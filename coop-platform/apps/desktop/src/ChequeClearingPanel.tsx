import { Alert, Box, Button, Checkbox, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography } from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { clearCheques, getChequeClearing } from './api'
import { money } from './FoTransactionForm'
import { glass } from './glass'

/**
 * Cheques taken at the window that are not cleared yet. The head cashier (or an accountant or administrator) marks the ones the bank has
 * honoured; cheques not yet due can be cleared early with a reason. Hidden for people who may not clear.
 */
export function ChequeClearingPanel() {
  const qc = useQueryClient()
  const list = useQuery({ queryKey: ['cheque-clearing'], queryFn: getChequeClearing, retry: false })
  const [picked, setPicked] = useState<Set<string>>(new Set())
  const [reason, setReason] = useState('')
  const [note, setNote] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const key = (r: { year: number; ctlNo: number; seq: number }) => `${r.year}/${r.ctlNo}/${r.seq}`
  const rows = list.data ?? []
  const chosen = rows.filter((r) => picked.has(key(r)))
  const early = chosen.some((r) => !r.due)
  const run = useMutation({
    mutationFn: () => clearCheques(chosen.map((r) => ({ year: r.year, ctlNo: r.ctlNo, seq: r.seq })), reason),
    onSuccess: (r) => {
      setError(null); setPicked(new Set()); setReason(''); setNote(`${r.cleared} cheque${r.cleared === 1 ? '' : 's'} cleared.${r.skipped ? ` ${r.skipped} skipped (already cleared).` : ''}`)
      void qc.invalidateQueries({ queryKey: ['cheque-clearing'] })
    },
    onError: (e) => setError((e as Error).message),
  })
  if (list.isError || list.isLoading) return null
  const toggle = (r: (typeof rows)[number]) => setPicked((p) => { const n = new Set(p); if (n.has(key(r))) n.delete(key(r)); else n.add(key(r)); return n })
  const total = chosen.reduce((t, r) => t + r.amount, 0)
  return (
    <Box sx={(t) => ({ p: 1.5, mt: 1.5, ...glass(t) })}>
      <Typography sx={{ fontSize: 12, fontWeight: 700, letterSpacing: '0.06em', textTransform: 'uppercase', mb: 0.75 }} color="text.secondary">Cheque clearing</Typography>
      {note && <Alert severity="success" sx={{ mb: 1 }} onClose={() => setNote(null)}>{note}</Alert>}
      {error && <Alert severity="error" sx={{ mb: 1 }} onClose={() => setError(null)}>{error}</Alert>}
      {rows.length === 0 ? (
        <Typography sx={{ fontSize: 13.5 }} color="text.secondary">No cheques are waiting to clear.</Typography>
      ) : (
        <>
          <Box sx={{ maxHeight: 320, overflow: 'auto' }}>
            <Table size="small" stickyHeader aria-label="Cheques not yet cleared">
              <TableHead><TableRow>
                <TableCell padding="checkbox" />
                <TableCell>Received</TableCell><TableCell>Receipt</TableCell><TableCell>Member</TableCell><TableCell>Cheque</TableCell><TableCell>Bank</TableCell>
                <TableCell align="right">Amount</TableCell><TableCell>Clears on</TableCell>
              </TableRow></TableHead>
              <TableBody>
                {rows.map((r) => (
                  <TableRow key={key(r)} hover selected={picked.has(key(r))} onClick={() => toggle(r)} sx={{ cursor: 'pointer' }}>
                    <TableCell padding="checkbox"><Checkbox size="small" checked={picked.has(key(r))} slotProps={{ input: { 'aria-label': `Clear cheque ${r.number}` } }} /></TableCell>
                    <TableCell>{r.receiptDate}</TableCell>
                    <TableCell>{r.documentNo}</TableCell>
                    <TableCell>{r.client ?? ''}</TableCell>
                    <TableCell>{r.number}{r.kind ? ` · ${r.kind}` : ''}</TableCell>
                    <TableCell>{r.bank ?? ''}</TableCell>
                    <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>{money(r.amount)}</TableCell>
                    <TableCell sx={{ color: r.due ? 'success.main' : 'text.secondary' }}>{r.due ? 'Due' : r.clearsOn}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </Box>
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, mt: 1.25, flexWrap: 'wrap' }}>
            <Button size="small" onClick={() => setPicked(new Set(rows.filter((r) => r.due).map(key)))}>Choose all due</Button>
            <Typography sx={{ fontSize: 13.5 }}>{chosen.length} chosen · <b style={{ fontVariantNumeric: 'tabular-nums' }}>{money(total)}</b></Typography>
            <Box sx={{ flex: 1 }} />
            {early && <TextField size="small" label="Reason for clearing early" value={reason} onChange={(e) => setReason(e.target.value)} sx={{ minWidth: 260 }} />}
            <Button variant="contained" disabled={chosen.length === 0 || run.isPending || (early && reason.trim().length < 5)} onClick={() => run.mutate()}>
              {run.isPending ? 'Clearing…' : 'Mark cleared'}
            </Button>
          </Box>
        </>
      )}
    </Box>
  )
}
