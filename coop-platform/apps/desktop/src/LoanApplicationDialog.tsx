import { Alert, Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle, InputAdornment, MenuItem, Select, Skeleton, TextField, Tooltip, Typography } from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { createLoanApplication, getAmortizationPdf, getLoanAppPermissions, getLoanAppTemplate, getLoanTypes, type LoanAppCreated, type Member } from './api'
import LoanDeductionFields, { deductionInput, type DeductionAmounts, type Payoffs } from './LoanDeductionFields'
import { FindBody, type FindWording } from './FindClientDialog'
import LoanApplicantCard from './LoanApplicantCard'
import LoanRelatedTabs, { CoMakerPicker, CoMakerRows, type CoMakerHoldInput } from './LoanRelatedTabs'
import { appear } from './motion'
import { announceLoanApplicationSaved, openLoanApplicationWindow } from './detachedWindow'
import type { ReactNode } from 'react'
import MoneyField from './MoneyField'
import { missingText, profileChecks, type ProfileField } from './loanProfileChecks'
import { money } from './ui/format'
import { printPdf } from './ui/printPdf'


/**
 * New loan application (status New), after the old Loans Profile screen: the loan type's defaults come from the server, the clerk gives the
 * amount, term, purpose, collateral, co-makers and the deduction amounts. Saving writes the application, loan and schedule; verifying and
 * approving are later steps. Opened from a member's profile it is given the member and loan type; opened from the dashboard it starts with a member search
 * and a loan type choice.
 */
