import { Alert, Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle, Skeleton, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography } from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { approveLoanApplication, cancelLoanApplication, getLoanApplication, getLoanApplications, verifyLoanApplication, type LoanApplicationItem } from './api'
import { money } from './FoTransactionForm'
import { glass } from './glass'

const STALE_DAYS = 60

/**
 * Loan applications waiting to be verified or approved. Approving creates the release voucher, which then shows in "Loan releases waiting" below.
 * The server decides who may do which: the encoder cannot verify or approve their own application, the verifier cannot approve it, unless a
 * system administrator has let that person work alone.
 */
export function LoanApplicationsPanel() {
  const qc = useQueryClient()
  const list = useQuery({ queryKey: ['loan-applications'], queryFn: getLoanApplications, refetchInterval: 30_000, retry: false })
  const [open, setOpen] = useState<LoanApplicationItem | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [note, setNote] = useState<string | null>(null)
  const [cancelling, setCancelling] = useState(false)
  const [showOlder, setShowOlder] = useState(false)
  const [reason, setReason] = useState('')
  const detail = useQuery({ queryKey: ['loan-application', open?.id], queryFn: () => getLoanApplication(open!.id), enabled: !!open })
  const refresh = () => { void qc.invalidateQueries({ queryKey: ['loan-applications'] }); void qc.invalidateQueries({ queryKey: ['loan-application'] }); void qc.invalidateQueries({ queryKey: ['loan-releases'] }) }
  const verify = useMutation({ mutationFn: (a: LoanApplicationItem) => verifyLoanApplication(a.id), onSuccess: (a) => { setError(null); setOpen(a); refresh() }, onError: (e) => setError((e as Error).message) })
  const cancel = useMutation({
    mutationFn: (a: LoanApplicationItem) => cancelLoanApplication(a.id, reason.trim()),
    onSuccess: () => { setError(null); setOpen(null); setCancelling(false); setReason(''); setNote('The application was cancelled.'); refresh() },
    onError: (e) => setError((e as Error).message),
  })
  const approve = useMutation({
    mutationFn: (a: LoanApplicationItem) => approveLoanApplication(a.id),
    onSuccess: (r) => { setError(null); setOpen(null); setNote(`Approved. Release voucher ${r.voucherDocumentNo} was created and waits below for approval and posting.`); refresh() },
    onError: (e) => setError((e as Error).message),
  })

  if (list.isLoading) return <Skeleton variant="rounded" height={120} />
  if (list.error) return null // no right to verify or approve loan applications
  const all = list.data ?? []
  const cutoff = new Date(Date.now() - STALE_DAYS * 86_400_000).toISOString().slice(0, 10)
  const older = all.filter((r) => r.date < cutoff).length
  const rows = showOlder ? all : all.filter((r) => r.date >= cutoff)
  const a = detail.data?.item ?? open
  const busy = verify.isPending || approve.isPending || cancel.isPending
  return (
    <Box sx={(t) => ({ p: 1.5, mb: 1.5, maxHeight: 240, overflow: 'auto', ...glass(t) })}>
      <Typography sx={{ fontSize: 12, fontWeight: 700, letterSpacing: '0.06em', textTransform: 'uppercase', mb: 0.75 }} color="text.secondary">Loan applications waiting</Typography>
      {note && <Alert severity="success" sx={{ mb: 1 }} onClose={() => setNote(null)}>{note}</Alert>}
      {rows.length === 0 && <Typography sx={{ py: 1 }} color="text.secondary">No recent loan applications are waiting.</Typography>}
      {older > 0 && (
        <Box sx={{ py: 0.5 }}>
          <Button size="small" onClick={() => setShowOlder((v) => !v)}>{showOlder ? `Hide ${older} older than ${STALE_DAYS} days` : `Show ${older} older than ${STALE_DAYS} days`}</Button>
        </Box>
      )}
      {rows.map((r) => (
        <Box key={r.id} onClick={() => { setOpen(r); setError(null); setCancelling(false) }} role="button" tabIndex={0} onKeyDown={(e) => { if (e.key === 'Enter') { setOpen(r); setError(null) } }}
          sx={{ display: 'flex', alignItems: 'center', gap: 1.5, py: 0.9, borderTop: '1px solid', borderColor: 'divider', cursor: 'pointer', '&:hover': { bgcolor: 'action.hover' } }}>
          <Box sx={{ flex: 1, minWidth: 0 }}>
            <Typography sx={{ fontSize: 14 }}>{r.clientName ?? `Client ${r.clientId}`} · {money(r.amount)}</Typography>
            <Typography sx={{ fontSize: 12 }} color="text.secondary" noWrap>{r.documentNo} · {r.typeName} · {r.terms} months · {r.date} · encoded by {r.encodedByName ?? '—'}</Typography>
          </Box>
          <Chip size="small" variant="outlined" color={r.status === 2 ? 'info' : 'warning'} label={r.statusName} />
        </Box>
      ))}
      <Dialog open={!!open} onClose={() => setOpen(null)} fullWidth maxWidth="md">
        <DialogTitle>Loan application {a?.documentNo}</DialogTitle>
        <DialogContent>
          {a && <Typography sx={{ fontSize: 13, mb: 1 }} color="text.secondary">{a.clientName ?? `Client ${a.clientId}`} · {a.typeName} · {money(a.amount)} over {a.terms} months · loan ref {a.refNo} · {a.statusName}</Typography>}
          {error && <Alert severity="error" sx={{ mb: 1 }} onClose={() => setError(null)}>{error}</Alert>}
          {detail.data?.problems.map((p) => <Alert key={p} severity="warning" sx={{ mb: 1 }}>{p}</Alert>)}
          {detail.isLoading ? <Skeleton variant="rounded" height={120} /> : (
            <Table size="small" aria-label="Release voucher preview">
              <TableHead><TableRow><TableCell>Line</TableCell><TableCell>Account</TableCell><TableCell align="right">Debit</TableCell><TableCell align="right">Credit</TableCell></TableRow></TableHead>
              <TableBody>
                {detail.data?.lines.map((l, i) => (
                  <TableRow key={i}>
                    <TableCell>{l.description}</TableCell><TableCell>{l.account ?? '—'}</TableCell>
                    <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>{l.amount > 0 ? money(l.amount) : ''}</TableCell>
                    <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>{l.amount < 0 ? money(-l.amount) : ''}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
          {cancelling && <TextField label="Reason for cancelling" value={reason} onChange={(e) => setReason(e.target.value)} fullWidth size="small" sx={{ mt: 1.5 }} autoFocus />}
          <Typography sx={{ fontSize: 12, mt: 1 }} color="text.secondary">
            This is the voucher approval will create. You cannot verify or approve an application you encoded, and the verifier cannot approve it.
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setOpen(null)}>Close</Button>
          {a && !cancelling && <Button color="error" disabled={busy} onClick={() => setCancelling(true)}>Cancel application</Button>}
          {a && cancelling && <Button color="error" variant="outlined" disabled={busy || !reason.trim()} onClick={() => cancel.mutate(a)}>Confirm cancel</Button>}
          {a?.status === 1 && <Button disabled={busy} onClick={() => verify.mutate(a)}>Verify</Button>}
          {a && (a.status === 1 || a.status === 2) && <Button variant="contained" disabled={busy || !!detail.data?.problems.length} onClick={() => approve.mutate(a)}>Approve</Button>}
        </DialogActions>
      </Dialog>
    </Box>
  )
}
