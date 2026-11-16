import { Alert, Box, Button, FormControlLabel, MenuItem, Skeleton, Switch, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography } from '@mui/material'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { FiPlus } from 'react-icons/fi'
import {
  addLoanDeduction, changeLoanDeduction, getLoanDeductionOptions, getLoanDeductions, removeLoanDeduction,
  type LoanDeduction, type LoanDeductionForm, type LoanDeductionKind,
} from './api'
import { tabular } from './ui/format'

type Draft = { id: number | null; description: string; order: string; kind: LoanDeductionKind | ''; member: string; event: string; account: string; hidePrinting: boolean; editableTarget: boolean }

const KINDS: { value: LoanDeductionKind; label: string }[] = [
  { value: 'MEMBER_ACCOUNT', label: "One of the member's accounts" },
  { value: 'LOAN_CHARGE', label: 'A charge of this loan' },
  { value: 'CHART_ACCOUNT', label: 'A chart account' },
]

const blank = (order: number): Draft => ({ id: null, description: '', order: String(order), kind: 'MEMBER_ACCOUNT', member: '', event: '', account: '', hidePrinting: false, editableTarget: true })
const draftOf = (l: LoanDeduction): Draft => ({
  id: l.id, description: l.description, order: String(l.order), kind: l.editableTarget ? (l.kind as LoanDeductionKind) : '',
  member: l.classId != null && l.typeCode != null ? `${l.classId}/${l.typeCode}` : '', event: l.event != null ? String(l.event) : '', account: l.account ?? '',
  hidePrinting: l.hidePrinting, editableTarget: l.editableTarget,
})

/**
 * The Deductions tab of a loan type: the lines an application of this type starts with. A line is added, changed or removed with a reason; the net proceeds line always stays
 * last. The amount of each line is typed on the application, so nothing here sets an amount.
 */