/** The type chosen first when the clerk has not chosen one: the most used, which is Back to back. */

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
  // No loan type until the clerk picks one (or the window was opened for a given type), so nothing is assumed.
  const [pickType, setPickType] = useState<number | null>(null)
  const branchCode = givenBranch ?? picked?.id.branchCode ?? 0
  const clientId = givenClient ?? picked?.id.clientId ?? 0
  const ready = clientId > 0
  // The loan types come from the setup. If the chosen one cannot be used, the most used usable one is taken instead.
  const types = useQuery({ queryKey: ['loan-types', branchCode], queryFn: () => getLoanTypes(branchCode, clientId), enabled: searching && ready, staleTime: 10 * 60_000 })
  const usable = types.data?.filter((x) => x.available)
  const typeCode: number | null = givenType ?? (pickType != null && (!usable || usable.some((x) => x.code === pickType)) ? pickType : null)
  useEffect(() => { onApplicant?.(ready ? { branchCode, clientId } : null) }, [onApplicant, ready, branchCode, clientId])
  const { data: t, error, isLoading } = useQuery({ queryKey: ['loan-app-template', branchCode, clientId, typeCode], queryFn: () => getLoanAppTemplate(branchCode, clientId, typeCode!), enabled: ready && typeCode != null && (!searching || !!types.data) })
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
  const [ded, setDed] = useState<DeductionAmounts>({})
  // Co-makers are kept as a comma-separated list of client numbers, which is what the save expects.
  const coMakerIds = coMakers.split(/[\s,]+/).filter(Boolean).map(Number).filter((n) => Number.isInteger(n) && n > 0)
  const unitOf = (x: { termUnit: number }) => (unit ? Number(unit) : x.termUnit === 7 ? 7 : 4)
  const [done, setDone] = useState<LoanAppCreated | null>(null)
  const [cls, setCls] = useState('1')
  const [paid, setPaid] = useState<Payoffs>({})

  // Profiling (Add loan application) is needed to save; Deduction to fill in the deductions in the same save. Said before anything is typed.
  const perms = useQuery({ queryKey: ['loan-app-permissions'], queryFn: getLoanAppPermissions, staleTime: 60_000 })
  const mayAdd = perms.data?.canAdd !== false
  const mayDeduct = perms.data?.canDeduct !== false
  // With Profiling only, a restructured loan cannot be saved: its pay-off is part of the deductions.
  const restructureNeedsBoth = !mayDeduct && cls === '3'

  // ---- guidance: what Profiling needs (loanProfileChecks), the cursor moving on, and pointing at what is missing ----
  const checks = profileChecks({
    typeChosen: typeCode != null, amount, terms, unit: t ? unitOf(t) : 4, typeUnit: t?.termUnit === 7 ? 7 : 4, typeTerms: t?.terms ?? 0,
    collateralRequired: !!t?.setup.collateralRequired, collType, collAmount, collDesc,
  })
  const missing = checks.filter((c) => !c.done)
  // A required field shows what it needs once the person has left it (not before they reach it).
  const [touched, setTouched] = useState<ReadonlySet<ProfileField>>(new Set())
  const [relatedTab, setRelatedTab] = useState(0)
  const spots = useRef<Partial<Record<ProfileField | 'next', HTMLElement | null>>>({})
  const spot = (f: ProfileField | 'next') => (el: HTMLElement | null) => { spots.current[f] = el }
  /** Puts the cursor on a field (opening the Collateral tab first when it is there) and brings it into view. */
  const focusField = (f: ProfileField | 'next') => {
    if (f.startsWith('coll')) setRelatedTab(1)
    requestAnimationFrame(() => requestAnimationFrame(() => {
      const box = spots.current[f]
      if (!box) return
      const target = box.matches('button') ? box : box.querySelector<HTMLElement>('input:not([type="hidden"]):not([aria-hidden="true"]), textarea, [role="combobox"]')
      box.scrollIntoView({ block: 'center', behavior: 'smooth' })
      target?.focus()
    }))
  }
  const problem = (f: ProfileField) => (touched.has(f) ? checks.find((c) => c.field === f && !c.done)?.message : undefined)
  /** Marks the field that focus left as touched, so its message shows if it is still missing. */
  const leftField = (e: React.FocusEvent) => {
    const f = (Object.keys(spots.current) as (ProfileField | 'next')[]).find((k) => k !== 'next' && spots.current[k]?.contains(e.target as Node)) as ProfileField | undefined
    if (f && !touched.has(f)) setTouched((prev) => new Set(prev).add(f))
  }
  /** Enter in a field moves to the next one still to be filled in, or to the button that goes on. */
  const onEnter = (f: ProfileField) => (e: React.KeyboardEvent) => {
    if (e.key !== 'Enter' || e.shiftKey) return
    e.preventDefault()
    const order = checks.map((c) => c.field)
    const after = order.slice(order.indexOf(f) + 1)
    focusField(after.find((x) => missing.some((m) => m.field === x)) ?? 'next')
  }
  /** Goes on only when Profiling is complete; otherwise shows what is missing and puts the cursor on the first. */
  // The buttons that go on (Print amortization, Next, Save) can be pressed only once Profiling is complete; until then they say what is still needed.
  const incomplete = missing.length > 0
  const stillNeeded = incomplete ? `Fill in ${missingText(checks)} first.` : ''
  /**
   * A button that waits for Profiling, with the reason on hover. A disabled button needs a wrapper to show it; the wrapper is always there (with no
   * reason, no tooltip), so the button is not remounted, and does not lose focus, when Profiling becomes complete.
   */
  const gate = (waiting: boolean, button: ReactNode) => (
    <Tooltip title={waiting && t ? stillNeeded : ''}><span style={{ display: 'inline-flex' }}>{button}</span></Tooltip>
  )
  // Once the loan type's setup is in, the next thing to type is the principal.
  const loadedType = t ? typeCode : null
  useEffect(() => {
    if (loadedType != null && showProfile && !amount) focusField('principal')
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [loadedType])
  /** What the form holds, as the save (and the printed schedule) take it. */
  const formBody = () => ({
      typeCode: typeCode!, amount: Number(amount), terms: terms ? Number(terms) : null, termUnit: unit ? Number(unit) : null, purpose: purpose ? Number(purpose) : null, loanClass: 1, borrowerClass: Number(cls),
      collateralType: collType ? Number(collType) : null, collateralAmount: collAmount ? Number(collAmount) : null, collateralDescription: collDesc || null, remarks: remarks || null,
      coMakers: coMakers.split(/[\s,]+/).filter(Boolean).map(Number),
      coMakerHolds: coMakerIds.filter((id) => Number(coHolds[id]?.amount) > 0 && coHolds[id]?.account).map((id) => {
        const [slc, slt, refNo] = coHolds[id].account.split('/').map(Number)
        return { clientId: id, amount: Number(coHolds[id].amount), slc, slt, refNo }
      }),
      ...(mayDeduct ? deductionInput(ded, paid) : { deductions: [], payoffs: [] }),
  })
  const save = useMutation({
    mutationFn: () => createLoanApplication(branchCode, clientId, formBody()),
    onSuccess: (r) => { setDone(r); qc.invalidateQueries({ queryKey: ['member-loans', branchCode, clientId] }); if (embedded) announceLoanApplicationSaved(); onSaved?.(r) },
  })
  // The amortization schedule of what is on the form, for the member to read and sign; once saved it carries the loan number.
  const print = useMutation({
    mutationFn: async () => printPdf(await getAmortizationPdf(branchCode, clientId, formBody(), done?.refNo),
      `amortization-schedule-${clientId}.pdf`),
    meta: { done: (how: unknown) => (how === 'saved' ? 'The amortization schedule was saved as a PDF; open it to print.' : 'The amortization schedule is ready to print.') },
  })
  const clear = () => { setTouched(new Set()); setAmount(''); setTerms(''); setUnit(''); setPurpose(''); setCollType(''); setCollAmount(''); setCollDesc(''); setRemarks(''); setCoMakers(''); setCoHolds({}); setDed({}); setDone(null); setCls('1'); setPaid({}); save.reset() }
  const pick = (m: Member | null) => { clear(); setPicked(m); }

  const title = `New loan application${t ? ` · ${t.typeName}` : ''}`
  // Inside the app it is a dialog that can be detached; in its own window it fills that window.
  const detach = () => { if (openLoanApplicationWindow(ready ? { branchCode, clientId, typeCode: typeCode ?? undefined } : {})) onClose() }
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
        {!mayAdd && !done && (
          <Alert severity="warning" sx={{ mb: 1.5 }}>
            Your account may not do the Profiling step, so a new application cannot be saved here. An administrator can switch it on for you
            in Settings &gt; Users: open your user, tab Receivables, Loan process, <b>1 · Profiling</b>.
            {perms.data?.canDeduct && <> You may do <b>2 · Deduction</b>: open an application waiting for deductions from the Loan Module, Loan applications waiting.</>}
          </Alert>
        )}
        {showProfile && searching && !picked && (
          <Box sx={{ ...(embedded ? appear : {}), mb: 1.5, mx: 'auto', width: '100%', maxWidth: embedded ? 520 : undefined, borderRadius: '5px', overflow: 'hidden', border: 1, borderColor: 'divider' }}>
            <FindBody wording={LOAN_FIND} inline allowInactive={false} onPick={() => {}} onPickMember={pick} />
          </Box>
        )}
        {ready && !embedded && <LoanApplicantCard id={{ branchCode, clientId }} member={picked} onChange={showProfile && searching && !done ? () => pick(null) : undefined} />}
        {showProfile && searching && picked && (
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, mb: 1.5 }}>
            <TextField size="small" select required label="Loan type" value={types.data && typeCode != null ? typeCode : ''} ref={spot('type')} onChange={(e) => { clear(); setPickType(e.target.value === '' ? null : Number(e.target.value)) }} sx={{ minWidth: 240 }} disabled={!!done || !types.data}
              slotProps={{ select: { displayEmpty: true }, inputLabel: { shrink: true } }}
              helperText={types.error ? (types.error as Error).message : problem('type')} error={!!types.error || !!problem('type')}>
              <MenuItem value="" disabled>-- Select Loan Type --</MenuItem>
              {types.data?.map((x) => <MenuItem key={x.code} value={x.code} disabled={!x.available}>{x.name}{x.available ? '' : ' (cannot be scheduled here yet)'}</MenuItem>)}
            </TextField>
          </Box>
        )}
        {error && <Alert severity="error">{(error as Error).message}</Alert>}
        {ready && searching && types.data && typeCode == null && !done && (
          <Typography color="text.secondary" sx={{ fontSize: 13, mb: 1 }}>Select the loan type to load its setup: term, rate, deductions and the rest.</Typography>
        )}
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
            <Box component="section" aria-label="What Profiling needs" sx={{ display: 'flex', alignItems: 'center', gap: 0.75, flexWrap: 'wrap' }}>
              <Typography sx={{ fontSize: 12, mr: 0.5 }} color="text.secondary">
                Fields marked * are required · {checks.length - missing.length} of {checks.length} done{missing.length ? ':' : '.'}
              </Typography>
              {checks.map((c) => (
                <Chip key={c.field} size="small" variant="outlined" clickable onClick={() => focusField(c.field)}
                  color={c.done ? 'success' : touched.has(c.field) ? 'error' : 'default'} label={`${c.done ? '✓' : '○'} ${c.label}`}
                  aria-label={`${c.label}: ${c.done ? 'done' : 'still to fill in'}`} />
              ))}
            </Box>
            <Box onBlurCapture={leftField} sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: '1fr 1fr' }, gap: 2, alignItems: 'start' }}>
              {/* Left half: the loan itself. Right half: everything related to it, one tab each. */}
              <Box sx={{ display: 'grid', gap: 1.5 }}>
                <TextField size="small" select label="Loan classification" value={cls} onChange={(e) => setCls(e.target.value)} fullWidth
                  helperText={cls === '3' ? 'A restructured loan must pay off the loan it restructures.' : undefined}>
                  <MenuItem value="1">New</MenuItem><MenuItem value="2">Renewal</MenuItem><MenuItem value="3">Restructured</MenuItem>
                </TextField>
                {!t.hasVouchersPayable && <Alert severity="info">This member has no vouchers-payable account yet. Saving the application opens one, as the old system does.</Alert>}
                <Box sx={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 1.25 }}>
                  <Box ref={spot('principal')}>
                    <MoneyField size="small" label="Principal" value={amount} onValueChange={(v) => setAmount(v)} required fullWidth onKeyDown={onEnter('principal')}
                      error={!!problem('principal')} helperText={problem('principal')} />
                  </Box>
                  {/* One field: the number and its unit share a single outline. */}
                  <TextField size="small" label="Term" type="number" value={terms} onChange={(e) => setTerms(e.target.value)} placeholder={unitOf(t) === t.termUnit ? String(t.terms) : undefined}
                    ref={spot('term')} onKeyDown={onEnter('term')} required={unitOf(t) !== t.termUnit} error={!!problem('term')}
                    helperText={problem('term') ?? (unitOf(t) === t.termUnit && !terms ? `Blank: the loan type’s ${t.terms} ${unitOf(t) === 7 ? 'years' : 'months'}` : undefined)}
                    slotProps={{ input: { endAdornment: (
                      <InputAdornment position="end">
                        <Select variant="standard" disableUnderline value={String(unitOf(t))} onChange={(e) => setUnit(String(e.target.value))} inputProps={{ 'aria-label': 'Term unit' }}
                          sx={{ fontSize: 13, color: 'text.secondary', '& .MuiSelect-select': { py: 0, pr: '20px !important' } }}>
                          <MenuItem value="4">Months</MenuItem><MenuItem value="7">Years</MenuItem>
                        </Select>
                      </InputAdornment>
                    ) } }} />
                  <TextField size="small" label="Interest % p.a." value={t.interestRate.toFixed(2)} slotProps={{ input: { readOnly: true } }} />
                  <TextField size="small" select label="Purpose" value={purpose} onChange={(e) => setPurpose(e.target.value)}>
                    {t.purposes.map((p) => <MenuItem key={p.id} value={String(p.id)}>{p.name}</MenuItem>)}
                  </TextField>
                </Box>
                <TextField size="small" label="Remarks" value={remarks} onChange={(e) => setRemarks(e.target.value)} multiline minRows={2} />
              </Box>
              <LoanRelatedTabs branchCode={branchCode} clientId={clientId} tab={relatedTab} onTab={setRelatedTab}
                collateralRequired={t.setup.collateralRequired} collateralMissing={missing.some((m) => m.field.startsWith('coll') && touched.has(m.field))}
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
                    <TextField size="small" select label="Collateral type" value={collType} onChange={(e) => setCollType(e.target.value)} ref={spot('collType')}
                      required={t.setup.collateralRequired} error={!!problem('collType')} helperText={problem('collType')}>
                      {t.collateralTypes.map((p) => <MenuItem key={p.id} value={String(p.id)}>{p.name}</MenuItem>)}
                    </TextField>
                    <Box ref={spot('collAmount')}>
                      <MoneyField size="small" label="Collateral amount" value={collAmount} onValueChange={(v) => setCollAmount(v)} fullWidth onKeyDown={onEnter('collAmount')}
                        required={t.setup.collateralRequired} error={!!problem('collAmount')} helperText={problem('collAmount')} />
                    </Box>
                    <TextField size="small" label="Collateral description" value={collDesc} onChange={(e) => setCollDesc(e.target.value)} multiline minRows={2} ref={spot('collDesc')}
                      required={t.setup.collateralRequired} error={!!problem('collDesc')} helperText={problem('collDesc')} />
                  </Box>
                } />
            </Box>
            </>}
            {showDeduction && <>
            {!showProfile && (
              <Typography sx={{ fontVariantNumeric: 'tabular-nums' }} color="text.secondary">
                {t.typeName} · principal <strong>{money(Number(amount) || 0)}</strong> over {terms || t.terms} {unitOf(t) === 7 ? 'years' : 'months'} at {t.interestRate.toFixed(2)}% p.a.
              </Typography>
            )}
            {mayDeduct
              ? <LoanDeductionFields t={t} amount={Number(amount) || 0} ded={ded} setDed={setDed} paid={paid} setPaid={setPaid} />
              : <Alert severity="info">You may do Profiling but not Deduction. The application is saved without deductions, and someone who may do the Deduction step fills them in before it is verified.</Alert>}
            </>}
            {save.error && <Alert severity="error">{(save.error as Error).message}</Alert>}
            {print.error && <Alert severity="error">{(print.error as Error).message}</Alert>}
          </Box>
        )}
        {done && (
          <Alert severity="success" sx={{ mt: 1 }}>
            Application {done.documentNo} saved (loan ref {done.refNo}). First due {done.firstDue}, maturity {done.maturity}, interest {money(done.totalInterest)}
            {done.waitingForDeductions ? '. It now waits for its deductions.' : `, net proceeds ${money(done.netProceeds)}.`}
          </Alert>
        )}
      </>,
    <>
      {part === 'deduction' && !done ? <Button onClick={onBack}>Back</Button> : <Button onClick={onClose}>{done ? 'Close' : 'Cancel'}</Button>}
      {/* Printing, going on and saving show once the member applying has been chosen; until then only Cancel. */}
      {ready && (<>
        {showProfile && gate(!done && incomplete,
          <Button disabled={!t || !!t.setup.unsupported || print.isPending || (!done && incomplete)} title={done || !incomplete ? 'The schedule of this loan as it is on the form, for the member to sign' : undefined}
            onClick={() => print.mutate()}>{print.isPending ? 'Preparing…' : 'Print amortization'}</Button>,
        )}
        {part === 'profile' && !done && mayDeduct && gate(incomplete,
          <Button variant="contained" ref={spot('next')} disabled={!t || !!t.setup.unsupported || incomplete} onClick={() => onNext?.()}>Next: Deduction</Button>,
        )}
        {part === 'profile' && !done && !mayDeduct && gate(incomplete,
          <Button variant="contained" title={restructureNeedsBoth ? 'A restructured loan needs both Profiling and Deduction.' : undefined}
            ref={spot('next')} disabled={!mayAdd || restructureNeedsBoth || !t || !!t.setup.unsupported || save.isPending || incomplete}
            onClick={() => save.mutate()}>Save and send for deductions</Button>,
        )}
        {part !== 'profile' && !done && gate(incomplete,
          <Button variant="contained" ref={part ? undefined : spot('next')} disabled={!mayAdd || !t || !!t.setup.unsupported || save.isPending || incomplete}
            onClick={() => save.mutate()}>{part ? 'Save and continue' : 'Save application'}</Button>,
        )}
      </>)}
    </>,
  )
}
