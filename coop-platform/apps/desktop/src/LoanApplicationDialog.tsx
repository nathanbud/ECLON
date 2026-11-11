import { Alert, Box, Button, Checkbox, Dialog, DialogActions, DialogContent, DialogTitle, InputAdornment, MenuItem, Select, Skeleton, TextField, Typography } from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { createLoanApplication, getLoanAppTemplate, getLoanTypes, type LoanAppCreated, type Member } from './api'
import { FindBody, type FindWording } from './FindClientDialog'
import LoanApplicantCard from './LoanApplicantCard'
import LoanRelatedTabs, { CoMakerPicker, CoMakerRows, type CoMakerHoldInput } from './LoanRelatedTabs'
import { appear } from './motion'
import { announceLoanApplicationSaved, openLoanApplicationWindow } from './detachedWindow'
import type { ReactNode } from 'react'
import MoneyField from './MoneyField'
import { money } from './ui/format'


/**
 * New loan application (status New), after the old Loans Profile screen: the loan type's defaults come from the server, the clerk gives the
 * amount, term, purpose, collateral, co-makers and the deduction amounts. Saving writes the application, loan and schedule; verifying and
 * approving are later steps. Opened from a member's profile it is given the member and loan type; opened from the dashboard it starts with a member search
 * and a loan type choice.
 */
/** The type chosen first when the clerk has not chosen one: the most used, which is Back to back. */
const DEFAULT_TYPE = 48

/** The member finder's layout, worded for starting a loan application. */
const LOAN_FIND: FindWording = { title: 'New loan application', lead: 'Who is applying for a loan?', hint: 'Find the member by name or member ID, then choose the loan type.', enter: 'start application', queryTag: 'loan-start' }