export default function LoanDeductions({ code }: { code: number }) {
  const qc = useQueryClient()
  const key = ['loan-type-deductions', code]
  const { data, error, isLoading } = useQuery({ queryKey: key, queryFn: () => getLoanDeductions(code), staleTime: 60_000 })
  const options = useQuery({ queryKey: ['loan-type-deduction-options', code], queryFn: () => getLoanDeductionOptions(code), staleTime: 60_000 })
  const [reason, setReason] = useState('')
  const [draft, setDraft] = useState<Draft | null>(null)
  const [fail, setFail] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const set = (p: Partial<Draft>) => setDraft((d) => (d ? { ...d, ...p } : d))

  const run = async (job: () => Promise<LoanDeduction[]>) => {
    if (reason.trim().length < 3) { setFail('Give a reason for the change first.'); return }
    setBusy(true)
    try {
      qc.setQueryData(key, await job())
      setFail(null); setDraft(null)
    } catch (e) { setFail((e as Error).message) } finally { setBusy(false) }
  }

  const formOf = (d: Draft): LoanDeductionForm => {
    const [cls, type] = d.member.split('/').map(Number)
    return {
      description: d.description.trim(), order: Number(d.order), kind: d.editableTarget && d.kind ? d.kind : null,
      classId: d.kind === 'MEMBER_ACCOUNT' && d.member ? cls : null, typeCode: d.kind === 'MEMBER_ACCOUNT' && d.member ? type : null,
      event: d.kind === 'LOAN_CHARGE' && d.event ? Number(d.event) : null, account: d.kind === 'CHART_ACCOUNT' ? d.account.trim() : null,
      hidePrinting: d.hidePrinting, reason: reason.trim(),
    }
  }
  const complete = (d: Draft) => d.description.trim() !== '' && (!d.editableTarget
    || (Number(d.order) >= 1 && Number(d.order) <= 98 && (d.kind === 'MEMBER_ACCOUNT' ? d.member !== '' : d.kind === 'LOAN_CHARGE' ? d.event !== '' : d.kind === 'CHART_ACCOUNT' && d.account.trim() !== '')))
  const nextOrder = Math.min(98, Math.max(0, ...(data ?? []).filter((l) => l.kind !== 'NET_PROCEEDS').map((l) => l.order)) + 1)

  return (
    <Box sx={{ mt: 1 }}>
      {error && <Alert severity="error" sx={{ py: 0 }}>{(error as Error).message}</Alert>}
      {fail && <Alert severity="error" sx={{ py: 0, mb: 1 }} onClose={() => setFail(null)}>{fail}</Alert>}
      {isLoading && <Skeleton variant="rounded" height={90} />}
      {data && data.length === 0 && <Typography sx={{ fontSize: 13 }} color="text.secondary">This loan type has no deductions: the whole amount is released to the member.</Typography>}
      {data && data.length > 0 && (
        <Box sx={{ maxHeight: 300, overflow: 'auto' }}>
          <Table size="small" stickyHeader aria-label="Deductions of this loan type" sx={{ '& td, & th': { py: 0.4 } }}>
            <TableHead><TableRow><TableCell sx={{ width: 56 }}>Order</TableCell><TableCell>Deduction</TableCell><TableCell>Goes to</TableCell><TableCell /></TableRow></TableHead>
            <TableBody>
              {data.map((l) => (
                <TableRow key={l.id} selected={draft?.id === l.id}>
                  <TableCell sx={tabular}>{l.order}</TableCell>
                  <TableCell>{l.description}{l.hidePrinting && <Typography component="span" color="text.secondary" sx={{ fontSize: 12, ml: 1 }}>not printed</Typography>}</TableCell>
                  <TableCell sx={{ color: l.kind === 'OTHER' ? 'text.secondary' : undefined }}>{l.target}</TableCell>
                  <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                    <Button size="small" disabled={busy} onClick={() => { setFail(null); setDraft(draftOf(l)) }}>Change</Button>
                    {l.removable && <Button size="small" color="error" disabled={busy} onClick={() => void run(() => removeLoanDeduction(code, l.id, reason.trim()))}>Remove</Button>}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Box>
      )}

      {draft ? (
        <Box role="group" aria-label={draft.id == null ? 'New deduction' : 'Change deduction'} sx={{ mt: 1.25, p: 1.5, border: 1, borderColor: 'divider', borderRadius: 1, display: 'grid', gridTemplateColumns: { xs: '1fr', sm: '1fr 90px' }, gap: 1.5 }}>
          <TextField required size="small" label="Description" value={draft.description} onChange={(e) => set({ description: e.target.value })} slotProps={{ htmlInput: { maxLength: 45 } }} />
          <TextField size="small" label="Order" value={draft.order} disabled={!draft.editableTarget} onChange={(e) => set({ order: e.target.value.replace(/\D/g, '') })}
            slotProps={{ htmlInput: { inputMode: 'numeric', maxLength: 2 } }} />
          {draft.editableTarget ? (
            <>
              <TextField select size="small" label="Goes to" value={draft.kind} onChange={(e) => set({ kind: e.target.value as LoanDeductionKind })}>
                {KINDS.map((k) => <MenuItem key={k.value} value={k.value}>{k.label}</MenuItem>)}
              </TextField>
              <Box />
              {draft.kind === 'MEMBER_ACCOUNT' && (
                <TextField select required size="small" label="Kind of account" value={draft.member} onChange={(e) => set({ member: e.target.value })}
                  helperText="Credited to the member's own account of this kind; the member must have one">
                  {(options.data?.memberTypes ?? []).map((t) => <MenuItem key={`${t.classId}/${t.typeCode}`} value={`${t.classId}/${t.typeCode}`}>{t.name}</MenuItem>)}
                </TextField>
              )}
              {draft.kind === 'LOAN_CHARGE' && (
                <TextField select required size="small" label="Charge" value={draft.event} onChange={(e) => set({ event: e.target.value })}
                  helperText="Posted where this loan type's COA Links send that charge">
                  {(options.data?.charges ?? []).map((c) => <MenuItem key={c.event} value={String(c.event)}>{c.name}</MenuItem>)}
                </TextField>
              )}
              {draft.kind === 'CHART_ACCOUNT' && (
                <TextField required size="small" label="Account no." value={draft.account} onChange={(e) => set({ account: e.target.value.replace(/\D/g, '') })} slotProps={{ htmlInput: { maxLength: 10 } }}
                  helperText="An account of the chart that takes postings, for example an insurer's payable" />
              )}
            </>
          ) : (
            <Typography color="text.secondary" sx={{ fontSize: 12, gridColumn: '1 / -1' }}>Only the wording of this line can be changed here; where it goes stays as it is.</Typography>
          )}
          <Box sx={{ gridColumn: '1 / -1', display: 'flex', alignItems: 'center', gap: 1 }}>
            <FormControlLabel control={<Switch size="small" checked={draft.hidePrinting} onChange={(e) => set({ hidePrinting: e.target.checked })} />} label={<Typography sx={{ fontSize: 13 }}>Leave it off the printed slip</Typography>} />
            <Box sx={{ flex: 1 }} />
            <Button size="small" color="inherit" onClick={() => setDraft(null)} disabled={busy}>Cancel</Button>
            <Button size="small" variant="contained" disabled={busy || !complete(draft)}
              onClick={() => void run(() => (draft.id == null ? addLoanDeduction(code, formOf(draft)) : changeLoanDeduction(code, draft.id, formOf(draft))))}>
              {draft.id == null ? 'Add deduction' : 'Save deduction'}
            </Button>
          </Box>
        </Box>
      ) : (
        <Button size="small" startIcon={<FiPlus />} sx={{ mt: 1 }} disabled={busy || !data} onClick={() => { setFail(null); setDraft(blank(nextOrder)) }}>Add a deduction</Button>
      )}

      <TextField size="small" fullWidth label="Reason for a change (needed for Add, Save and Remove)" value={reason} onChange={(e) => setReason(e.target.value)} sx={{ mt: 1.5 }}
        helperText="Kept in the audit log with the line before and after. The amount of each deduction is typed on the application. Applications already saved keep their own lines; the net proceeds line cannot be removed."
        slotProps={{ htmlInput: { maxLength: 255 } }} />
    </Box>
  )
}
