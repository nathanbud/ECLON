import { Alert, Box, Button, Checkbox, Dialog, DialogActions, DialogContent, DialogTitle, MenuItem, Skeleton, TextField, Typography } from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { createLoanApplication, getLoanAppTemplate, type LoanAppCreated } from './api'

const money = (n: number) => n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })

/**
 * New loan application (status New), after the old Loans Profile screen: the loan type's defaults come from the server, the clerk gives the
 * amount, term, purpose, collateral, co-makers and the deduction amounts. Saving writes the application, loan and schedule; verifying and
 * approving are later steps.
 */
export default function LoanApplicationDialog({ branchCode, clientId, typeCode, onClose }: { branchCode: number; clientId: number; typeCode: number; onClose: () => void }) {
  const qc = useQueryClient()
  const { data: t, error, isLoading } = useQuery({ queryKey: ['loan-app-template', branchCode, clientId, typeCode], queryFn: () => getLoanAppTemplate(branchCode, clientId, typeCode) })
  const [amount, setAmount] = useState('')
  const [terms, setTerms] = useState('')
  const [purpose, setPurpose] = useState('')
  const [collType, setCollType] = useState('')
  const [collAmount, setCollAmount] = useState('')
  const [collDesc, setCollDesc] = useState('')
  const [remarks, setRemarks] = useState('')
  const [coMakers, setCoMakers] = useState('')
  const [ded, setDed] = useState<Record<number, string>>({})
  const [done, setDone] = useState<LoanAppCreated | null>(null)
  const [cls, setCls] = useState('1')
  const [paid, setPaid] = useState<Record<string, { principal: string; interest: string; penalty: string }>>({})

  const save = useMutation({
    mutationFn: () => createLoanApplication(branchCode, clientId, {
      typeCode, amount: Number(amount), terms: terms ? Number(terms) : null, purpose: purpose ? Number(purpose) : null, loanClass: 1, borrowerClass: Number(cls),
      collateralType: collType ? Number(collType) : null, collateralAmount: collAmount ? Number(collAmount) : null, collateralDescription: collDesc || null, remarks: remarks || null,
      coMakers: coMakers.split(/[\s,]+/).filter(Boolean).map(Number),
      deductions: Object.entries(ded).filter(([, v]) => Number(v) > 0).map(([id, v]) => ({ id: Number(id), amount: Number(v) })),
      payoffs: Object.entries(paid).map(([k, v]) => { const [typeCode, refNo] = k.split('/').map(Number); return { typeCode, refNo, principal: Number(v.principal) || 0, interest: Number(v.interest) || 0, penalty: Number(v.penalty) || 0 } }),
    }),
    onSuccess: (r) => { setDone(r); qc.invalidateQueries({ queryKey: ['member-loans', branchCode, clientId] }) },
  })
  const paidTotal = Object.values(paid).reduce((s, v) => s + (Number(v.principal) || 0) + (Number(v.interest) || 0) + (Number(v.penalty) || 0), 0)
  const total = Object.values(ded).reduce((s, v) => s + (Number(v) || 0), 0) + paidTotal
  const net = (Number(amount) || 0) - total

  return (
    <Dialog open onClose={onClose} maxWidth="md" fullWidth>
      <DialogTitle>New loan application{t ? ` · ${t.typeName}` : ''}</DialogTitle>
      <DialogContent>
        {error && <Alert severity="error">{(error as Error).message}</Alert>}
        {isLoading && <Skeleton variant="rounded" height={240} />}
        {t && !done && (
          <Box sx={{ display: 'grid', gap: 1.5, pt: 1 }}>
            {!t.hasVouchersPayable && <Alert severity="info">This member has no vouchers-payable account yet. Saving the application opens one, as the old system does.</Alert>}
            <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: '1fr 1fr 1fr' }, gap: 1.25 }}>
              <TextField size="small" label="Principal" type="number" value={amount} onChange={(e) => setAmount(e.target.value)} required />
              <TextField size="small" label="Term (months)" type="number" value={terms} onChange={(e) => setTerms(e.target.value)} placeholder={String(t.terms)} />
              <TextField size="small" label="Interest % p.a." value={t.interestRate.toFixed(5)} slotProps={{ input: { readOnly: true } }} />
              <TextField size="small" select label="Purpose" value={purpose} onChange={(e) => setPurpose(e.target.value)}>
                {t.purposes.map((p) => <MenuItem key={p.id} value={String(p.id)}>{p.name}</MenuItem>)}
              </TextField>
              <TextField size="small" select label="Collateral type" value={collType} onChange={(e) => setCollType(e.target.value)}>
                {t.collateralTypes.map((p) => <MenuItem key={p.id} value={String(p.id)}>{p.name}</MenuItem>)}
              </TextField>
              <TextField size="small" label="Collateral amount" type="number" value={collAmount} onChange={(e) => setCollAmount(e.target.value)} />
            </Box>
            <TextField size="small" label="Collateral description" value={collDesc} onChange={(e) => setCollDesc(e.target.value)} />
            <TextField size="small" label="Remarks" value={remarks} onChange={(e) => setRemarks(e.target.value)} />
            <TextField size="small" label="Co-maker client IDs (separated by commas)" value={coMakers} onChange={(e) => setCoMakers(e.target.value)} />
            <TextField size="small" select label="Loan classification" value={cls} onChange={(e) => setCls(e.target.value)} sx={{ maxWidth: 260 }}
              helperText={cls === '3' ? 'A restructured loan must pay off the loan it restructures.' : undefined}>
              <MenuItem value="1">New</MenuItem><MenuItem value="2">Renewal</MenuItem><MenuItem value="3">Restructured</MenuItem>
            </TextField>
            {t.payoffs.length > 0 && (
              <>
                <Typography sx={{ fontSize: 12, fontWeight: 700 }} color="text.secondary">Pay off earlier loans from the proceeds</Typography>
                {t.payoffs.map((p) => {
                  const k = `${p.typeCode}/${p.refNo}`
                  const on = k in paid
                  return (
                    <Box key={k} sx={{ display: 'grid', gridTemplateColumns: 'auto 1.5fr 1fr 1fr 1fr', gap: 1, alignItems: 'center' }}>
                      <Checkbox size="small" checked={on} slotProps={{ input: { 'aria-label': `Pay off ${p.typeName ?? p.typeCode} ${p.refNo}` } }}
                        onChange={(_, c) => { const next = { ...paid }; if (c) next[k] = { principal: String(p.outstanding), interest: String(p.interestDue), penalty: '' }; else delete next[k]; setPaid(next) }} />
                      <Typography sx={{ fontSize: 13 }}>{p.typeName ?? `Loan ${p.typeCode}`} #{p.refNo} · owes {money(p.outstanding)}{p.daysPastDue > 0 ? ` · ${p.daysPastDue} days late` : ''}</Typography>
                      <TextField size="small" type="number" label="Principal" disabled={!on} value={paid[k]?.principal ?? ''} onChange={(e) => setPaid({ ...paid, [k]: { ...paid[k], principal: e.target.value } })} />
                      <TextField size="small" type="number" label="Interest" disabled={!on} value={paid[k]?.interest ?? ''} onChange={(e) => setPaid({ ...paid, [k]: { ...paid[k], interest: e.target.value } })} />
                      <TextField size="small" type="number" label="Penalty (typed)" disabled={!on} value={paid[k]?.penalty ?? ''} onChange={(e) => setPaid({ ...paid, [k]: { ...paid[k], penalty: e.target.value } })} />
                    </Box>
                  )
                })}
              </>
            )}
            <Typography sx={{ fontSize: 12, fontWeight: 700 }} color="text.secondary">Deductions (amounts typed by the clerk)</Typography>
            <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: '1fr 1fr' }, gap: 1.25 }}>
              {t.deductions.map((d) => (
                <TextField key={d.id} size="small" type="number" label={d.description} value={ded[d.id] ?? ''} onChange={(e) => setDed({ ...ded, [d.id]: e.target.value })}
                  error={d.needsMemberAccount && !d.hasMemberAccount} helperText={d.needsMemberAccount && !d.hasMemberAccount ? 'The member has no account for this' : undefined} />
              ))}
            </Box>
            <Typography sx={{ fontVariantNumeric: 'tabular-nums' }}>Deductions {money(total)} · Net proceeds <strong>{money(net)}</strong></Typography>
            {save.error && <Alert severity="error">{(save.error as Error).message}</Alert>}
          </Box>
        )}
        {done && (
          <Alert severity="success" sx={{ mt: 1 }}>
            Application {done.documentNo} saved (loan ref {done.refNo}). First due {done.firstDue}, maturity {done.maturity}, interest {money(done.totalInterest)}, net proceeds {money(done.netProceeds)}.
          </Alert>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>{done ? 'Close' : 'Cancel'}</Button>
        {!done && <Button variant="contained" disabled={!t || !(Number(amount) > 0) || save.isPending} onClick={() => save.mutate()}>Save application</Button>}
      </DialogActions>
    </Dialog>
  )
}