export default function LoanApplicationDialog({ branchCode: givenBranch, clientId: givenClient, typeCode: givenType, onClose, embedded, onSaved, part, onNext, onBack, onApplicant }: {
  branchCode?: number; clientId?: number; typeCode?: number; onClose: () => void; embedded?: boolean; onSaved?: (r: LoanAppCreated) => void
  /** In the step-by-step window the form is shown in two parts: the loan itself (profile) and what comes out of the proceeds (deduction). Left out, it shows everything. */
  part?: 'profile' | 'deduction'; onNext?: () => void; onBack?: () => void
  /** Tells the window who the applicant is, so it can show them above the steps. */
  onApplicant?: (id: { branchCode: number; clientId: number } | null) => void
}) {
  const showProfile = part !== 'deduction'
  const showDeduction = part !== 'profile'
  const qc = useQueryClient()
  const searching = givenClient == null
  const [picked, setPicked] = useState<Member | null>(null)
  const [pickType, setPickType] = useState(DEFAULT_TYPE)
  const branchCode = givenBranch ?? picked?.id.branchCode ?? 0
  const clientId = givenClient ?? picked?.id.clientId ?? 0
  const ready = clientId > 0
  // The loan types come from the setup. If the chosen one cannot be used, the most used usable one is taken instead.
  const types = useQuery({ queryKey: ['loan-types', branchCode], queryFn: () => getLoanTypes(branchCode, clientId), enabled: searching && ready, staleTime: 10 * 60_000 })
  const usable = types.data?.filter((x) => x.available)
  const typeCode = givenType ?? (usable ? (usable.find((x) => x.code === pickType) ?? usable[0])?.code ?? pickType : pickType)
  useEffect(() => { onApplicant?.(ready ? { branchCode, clientId } : null) }, [onApplicant, ready, branchCode, clientId])
  const { data: t, error, isLoading } = useQuery({ queryKey: ['loan-app-template', branchCode, clientId, typeCode], queryFn: () => getLoanAppTemplate(branchCode, clientId, typeCode), enabled: ready && (!searching || !!types.data) })
  const [amount, setAmount] = useState('')
  const [terms, setTerms] = useState('')
  const [unit, setUnit] = useState('') // '' = the loan type's own unit
  const [purpose, setPurpose] = useState('')
  const [collType, setCollType] = useState('')
  const [collAmount, setCollAmount] = useState('')
  const [collDesc, setCollDesc] = useState('')
  const [remarks, setRemarks] = useState('')
  const [coMakers, setCoMakers] = useState('')
  const [coHolds, setCoHolds] = useState<Record<number, CoMakerHoldInput>>({})
  const [ded, setDed] = useState<Record<number, string>>({})
  // Co-makers are kept as a comma-separated list of client numbers, which is what the save expects.
  const coMakerIds = coMakers.split(/[\s,]+/).filter(Boolean).map(Number).filter((n) => Number.isInteger(n) && n > 0)
  const unitOf = (x: { termUnit: number }) => (unit ? Number(unit) : x.termUnit === 7 ? 7 : 4)
  const [done, setDone] = useState<LoanAppCreated | null>(null)
  const [cls, setCls] = useState('1')
  const [paid, setPaid] = useState<Record<string, { principal: string; interest: string; penalty: string }>>({})

  const save = useMutation({
    mutationFn: () => createLoanApplication(branchCode, clientId, {
      typeCode, amount: Number(amount), terms: terms ? Number(terms) : null, termUnit: unit ? Number(unit) : null, purpose: purpose ? Number(purpose) : null, loanClass: 1, borrowerClass: Number(cls),
      collateralType: collType ? Number(collType) : null, collateralAmount: collAmount ? Number(collAmount) : null, collateralDescription: collDesc || null, remarks: remarks || null,
      coMakers: coMakers.split(/[\s,]+/).filter(Boolean).map(Number),
      coMakerHolds: coMakerIds.filter((id) => Number(coHolds[id]?.amount) > 0 && coHolds[id]?.account).map((id) => {
        const [slc, slt, refNo] = coHolds[id].account.split('/').map(Number)
        return { clientId: id, amount: Number(coHolds[id].amount), slc, slt, refNo }
      }),
      deductions: Object.entries(ded).filter(([, v]) => Number(v) > 0).map(([id, v]) => ({ id: Number(id), amount: Number(v) })),
      payoffs: Object.entries(paid).map(([k, v]) => { const [typeCode, refNo] = k.split('/').map(Number); return { typeCode, refNo, principal: Number(v.principal) || 0, interest: Number(v.interest) || 0, penalty: Number(v.penalty) || 0 } }),
    }),
    onSuccess: (r) => { setDone(r); qc.invalidateQueries({ queryKey: ['member-loans', branchCode, clientId] }); if (embedded) announceLoanApplicationSaved(); onSaved?.(r) },
  })
  const clear = () => { setAmount(''); setTerms(''); setUnit(''); setPurpose(''); setCollType(''); setCollAmount(''); setCollDesc(''); setRemarks(''); setCoMakers(''); setCoHolds({}); setDed({}); setDone(null); setCls('1'); setPaid({}); save.reset() }
  const pick = (m: Member | null) => { clear(); setPicked(m); }
  const paidTotal = Object.values(paid).reduce((s, v) => s + (Number(v.principal) || 0) + (Number(v.interest) || 0) + (Number(v.penalty) || 0), 0)
  const total = Object.values(ded).reduce((s, v) => s + (Number(v) || 0), 0) + paidTotal
  const net = (Number(amount) || 0) - total

  const title = `New loan application${t ? ` · ${t.typeName}` : ''}`
  // Inside the app it is a dialog that can be detached; in its own window it fills that window.
  const detach = () => { if (openLoanApplicationWindow(ready ? { branchCode, clientId, typeCode } : {})) onClose() }
  // A plain function, not a component: a component defined here would remount, and lose focus, on every keystroke.
  const shell = (children: ReactNode, actions: ReactNode) => embedded ? (
    <Box sx={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
      <Box sx={{ flex: 1, overflow: 'auto', px: 3, py: 1 }}>{children}</Box>
      <Box sx={{ display: 'flex', justifyContent: 'flex-end', gap: 1, p: 2 }}>{actions}</Box>
    </Box>
  ) : (
    <Dialog open onClose={onClose} maxWidth="md" fullWidth>
      <DialogTitle sx={{ display: 'flex', alignItems: 'center' }}>
        <Box sx={{ flex: 1 }}>{title}</Box>
        <Button size="small" onClick={detach} disabled={!!done}>Open in own window</Button>
      </DialogTitle>
      <DialogContent>{children}</DialogContent>
      <DialogActions>{actions}</DialogActions>
    </Dialog>
  )

  return shell(
      <>
        {showProfile && searching && !picked && (
          <Box sx={{ ...(embedded ? appear : {}), mb: 1.5, mx: 'auto', width: '100%', maxWidth: embedded ? 520 : undefined, borderRadius: '5px', overflow: 'hidden', border: 1, borderColor: 'divider' }}>
            <FindBody wording={LOAN_FIND} inline onPick={() => {}} onPickMember={pick} />
          </Box>
        )}
        {ready && !embedded && <LoanApplicantCard id={{ branchCode, clientId }} member={picked} onChange={showProfile && searching && !done ? () => pick(null) : undefined} />}
        {showProfile && searching && picked && (
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, mb: 1.5 }}>
            <TextField size="small" select label="Loan type" value={types.data ? typeCode : ''} onChange={(e) => { clear(); setPickType(Number(e.target.value)) }} sx={{ minWidth: 240 }} disabled={!!done || !types.data}
              helperText={types.error ? (types.error as Error).message : undefined} error={!!types.error}>
              {types.data?.map((x) => <MenuItem key={x.code} value={x.code} disabled={!x.available}>{x.name}{x.available ? '' : ' (cannot be scheduled here yet)'}</MenuItem>)}
            </TextField>
          </Box>
        )}
        {error && <Alert severity="error">{(error as Error).message}</Alert>}
        {ready && isLoading && <Skeleton variant="rounded" height={240} />}
        {t && !done && (
          <Box sx={{ display: 'grid', gap: 1.5, pt: 1 }}>
            {showProfile && <>
            <Box component="section" aria-label="Loan setup" sx={{ border: 1, borderColor: 'divider', borderRadius: '8px', p: 1.5 }}>
              <Typography sx={{ fontSize: 11, fontWeight: 700, letterSpacing: '0.04em', textTransform: 'uppercase', mb: 1 }} color="text.secondary">Loan setup · {t.typeName}</Typography>
              <Box component="dl" sx={{ m: 0, display: 'grid', gridTemplateColumns: { xs: '1fr 1fr', sm: 'repeat(4, 1fr)' }, gap: 1.25 }}>
                {([
                  ['Based on', t.setup.basedOn], ['Diminishing', t.setup.diminish], ['Term', `${t.terms} ${t.setup.termUnit?.toLowerCase() ?? ''}`.trim()], ['Interest rate', `${t.interestRate.toFixed(2)}% p.a.`],
                  ['Principal payment', t.setup.principalPayment], ['Interest payment', t.setup.interestPayment], ['Penalty rate', t.setup.penaltyRate == null ? null : `${t.setup.penaltyRate}%`],
                  ['Days in a year', t.setup.yearDays == null ? null : String(t.setup.yearDays)], ['Collateral', t.setup.collateralRequired ? 'Required' : 'Not required'],
                ] as [string, string | null][]).map(([k, v]) => (
                  <Box key={k}><Typography component="dt" sx={{ fontSize: 11 }} color="text.secondary">{k}</Typography><Typography component="dd" sx={{ m: 0, fontSize: 13, fontWeight: 500 }}>{v ?? '—'}</Typography></Box>
                ))}
              </Box>
              {t.setup.unsupported && <Alert severity="warning" sx={{ mt: 1.25 }}>{t.setup.unsupported}</Alert>}
            </Box>
            <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: '1fr 1fr' }, gap: 2, alignItems: 'start' }}>
              {/* Left half: the loan itself. Right half: everything related to it, one tab each. */}
              <Box sx={{ display: 'grid', gap: 1.5 }}>
                <TextField size="small" select label="Loan classification" value={cls} onChange={(e) => setCls(e.target.value)} fullWidth
                  helperText={cls === '3' ? 'A restructured loan must pay off the loan it restructures.' : undefined}>
                  <MenuItem value="1">New</MenuItem><MenuItem value="2">Renewal</MenuItem><MenuItem value="3">Restructured</MenuItem>
                </TextField>
                {!t.hasVouchersPayable && <Alert severity="info">This member has no vouchers-payable account yet. Saving the application opens one, as the old system does.</Alert>}
                <Box sx={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 1.25 }}>
                  <MoneyField size="small" label="Principal" value={amount} onValueChange={(v) => setAmount(v)} required />
                  {/* One field: the number and its unit share a single outline. */}
                  <TextField size="small" label="Term" type="number" value={terms} onChange={(e) => setTerms(e.target.value)} placeholder={unitOf(t) === t.termUnit ? String(t.terms) : undefined}
                    slotProps={{ input: { endAdornment: (
                      <InputAdornment position="end">
                        <Select variant="standard" disableUnderline value={String(unitOf(t))} onChange={(e) => setUnit(String(e.target.value))} inputProps={{ 'aria-label': 'Term unit' }}
                          sx={{ fontSize: 13, color: 'text.secondary', '& .MuiSelect-select': { py: 0, pr: '20px !important' } }}>
                          <MenuItem value="4">Months</MenuItem><MenuItem value="7">Years</MenuItem>
                        </Select>
                      </InputAdornment>
                    ) } }} />
                  <TextField size="small" label="Interest % p.a." value={t.interestRate.toFixed(5)} slotProps={{ input: { readOnly: true } }} />
                  <TextField size="small" select label="Purpose" value={purpose} onChange={(e) => setPurpose(e.target.value)}>
                    {t.purposes.map((p) => <MenuItem key={p.id} value={String(p.id)}>{p.name}</MenuItem>)}
                  </TextField>
                </Box>
                <TextField size="small" label="Remarks" value={remarks} onChange={(e) => setRemarks(e.target.value)} multiline minRows={2} />
              </Box>
              <LoanRelatedTabs branchCode={branchCode} clientId={clientId}
                coMakers={
                  <>
                    <CoMakerPicker applicant={clientId} ids={coMakerIds} onAdd={(id) => setCoMakers([...coMakerIds, id].join(','))} />
                    <CoMakerRows branchCode={branchCode} applicant={clientId} ids={coMakerIds} holds={coHolds}
                      onChange={(id, v) => setCoHolds({ ...coHolds, [id]: v })}
                      onRemove={(id) => { setCoMakers(coMakerIds.filter((x) => x !== id).join(',')); const next = { ...coHolds }; delete next[id]; setCoHolds(next) }} />
                  </>
                }
                collateral={
                  <Box sx={{ display: 'grid', gap: 1.25 }}>
                    <TextField size="small" select label="Collateral type" value={collType} onChange={(e) => setCollType(e.target.value)}>
                      {t.collateralTypes.map((p) => <MenuItem key={p.id} value={String(p.id)}>{p.name}</MenuItem>)}
                    </TextField>
                    <MoneyField size="small" label="Collateral amount" value={collAmount} onValueChange={(v) => setCollAmount(v)} />
                    <TextField size="small" label="Collateral description" value={collDesc} onChange={(e) => setCollDesc(e.target.value)} multiline minRows={2} />
                  </Box>
                } />
            </Box>
            </>}
            {showDeduction && <>
            {!showProfile && (
              <Typography sx={{ fontVariantNumeric: 'tabular-nums' }} color="text.secondary">
                {t.typeName} · principal <strong>{money(Number(amount) || 0)}</strong> over {terms || t.terms} {unitOf(t) === 7 ? 'years' : 'months'} at {t.interestRate.toFixed(5)}% p.a.
              </Typography>
            )}
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
                      <MoneyField size="small" label="Principal" disabled={!on} value={paid[k]?.principal ?? ''} onValueChange={(v) => setPaid({ ...paid, [k]: { ...paid[k], principal: v } })} />
                      <MoneyField size="small" label="Interest" disabled={!on} value={paid[k]?.interest ?? ''} onValueChange={(v) => setPaid({ ...paid, [k]: { ...paid[k], interest: v } })} />
                      <MoneyField size="small" label="Penalty (typed)" disabled={!on} value={paid[k]?.penalty ?? ''} onValueChange={(v) => setPaid({ ...paid, [k]: { ...paid[k], penalty: v } })} />
                    </Box>
                  )
                })}
              </>
            )}
            <Typography sx={{ fontSize: 12, fontWeight: 700 }} color="text.secondary">Deductions (amounts typed by the clerk)</Typography>
            <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: '1fr 1fr' }, gap: 1.25 }}>
              {t.deductions.map((d) => (
                <MoneyField key={d.id} size="small" label={d.description} value={ded[d.id] ?? ''} onValueChange={(v) => setDed({ ...ded, [d.id]: v })}
                  error={d.needsMemberAccount && !d.hasMemberAccount} helperText={d.needsMemberAccount && !d.hasMemberAccount ? 'The member has no account for this' : undefined} />
              ))}
            </Box>
            <Typography sx={{ fontVariantNumeric: 'tabular-nums' }}>Deductions {money(total)} · Net proceeds <strong>{money(net)}</strong></Typography>
            </>}
            {save.error && <Alert severity="error">{(save.error as Error).message}</Alert>}
          </Box>
        )}
        {done && (
          <Alert severity="success" sx={{ mt: 1 }}>
            Application {done.documentNo} saved (loan ref {done.refNo}). First due {done.firstDue}, maturity {done.maturity}, interest {money(done.totalInterest)}, net proceeds {money(done.netProceeds)}.
          </Alert>
        )}
      </>,
    <>
      {part === 'deduction' && !done ? <Button onClick={onBack}>Back</Button> : <Button onClick={onClose}>{done ? 'Close' : 'Cancel'}</Button>}
      {part === 'profile' && !done && <Button variant="contained" disabled={!t || !!t.setup.unsupported || !(Number(amount) > 0) || (unitOf(t) !== t.termUnit && !(Number(terms) > 0))} onClick={onNext}>Next: Deduction</Button>}
      {part !== 'profile' && !done && <Button variant="contained" disabled={!t || !!t.setup.unsupported || !(Number(amount) > 0) || save.isPending || (unitOf(t) !== t.termUnit && !(Number(terms) > 0))} onClick={() => save.mutate()}>{part ? 'Save and continue' : 'Save application'}</Button>}
    </>,
  )
}
