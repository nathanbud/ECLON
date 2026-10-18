import { Alert, Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle, Skeleton, Table, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { approveLoanRelease, getLoanRelease, getLoanReleases, postLoanRelease, type LoanReleaseVoucher } from './api'
import { money } from './FoTransactionForm'
import { glass } from './glass'

const statusColor = (s: LoanReleaseVoucher['status']) => (s === 'POSTED' ? 'success' : s === 'APPROVED' ? 'info' : 'warning')

/** Loan release vouchers the old system encoded: approve one, then a third person posts it. The server decides who may do which. */
export function LoanReleasePanel() {
  const qc = useQueryClient()
  const list = useQuery({ queryKey: ['loan-releases'], queryFn: getLoanReleases, refetchInterval: 30_000, retry: false })
  const [open, setOpen] = useState<LoanReleaseVoucher | null>(null)
  const [error, setError] = useState<string | null>(null)
  const detail = useQuery({ queryKey: ['loan-release', open?.year, open?.controlNo], queryFn: () => getLoanRelease(open!.year, open!.controlNo), enabled: !!open })
  const act = useMutation({
    mutationFn: (v: { kind: 'approve' | 'post'; r: LoanReleaseVoucher }) => (v.kind === 'approve' ? approveLoanRelease : postLoanRelease)(v.r.year, v.r.controlNo),
    onSuccess: (d) => { setError(null); setOpen(d.voucher); void qc.invalidateQueries({ queryKey: ['loan-releases'] }); void qc.invalidateQueries({ queryKey: ['loan-release'] }) },
    onError: (e) => setError((e as Error).message),
  })

  if (list.isLoading) return <Skeleton variant="rounded" height={120} />
  if (list.error) return null // no right to see loan releases
  const rows = list.data ?? []
  const v = detail.data?.voucher ?? open
  return (
    <Box sx={(t) => ({ p: 1.5, flex: 1, minHeight: 0, overflow: 'auto', ...glass(t) })}>
      <Typography sx={{ fontSize: 12, fontWeight: 700, letterSpacing: '0.06em', textTransform: 'uppercase', mb: 0.75 }} color="text.secondary">Loan releases waiting</Typography>
      {rows.length === 0 && <Typography sx={{ py: 2 }} color="text.secondary">No release vouchers are waiting.</Typography>}
      {rows.map((r) => (
        <Box key={`${r.year}-${r.controlNo}`} onClick={() => { setOpen(r); setError(null) }} role="button" tabIndex={0}
          onKeyDown={(e) => { if (e.key === 'Enter') { setOpen(r); setError(null) } }}
          sx={{ display: 'flex', alignItems: 'center', gap: 1.5, py: 0.9, borderTop: '1px solid', borderColor: 'divider', cursor: 'pointer', '&:hover': { bgcolor: 'action.hover' } }}>
          <Box sx={{ flex: 1, minWidth: 0 }}>
            <Typography sx={{ fontSize: 14 }}>{r.clientName ?? `Client ${r.clientId}`} · {money(r.loanAmount)}</Typography>
            <Typography sx={{ fontSize: 12 }} color="text.secondary" noWrap>{r.documentNo} · {r.date} · {r.explanation}</Typography>
          </Box>
          <Chip size="small" variant="outlined" color={statusColor(r.status)} label={r.status === 'WAITING FOR APPROVAL' ? 'Waiting for approval' : r.status === 'APPROVED' ? 'Approved' : 'Posted'} />
        </Box>
      ))}
      <Dialog open={!!open} onClose={() => setOpen(null)} fullWidth maxWidth="md">
        <DialogTitle>Loan release {v?.documentNo}</DialogTitle>
        <DialogContent>
          {v && (
            <Typography sx={{ fontSize: 13, mb: 1 }} color="text.secondary">
              {v.clientName ?? `Client ${v.clientId}`} · dated {v.date} · encoded by {v.encodedByName ?? v.encodedBy}{v.approvedByName ? ` · approved by ${v.approvedByName}` : ''}
            </Typography>
          )}
          {error && <Alert severity="error" sx={{ mb: 1 }} onClose={() => setError(null)}>{error}</Alert>}
          {detail.data?.problems.map((p) => <Alert key={p} severity="warning" sx={{ mb: 1 }}>{p}</Alert>)}
          {detail.isLoading ? <Skeleton variant="rounded" height={140} /> : (
            <Table size="small">
              <TableHead><TableRow><TableCell>Account</TableCell><TableCell>Ledger</TableCell><TableCell align="right">Debit</TableCell><TableCell align="right">Credit</TableCell></TableRow></TableHead>
              <TableBody>
                {detail.data?.lines.map((l) => (
                  <TableRow key={l.seq}>
                    <TableCell>{l.account}</TableCell>
                    <TableCell>{l.slc ? `${l.slc}/${l.slt}/${l.refNo} (event ${l.event})` : '—'}</TableCell>
                    <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>{l.amount > 0 ? money(l.amount) : ''}</TableCell>
                    <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>{l.amount < 0 ? money(-l.amount) : ''}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
          <Typography sx={{ fontSize: 12, mt: 1 }} color="text.secondary">
            The person who encoded a voucher cannot approve it, and the approver cannot post it. Posting writes the loan ledger entries dated today.
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setOpen(null)}>Close</Button>
          {v?.status === 'WAITING FOR APPROVAL' && <Button variant="contained" disabled={act.isPending || !!detail.data?.problems.length} onClick={() => act.mutate({ kind: 'approve', r: v })}>Approve</Button>}
          {v?.status === 'APPROVED' && <Button variant="contained" color="success" disabled={act.isPending || !!detail.data?.problems.length} onClick={() => act.mutate({ kind: 'post', r: v })}>Post release</Button>}
        </DialogActions>
      </Dialog>
    </Box>
  )
}
