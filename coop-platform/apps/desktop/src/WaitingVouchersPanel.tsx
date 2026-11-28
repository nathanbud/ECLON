import { Alert, Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle, Table, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { approveWaitingVoucher, getWaitingVoucher, getWaitingVouchers, postWaitingVoucher, type WaitingVoucher } from './api'
import { glass } from './glass'
import { ConfirmButton } from './ui/dialogs'
import { money } from './ui/format'
import { LoadingBlock } from './ui/Loading'
import { notifyError } from './ui/notices'

/**
 * Cash Vouchers and Check Vouchers made from front office drafts (and any the old system left waiting) that are not loan releases: someone other than
 * the encoder approves, and a third person posts. A posted Cash Voucher's payee is then paid on Pay out vouchers payable; a Check Voucher's cheque is
 * written when it is posted.
 */
export function WaitingVouchersPanel() {
  const list = useQuery({ queryKey: ['waiting-vouchers'], queryFn: getWaitingVouchers, refetchInterval: 30_000, retry: false })
  const [open, setOpen] = useState<WaitingVoucher | null>(null)
  if (list.error && /cannot look up/i.test((list.error as Error).message)) return null // no right to vouchers
  const rows = list.data ?? []
  return (
    <Box sx={(t) => ({ p: 1.5, flexShrink: 0, maxHeight: '40%', overflow: 'auto', ...glass(t) })}>
      <Typography variant="overline" sx={{ display: 'block', mb: 0.75 }} color="text.secondary">Vouchers waiting</Typography>
      <LoadingBlock q={list} what="the vouchers waiting" />
      {!list.isLoading && !list.error && rows.length === 0 && (
        <Typography sx={{ py: 1 }} color="text.secondary">No Cash or Check Voucher is waiting. Make one as a draft on this tab (Cash Voucher or Check Voucher), save it, then Post.</Typography>
      )}
      {rows.map((v) => (
        <Box key={`${v.code}-${v.year}-${v.controlNo}`} sx={{ display: 'flex', alignItems: 'center', gap: 1.5, py: 0.9, borderTop: '1px solid', borderColor: 'divider' }}>
          <Box sx={{ flex: 1, minWidth: 0 }}>
            <Typography>{v.type} {v.documentNo.trim()} · {money(v.amount)}{v.clientName ? ` · ${v.clientName}` : ''}</Typography>
            <Typography variant="body2" color="text.secondary" noWrap>
              {v.date} · encoded by {v.encodedByName ?? `#${v.encodedBy}`}{v.approvedByName ? ` · approved by ${v.approvedByName}` : ''}{v.explanation ? ` · ${v.explanation}` : ''}
            </Typography>
          </Box>
          <Chip size="small" variant="outlined" color={v.status === 'APPROVED' ? 'info' : 'warning'} label={v.status === 'APPROVED' ? 'Approved, to post' : 'To approve'} />
          <Button size="small" variant="outlined" onClick={() => setOpen(v)}>Open</Button>
        </Box>
      ))}
      {open && <VoucherDialog v={open} onClose={() => setOpen(null)} />}
    </Box>
  )
}

function VoucherDialog({ v, onClose }: { v: WaitingVoucher; onClose: () => void }) {
  const qc = useQueryClient()
  const detail = useQuery({ queryKey: ['waiting-voucher', v.code, v.year, v.controlNo], queryFn: () => getWaitingVoucher(v.code, v.year, v.controlNo), retry: false })
  const done = () => { void qc.invalidateQueries({ queryKey: ['waiting-vouchers'] }); void qc.invalidateQueries({ queryKey: ['waiting-voucher'] }); void qc.invalidateQueries({ queryKey: ['inbox'] }) }
  const approve = useMutation({ mutationFn: () => approveWaitingVoucher(v.code, v.year, v.controlNo), onSuccess: done, onError: notifyError, meta: { done: `${v.type} ${v.documentNo.trim()} approved` } })
  const post = useMutation({ mutationFn: () => postWaitingVoucher(v.code, v.year, v.controlNo), onSuccess: () => { done(); onClose() }, onError: notifyError, meta: { done: `${v.type} ${v.documentNo.trim()} posted` } })
  const d = detail.data
  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth aria-labelledby="voucher-title">
      <DialogTitle id="voucher-title">{v.type} {v.documentNo.trim()}</DialogTitle>
      <DialogContent>
        <LoadingBlock q={detail} what="the voucher" />
        {d && (
          <Box sx={{ display: 'grid', gap: 1.25 }}>
            <Typography>
              {d.voucher.date} · {d.voucher.clientName ?? (d.voucher.clientId ? `Member ${d.voucher.clientId}` : 'No member')} · {money(d.voucher.amount)}
            </Typography>
            {d.voucher.explanation && <Typography color="text.secondary">{d.voucher.explanation}</Typography>}
            {d.voucher.cheque && (
              <Typography>Cheque {d.voucher.cheque.number} of {d.voucher.cheque.bank}, dated {d.voucher.cheque.date}, pay to {d.voucher.cheque.payee}</Typography>
            )}
            <Table size="small" aria-label="Voucher entries">
              <TableHead><TableRow><TableCell>Account</TableCell><TableCell>Description</TableCell><TableCell align="right">Debit</TableCell><TableCell align="right">Credit</TableCell></TableRow></TableHead>
              <TableBody>
                {d.lines.map((l) => (
                  <TableRow key={l.seq}>
                    <TableCell sx={{ fontVariantNumeric: 'tabular-nums' }}>{l.account}</TableCell>
                    <TableCell>{l.description}{l.refNo ? ` · account ${l.refNo}` : ''}</TableCell>
                    <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>{l.amount > 0 ? money(l.amount) : ''}</TableCell>
                    <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>{l.amount < 0 ? money(-l.amount) : ''}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
            <Typography variant="body2" color="text.secondary">
              Encoded by {d.voucher.encodedByName ?? `#${d.voucher.encodedBy}`}{d.voucher.approvedByName ? `, approved by ${d.voucher.approvedByName}` : ''}. The person who encoded or approved it cannot post it.
            </Typography>
            {d.problems.map((p) => <Alert key={p} severity="error">{p}</Alert>)}
            {!d.mayApprove && !d.mayPost && d.problems.length === 0 && (
              <Alert severity="info">{d.voucher.status === 'WAITING FOR APPROVAL' ? 'Someone other than the encoder approves it.' : 'Someone other than the encoder and the approver posts it.'}</Alert>
            )}
          </Box>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Close</Button>
        {d?.mayApprove && (
          <ConfirmButton variant="contained" disabled={approve.isPending} title="Approve this voucher?"
            message={`${v.type} ${v.documentNo.trim()} of ${money(v.amount)} is approved by you. A third person then posts it.`} onConfirm={() => approve.mutate()}>
            Approve
          </ConfirmButton>
        )}
        {d?.mayPost && (
          <ConfirmButton variant="contained" color="success" disabled={post.isPending} title="Post this voucher?"
            message={`${v.type} ${v.documentNo.trim()} of ${money(v.amount)} is posted to the ledger, dated the open business day${v.code === 22 ? ', with its cheque' : ''}. It cannot be edited afterwards.`}
            onConfirm={() => post.mutate()}>
            Post
          </ConfirmButton>
        )}
      </DialogActions>
    </Dialog>
  )
}
