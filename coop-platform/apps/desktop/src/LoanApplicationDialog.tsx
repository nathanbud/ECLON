import { Alert, Box, Button, Checkbox, Chip, Dialog, DialogActions, DialogContent, DialogTitle, MenuItem, Skeleton, TextField, Typography } from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { createLoanApplication, getLoanAppTemplate, searchMembers, type LoanAppCreated, type Member } from './api'
import { displayName, memberNo, statusOf } from './clients'

const money = (n: number) => n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })

/**
 * New loan application (status New), after the old Loans Profile screen: the loan type's defaults come from the server, the clerk gives the
 * amount, term, purpose, collateral, co-makers and the deduction amounts. Saving writes the application, loan and schedule; verifying and
 * approving are later steps. Opened from a member's profile it is given the member and loan type; opened from the dashboard it starts with a member search
 * and a loan type choice.
 */
const LOAN_TYPES = [{ code: 48, name: 'Back to back loan' }, { code: 49, name: 'Co-maker loan' }]

export default function LoanApplicationDialog({ branchCode: givenBranch, clientId: givenClient, typeCode: givenType, onClose }: { branchCode?: number; clientId?: number; typeCode?: number; onClose: () => void }) {
  const qc = useQueryClient()
  const searching = givenClient == null
  const [picked, setPicked] = useState<Member | null>(null)
  const [pickType, setPickType] = useState(LOAN_TYPES[0].code)
  const [text, setText] = useState('')
  const [q, setQ] = useState('')
  useEffect(() => {
    const h = setTimeout(() => setQ(text.trim()), 300)
    return () => clearTimeout(h)
  }, [text])
  const found = useQuery({ queryKey: ['loan-start-search', q], queryFn: () => searchMembers(q, 0, 8), enabled: searching && !picked && q.length >= 2 })
  const branchCode = givenBranch ?? picked?.id.branchCode ?? 0
  const clientId = givenClient ?? picked?.id.clientId ?? 0
  const typeCode = givenType ?? pickType
  const ready = clientId > 0
  const { data: t, error, isLoading } = useQuery({ queryKey: ['loan-app-template', branchCode, clientId, typeCode], queryFn: () => getLoanAppTemplate(branchCode, clientId, typeCode), enabled: ready })
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
  const clear = () => { setAmount(''); setTerms(''); setPurpose(''); setCollType(''); setCollAmount(''); setCollDesc(''); setRemarks(''); setCoMakers(''); setDed({}); setDone(null); setCls('1'); setPaid({}); save.reset() }
  const pick = (m: Member | null) => { clear(); setPicked(m); if (m == null) { setText(''); setQ('') } }
  const paidTotal = Object.values(paid).reduce((s, v) => s + (Number(v.principal) || 0) + (Number(v.interest) || 0) + (Number(v.penalty) || 0), 0)
  const total = Object.values(ded).reduce((s, v) => s + (Number(v) || 0), 0) + paidTotal
  const net = (Number(amount) || 0) - total

  return (
    <Dialog open onClose={onClose} maxWidth="md" fullWidth>
      <DialogTitle>New loan application{t ? ` · ${t.typeName}` : ''}</DialogTitle>
      <DialogContent>
        {searching && !picked && (
          <Box sx={{ mb: 1.5, pt: 0.5 }}>
            <Typography sx={{ fontSize: 13, mb: 1 }} color="text.secondary">Find the member who wants to apply. Search by name or member number.</Typography>
            <TextField autoFocus fullWidth size="small" label="Search member" value={text} onChange={(e) => setText(e.target.value)} />
            {found.error && <Alert severity="error" sx={{ mt: 1 }}>{(found.error as Error).message}</Alert>}
            {q.length >= 2 && found.data && found.data.content.length === 0 && <Typography sx={{ py: 1.5 }} color="text.secondary">No member found.</Typography>}
            {found.data?.content.map((m) => {
              const st = statusOf(m)
              return (
                <Box key={`${m.id.branchCode}-${m.id.clientId}`} role="button" tabIndex={0} onClick={() => pick(m)} onKeyDown={(e) => { if (e.key === 'Enter') pick(m) }}
                  sx={{ display: 'flex', alignItems: 'center', gap: 1.5, py: 0.9, borderTop: '1px solid', borderColor: 'divider', cursor: 'pointer', '&:hover': { bgcolor: 'action.hover' } }}>
                  <Box sx={{ flex: 1, minWidth: 0 }}>
                    <Typography sx={{ fontSize: 14 }}>{displayName(m)}</Typography>
                    <Typography sx={{ fontSize: 12 }} color="text.secondary">{memberNo(m.id)}{m.city ? ` · ${m.city}` : ''}</Typography>
                  </Box>
                  <Chip size="small" variant="outlined" color={st.kind} label={st.label} />
                </Box>
              )
            })}
          </Box>
        )}
        {searching && picked && (
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, mb: 1.5, pt: 0.5, flexWrap: 'wrap' }}>
            <Box sx={{ flex: 1, minWidth: 180 }}>
              <Typography sx={{ fontWeight: 600 }}>{displayName(picked)}</Typography>
              <Typography sx={{ fontSize: 12 }} color="text.secondary">{memberNo(picked.id)}</Typography>
            </Box>
            <TextField size="small" select label="Loan type" value={pickType} onChange={(e) => { clear(); setPickType(Number(e.target.value)) }} sx={{ minWidth: 200 }} disabled={!!done}>
              {LOAN_TYPES.map((x) => <MenuItem key={x.code} value={x.code}>{x.name}</MenuItem>)}
            </TextField>
            {!done && <Button size="small" onClick={() => pick(null)}>Change member</Button>}
          </Box>
        )}
        {searching && picked && statusOf(picked).kind === 'default' && <Alert severity="warning" sx={{ mb: 1.5 }}>This member's account is not active. The application may be refused.</Alert>}
        {error && <Alert severity="error">{(error as Error).message}</Alert>}
        {ready && isLoading && <Skeleton variant="rounded" height={240} />}
        {t && !done && (
          <Box sx={{ display: 'grid', gap: 1.5, pt: 1 }}>
            <TextField size="small" select label="Loan classification" value={cls} onChange={(e) => setCls(e.target.value)} sx={{ maxWidth: 260 }}
              helperText={cls === '3' ? 'A restructured loan must pay off the loan it restructures.' : undefined}>
              <MenuItem value="1">New</MenuItem><MenuItem value="2">Renewal</MenuItem><MenuItem value="3">Restructured</MenuItem>
            </TextField>
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
