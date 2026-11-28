import { Alert, Box, Button, Dialog, DialogActions, DialogContent, DialogTitle, MenuItem, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography } from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { approveLoanApplication, cancelLoanApplication, disapproveLoanApplication, getLoanAppPermissions, getLoanApplication, getLoanApplications, verifyLoanApplication, type LoanApplicationItem } from './api'
import { openLoanApplicationWindow } from './detachedWindow'
import { money } from './FoTransactionForm'
import { Segmented } from './appleUi'
import { glass } from './glass'
import { FILTERS, type LoanFilter } from './LoanMenu'
import { LoanStepStatus } from './LoanStepStatus'
import { notifyError } from './ui/notices'
import { LoadingBlock } from './ui/Loading'
import { APPROVED, isReleased, LOAN_SORTS, releaseWords, sortApplications, type LoanSort } from './loanSteps'

const STALE_DAYS = 60

/**
 * Loan applications waiting for their deductions, to be verified or approved (Loan Module). Approving creates the release voucher, which is released in
 * Front Office > Disburse, "Loan releases waiting".
 * The server decides who may do which: the encoder cannot verify or approve their own application, the verifier cannot approve it, unless a
 * system administrator has let that person work alone.
 */
/** With `onFilter`, a row of step buttons (All, Deduction, Verification, Approval, each with its count) narrows the list. */
export function LoanApplicationsPanel({ full, filter = 'all', onShowAll, onFilter }: { full?: boolean; filter?: LoanFilter; onShowAll?: () => void; onFilter?: (f: LoanFilter) => void } = {}) {
  const qc = useQueryClient()
  const list = useQuery({ queryKey: ['loan-applications'], queryFn: getLoanApplications, refetchInterval: 30_000, retry: false })
  const perms = useQuery({ queryKey: ['loan-app-permissions'], queryFn: getLoanAppPermissions, staleTime: 60_000 })
  const [open, setOpen] = useState<LoanApplicationItem | null>(null)
  const [note, setNote] = useState<string | null>(null)
  const [cancelling, setCancelling] = useState(false)
  const [disapproving, setDisapproving] = useState(false)
  const [showOlder, setShowOlder] = useState(false)
  const [reason, setReason] = useState('')
  // Always opens newest first; another order lasts while the screen is open.
  const [sort, setSort] = useState<LoanSort>('date-desc')
  // an approved one is asked again every 30 seconds until released, so its release shows while it is open
  const detail = useQuery({ queryKey: ['loan-application', open?.id], queryFn: () => getLoanApplication(open!.id), enabled: !!open,
    refetchInterval: (q) => (q.state.data?.item.status === APPROVED && !isReleased(q.state.data.item) ? 30_000 : false) })
  const refresh = () => { void qc.invalidateQueries({ queryKey: ['loan-applications'] }); void qc.invalidateQueries({ queryKey: ['loan-application'] }); void qc.invalidateQueries({ queryKey: ['loan-releases'] }) }
  const verify = useMutation({ mutationFn: (a: LoanApplicationItem) => verifyLoanApplication(a.id), onSuccess: (a) => { setOpen(a); refresh() }, onError: (e) => notifyError((e as Error).message) })
  const cancel = useMutation({
    mutationFn: (a: LoanApplicationItem) => cancelLoanApplication(a.id, reason.trim()),
    onSuccess: () => { setOpen(null); setCancelling(false); setReason(''); setNote('The application was cancelled.'); refresh() },
    onError: (e) => notifyError((e as Error).message),
  })
  const disapprove = useMutation({
    mutationFn: (a: LoanApplicationItem) => disapproveLoanApplication(a.id, reason.trim()),
    onSuccess: () => { setOpen(null); setDisapproving(false); setReason(''); setNote('The application was disapproved.'); refresh() },
    onError: (e) => notifyError((e as Error).message),
  })
  const approve = useMutation({
    mutationFn: (a: LoanApplicationItem) => approveLoanApplication(a.id),
    onSuccess: (r) => { setOpen(null); setNote(`Approved. Release voucher ${r.voucherDocumentNo} was created; the money is released in Front Office > Disburse, Loan releases waiting.`); refresh() },
    onError: (e) => notifyError((e as Error).message),
  })

  if (list.isLoading) return <LoadingBlock q={list} what="the loan applications waiting" />
  if (list.error) return null // no right to verify or approve loan applications
  const step = FILTERS.find((f) => f.key === filter) ?? FILTERS[0]
  const all = (list.data ?? []).filter(step.test)
  const cutoff = new Date(Date.now() - STALE_DAYS * 86_400_000).toISOString().slice(0, 10)
  // an application counts as recent by its last move: released, approved, or else the day it was saved
  const lastMove = (r: LoanApplicationItem) => r.releasedOn ?? r.approvedOn ?? r.date
  const older = all.filter((r) => lastMove(r) < cutoff).length
  const rows = sortApplications(showOlder ? all : all.filter((r) => lastMove(r) >= cutoff), sort)
  const a = detail.data?.item ?? open
  const busy = verify.isPending || approve.isPending || cancel.isPending || disapprove.isPending
  return (
    <Box sx={(t) => ({ p: 1.5, mb: 1.5, ...(full ? {} : { maxHeight: 240, overflow: 'auto' }), ...glass(t) })}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 0.75 }}>
        <Typography variant="overline" sx={{ display: 'block', flex: 1 }} color="text.secondary">
          Loan applications waiting{filter !== 'all' ? ` · ${step.label}` : ''}
        </Typography>
        {filter !== 'all' && onShowAll && !onFilter && <Button size="small" onClick={onShowAll}>Show all waiting</Button>}
      </Box>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, mb: 1, flexWrap: 'wrap' }}>
        {onFilter && (
          <Segmented label="Show applications at step" value={filter} onChange={onFilter}
            options={FILTERS.map((f) => ({ value: f.key, label: `${f.step} (${(list.data ?? []).filter(f.test).length})` }))} />
        )}
        <TextField select size="small" label="Sort by" value={sort} onChange={(e) => setSort(e.target.value as LoanSort)} sx={{ width: 200, ml: 'auto' }}>
          {LOAN_SORTS.map((o) => <MenuItem key={o.value} value={o.value}>{o.label}</MenuItem>)}
        </TextField>
      </Box>
      {note && <Alert severity="success" sx={{ mb: 1 }} onClose={() => setNote(null)}>{note}</Alert>}
      {rows.length === 0 && <Typography sx={{ py: 1 }} color="text.secondary">{filter === 'all' ? 'No recent loan applications are waiting.' : `No recent applications in ${step.label}.`}</Typography>}
      {older > 0 && (
        <Box sx={{ py: 0.5 }}>
          <Button size="small" onClick={() => setShowOlder((v) => !v)}>{showOlder ? `Hide ${older} older than ${STALE_DAYS} days` : `Show ${older} older than ${STALE_DAYS} days`}</Button>
        </Box>
      )}
      {rows.map((r) => (
        <Box key={r.id} onClick={() => { setOpen(r); setCancelling(false); setDisapproving(false) }} role="button" tabIndex={0} onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); setOpen(r) } }}
          sx={{ display: 'flex', alignItems: 'center', gap: 1.5, py: 0.9, borderTop: '1px solid', borderColor: 'divider', cursor: 'pointer', '&:hover': { bgcolor: 'action.hover' }, '&:focus-visible': { outline: '2px solid', outlineColor: 'primary.main', outlineOffset: -2, bgcolor: 'action.hover' } }}>
          <Box sx={{ flex: 1, minWidth: 0 }}>
            <Typography>{r.clientName ?? `Member ${r.clientId}`} · {money(r.amount)}</Typography>
            <Typography variant="body2" color="text.secondary" noWrap>{r.documentNo} · {r.typeName} · {r.terms} months · {r.date} · encoded by {r.encodedByName ?? '—'}{r.approvedOn ? ` · approved ${r.approvedOn}` : ''}{r.releasedOn ? ` · released ${r.releasedOn}` : ''}</Typography>
          </Box>
          <LoanStepStatus application={r} />
        </Box>
      ))}
      <Dialog open={!!open} onClose={() => setOpen(null)} fullWidth maxWidth="md">
        <DialogTitle>Loan application {a?.documentNo}</DialogTitle>
        <DialogContent>
          {a && (
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, mb: 1, flexWrap: 'wrap' }}>
              <Typography sx={{ flex: 1, minWidth: 240 }} color="text.secondary">{a.clientName ?? `Member ${a.clientId}`} · {a.typeName} · {money(a.amount)} over {a.terms} months · loan ref {a.refNo} · {a.statusName}</Typography>
              <LoanStepStatus application={a} />
            </Box>
          )}
          {detail.data?.problems.map((p) => <Alert key={p} severity="warning" sx={{ mb: 1 }}>{p}</Alert>)}
          {detail.isLoading || detail.isError ? <LoadingBlock q={detail} what="the release voucher preview" /> : (
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
          {a && releaseWords(a) && <Alert severity={isReleased(a) ? 'success' : 'info'} sx={{ mt: 1.5 }}>{releaseWords(a)}.</Alert>}
          {a?.waitingForDeductions && <Alert severity="info" sx={{ mt: 1.5 }}>This application waits for its deductions. It can be verified once someone who may do the Deduction step fills them in.</Alert>}
          {disapproving && <TextField label="Reason for disapproving" value={reason} onChange={(e) => setReason(e.target.value)} fullWidth size="small" sx={{ mt: 1.5 }} autoFocus />}
          {cancelling && <TextField label="Reason for cancelling" value={reason} onChange={(e) => setReason(e.target.value)} fullWidth size="small" sx={{ mt: 1.5 }} autoFocus />}
          <Typography variant="body2" sx={{ mt: 1 }} color="text.secondary">
            {a?.status === APPROVED
              ? 'This is the release voucher. The money is released in Front Office > Disburse, Loan releases waiting: the voucher is approved by one person and posted by another.'
              : 'This is the voucher approval will create. You cannot verify or approve an application you encoded, and the verifier cannot approve it.'}
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setOpen(null)}>Close</Button>
          {a && <Button onClick={() => { if (openLoanApplicationWindow({ applicationId: a.id })) setOpen(null) }}>Open step by step</Button>}
          {a && !isReleased(a) && !cancelling && !disapproving && <Button color="error" disabled={busy} onClick={() => setCancelling(true)}>Cancel application</Button>}
          {a && !disapproving && !cancelling && (a.status === 1 || a.status === 2) && <Button color="error" disabled={busy} onClick={() => setDisapproving(true)}>Disapprove</Button>}
          {a && disapproving && <Button color="error" variant="outlined" disabled={busy || !reason.trim()} onClick={() => disapprove.mutate(a)}>Confirm disapprove</Button>}
          {a && cancelling && <Button color="error" variant="outlined" disabled={busy || !reason.trim()} onClick={() => cancel.mutate(a)}>Confirm cancel</Button>}
          {a?.waitingForDeductions && perms.data?.canDeduct && (
            <Button variant="contained" onClick={() => { if (openLoanApplicationWindow({ branchCode: a.branchCode, clientId: a.clientId, applicationId: a.id })) setOpen(null) }}>Do the deductions</Button>
          )}
          {a?.status === 1 && !a.waitingForDeductions && <Button disabled={busy} onClick={() => verify.mutate(a)}>Verify</Button>}
          {a && (a.status === 1 || a.status === 2) && !a.waitingForDeductions && <Button variant="contained" disabled={busy || !!detail.data?.problems.length} onClick={() => approve.mutate(a)}>Approve</Button>}
        </DialogActions>
      </Dialog>
    </Box>
  )
}
