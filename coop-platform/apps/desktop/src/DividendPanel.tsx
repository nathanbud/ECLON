import {
  Alert, Box, Button, Chip, Dialog, DialogActions, DialogContent, GlobalStyles, IconButton, Skeleton, Step, StepButton, Stepper, TextField, ToggleButton, ToggleButtonGroup, Typography,
} from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { FiPlus, FiTrash2 } from 'react-icons/fi'
import {
  approveDividend, downloadDividendRegister, encodeDividend, getDividendStatement, getDividendVouchers, postDividend, previewDividend,
  type DividendMemberAmounts, type DividendReq, type DividendStatement, type DividendStatutory, type DividendVoucher,
} from './api'
import { money } from './FoTransactionForm'
import { glass } from './glass'
import { ConfirmButton } from './ui/dialogs'

const statusColor = (s: DividendVoucher['status']) => (s === 'POSTED' ? 'success' : s === 'APPROVED' ? 'info' : 'warning')
const pct = (n: number) => `${Number(n.toFixed(6))}%`

/** One line per member: "client no, mutual benefit, hospitalization"; a blank or dash keeps the standard amount. */
function parseOverrides(text: string): { rows: DividendMemberAmounts[]; error: string | null } {
  const rows: DividendMemberAmounts[] = []
  const amount = (s: string | undefined) => (s === undefined || s.trim() === '' || s.trim() === '-' ? null : Number(s.trim()))
  for (const [i, raw] of text.split('\n').entries()) {
    if (raw.trim() === '') continue
    const [c, m, h] = raw.split(/[,\t ]+/)
    const clientId = Number(c)
    const mbf = amount(m), habp = amount(h)
    if (!Number.isInteger(clientId) || clientId <= 0 || (mbf !== null && !(mbf >= 0)) || (habp !== null && !(habp >= 0))) return { rows: [], error: `Line ${i + 1}: write the member number, then the two amounts, such as 12345, 402.17, 697.83.` }
    rows.push({ clientId, mutualBenefit: mbf, hospitalization: habp })
  }
  return { rows, error: null }
}

/** A member's combined distribution statement. It prints on its own: while printing, everything except the statement is hidden. */
function StatementDialog({ s, onClose }: { s: DividendStatement | null; onClose: () => void }) {
  const row = (k: string, v: string, bold = false) => <Box key={k} sx={{ display: 'flex', justifyContent: 'space-between', py: 0.4, fontWeight: bold ? 700 : 400 }}><span>{k}</span><span>{v}</span></Box>
  return (
    <Dialog open={!!s} onClose={onClose} maxWidth="xs" fullWidth>
      {s && (
        <GlobalStyles styles={{ '@media print': { 'body *': { visibility: 'hidden' }, '#dividend-statement, #dividend-statement *': { visibility: 'visible' }, '#dividend-statement': { position: 'absolute', left: 0, top: 0, width: '100%', padding: '15mm', color: '#000', background: '#fff' } } }} />
      )}
      <DialogContent>
        {s && (
          <Box id="dividend-statement" sx={{ fontSize: 13 }}>
            <Typography sx={{ fontWeight: 700, fontSize: 15, mb: 0.25 }}>Combined member distribution statement</Typography>
            <Typography sx={{ mb: 1.5 }} color="text.secondary">Financial year {s.year}</Typography>
            {row('Member', `${s.name ?? ''} (${s.clientId})`, true)}
            <Box sx={{ borderTop: '1px solid', borderColor: 'divider', my: 1 }} />
            {row('Average share-month', money(s.averageShareMonth))}
            {row('Interest on share capital rate', pct(s.dividendRate))}
            {row('Interest on share capital', money(s.dividend))}
            {row('Qualified patronage', money(s.qualifiedPatronage))}
            {row('Patronage refund rate', pct(s.patronageRate))}
            {row('Patronage refund', money(s.refund))}
            <Box sx={{ borderTop: '1px solid', borderColor: 'divider', my: 1 }} />
            {row('Total distribution', money(s.total), true)}
          </Box>
        )}
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Close</Button><Button variant="contained" onClick={() => window.print()}>Print</Button></DialogActions>
    </Dialog>
  )
}

/**
 * The yearly interest on share capital (dividend) and patronage refund, as the old "Dividend and Patronage Refund Transaction (Computer Generated)", extended with the method of
 * the specification (pics/divpat_system.docx, documentation/dividend-spec.md). The accountant either types the two rates, or enters the net surplus, the statutory funds and the amounts
 * the general assembly allocated, and the rates are worked out (allocation / total average share-month, allocation / total qualified patronage). The screen shows the figures and
 * what they reconcile to, then encodes the vouchers; another person approves and a third posts them. The register and each member's statement can be taken from the figures.
 */
export function DividendPanel() {
  const qc = useQueryClient()
  const year = new Date().getFullYear()
  const [method, setMethod] = useState<'rates' | 'allocation'>('rates')
  const [cutoff, setCutoff] = useState(`${year}-02-21`)
  const [dividendRate, setDividendRate] = useState('8')
  const [patronageRate, setPatronageRate] = useState('8.23')
  // Step 2, after the dividend and refund are known: how much of each member's share is directed to the two insurance funds. Empty means none: it all goes to savings or share capital.
  const [mutualBenefit, setMutualBenefit] = useState('')
  const [hospitalization, setHospitalization] = useState('')
  const [splitEdited, setSplitEdited] = useState(false)
  const [overrides, setOverrides] = useState('')
  const [netSurplus, setNetSurplus] = useState('')
  const [funds, setFunds] = useState<{ name: string; percent: string }[]>([])
  const [iscAllocation, setIscAllocation] = useState('')
  const [prAllocation, setPrAllocation] = useState('')
  const [resolution, setResolution] = useState('')
  const [statementOf, setStatementOf] = useState('')
  const [statement, setStatement] = useState<DividendStatement | null>(null)
  const [asked, setAsked] = useState<DividendReq | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [step, setStep] = useState(0)

  const availableNow = (() => {
    const n = Number(netSurplus)
    if (!(n > 0)) return null
    return n - funds.reduce((t, f) => t + (n * (Number(f.percent) || 0)) / 100, 0)
  })()

  const build = (): DividendReq | null => {
    const o = parseOverrides(overrides)
    if (o.error) { setError(o.error); return null }
    const base = { cutoff, mutualBenefit: Number(mutualBenefit || 0), hospitalization: Number(hospitalization || 0), overrides: o.rows }
    if (!cutoff || !(base.mutualBenefit >= 0) || !(base.hospitalization >= 0)) { setError('Give a cut-off date, and the insurance amounts as numbers of zero or more.'); return null }
    if (method === 'rates') {
      const nums = [dividendRate, patronageRate].map((x) => Number(x))
      if (nums.some((n) => !(n >= 0))) { setError('Give the rates as numbers of zero or more.'); return null }
      return { ...base, dividendRate: nums[0], patronageRate: nums[1] }
    }
    const isc = iscAllocation.trim() === '' ? null : Number(iscAllocation), pr = prAllocation.trim() === '' ? null : Number(prAllocation)
    if (isc === null && pr === null) { setError('Give the amount allocated to interest on share capital, to patronage refund, or both.'); return null }
    if ([isc, pr, Number(netSurplus)].some((n) => n !== null && !(n >= 0)) || !(Number(netSurplus) > 0)) { setError('Give the net surplus and the allocations as numbers; the net surplus must be more than zero.'); return null }
    const statutory: DividendStatutory[] = funds.filter((f) => f.name.trim() || f.percent.trim()).map((f) => ({ name: f.name.trim(), percent: Number(f.percent) }))
    return { ...base, iscAllocation: isc, prAllocation: pr, netSurplus: Number(netSurplus), statutory, approvalRef: resolution.trim() }
  }
  const preview = useQuery({ queryKey: ['dividend-preview', asked], queryFn: () => previewDividend(asked!), enabled: !!asked, retry: false, staleTime: Infinity })
  const vouchers = useQuery({ queryKey: ['dividend-vouchers', asked?.cutoff], queryFn: () => getDividendVouchers(asked!.cutoff), enabled: !!asked, retry: false, refetchInterval: 20_000 })
  const refresh = () => { void qc.invalidateQueries({ queryKey: ['dividend-preview'] }); void qc.invalidateQueries({ queryKey: ['dividend-vouchers'] }) }
  const encode = useMutation({ meta: { done: 'Dividend vouchers made. They now wait for approval.' }, mutationFn: () => encodeDividend(asked!), onSuccess: () => { setError(null); refresh() }, onError: (e) => setError((e as Error).message) })
  const act = useMutation({ meta: { done: (_, v) => ((v as { kind: string }).kind === 'approve' ? 'Voucher approved. Someone else can now post it.' : 'Dividend voucher posted.') },
    mutationFn: (v: { kind: 'approve' | 'post'; r: DividendVoucher }) => (v.kind === 'approve' ? approveDividend : postDividend)(v.r.year, v.r.controlNo),
    onSuccess: () => { setError(null); refresh() },
    onError: (e) => setError((e as Error).message),
  })
  const register = useMutation({ mutationFn: () => downloadDividendRegister(asked!), onError: (e) => setError((e as Error).message) })
  const showStatement = useMutation({ mutationFn: () => getDividendStatement(asked!, Number(statementOf)), onSuccess: setStatement, onError: (e) => setError((e as Error).message) })

  const p = preview.data
  const identified = !!asked && !!p
  const num = (set: (v: string) => void) => (e: React.ChangeEvent<HTMLInputElement>) => { set(e.target.value); setAsked(null); setStep(0) }
  const label = (t: string) => <Typography sx={{ fontSize: 12, fontWeight: 700, letterSpacing: '0.06em', textTransform: 'uppercase', mb: 0.75 }} color="text.secondary">{t}</Typography>
  const STEPS = ['Identify the dividend and patronage', 'Review the figures', 'Allocation']
  const go = (n: number) => { if (n === 0 || identified) setStep(n) }
  const identify = () => { setError(null); const r = build(); if (r) { setAsked(r); setSplitEdited(false); setStep(1) } }

  return (
    <Box sx={(t) => ({ p: 1.5, flex: 1, minHeight: 0, overflow: 'auto', ...glass(t) })}>
      <StatementDialog s={statement} onClose={() => setStatement(null)} />
      <Stepper nonLinear activeStep={step} sx={{ mb: 2 }}>
        {STEPS.map((t, i) => (
          <Step key={t} completed={identified && i < step}>
            <StepButton disabled={i > 0 && !identified} onClick={() => go(i)}>{t}</StepButton>
          </Step>
        ))}
      </Stepper>
      {error && <Alert severity="error" sx={{ mb: 1.5 }} onClose={() => setError(null)}>{error}</Alert>}

      {step === 0 && (
        <>
          {label('Step 1: identify the dividend and the patronage refund')}
          <Typography sx={{ fontSize: 13, mb: 1.5 }} color="text.secondary">
            Interest on share capital is worked from each member&apos;s average month-end share capital in the year before the cut-off; the patronage refund from the loan interest, service fees and charges they paid that year.
            Choose how the rates are set, then identify them. Nothing is encoded yet.
          </Typography>
          <ToggleButtonGroup exclusive size="small" value={method} onChange={(_, v) => { if (v) { setMethod(v); setAsked(null) } }} aria-label="How the rates are set" sx={{ mb: 1.5 }}>
            <ToggleButton value="rates" sx={{ textTransform: 'none' }}>Type the rates</ToggleButton>
            <ToggleButton value="allocation" sx={{ textTransform: 'none' }}>From the approved allocation</ToggleButton>
          </ToggleButtonGroup>
          <Box sx={{ display: 'flex', gap: 1.5, alignItems: 'flex-start', flexWrap: 'wrap', mb: 1.5 }}>
            <TextField size="small" type="date" label="Cut-off date" value={cutoff} onChange={(e) => { setCutoff(e.target.value); setAsked(null) }} slotProps={{ inputLabel: { shrink: true } }} />
            {method === 'rates' && (<>
              <TextField size="small" label="Dividend rate %" value={dividendRate} onChange={num(setDividendRate)} sx={{ width: 140 }} />
              <TextField size="small" label="Patronage refund rate %" value={patronageRate} onChange={num(setPatronageRate)} sx={{ width: 180 }} />
            </>)}
          </Box>
          {method === 'allocation' && (
            <Box sx={{ border: 1, borderColor: 'divider', borderRadius: '5px', p: 1.5, mb: 1.5 }}>
              {label('Net surplus and allocation')}
              <Typography sx={{ fontSize: 12, mb: 1 }} color="text.secondary">
                Enter the approved net surplus, the statutory funds set aside from it (name and percent as the Cooperative Code, the CDA rules and your bylaws require; none are built in),
                and the amounts the general assembly allocated. Interest on share capital and patronage refund are then spread over the members.
              </Typography>
              <Box sx={{ display: 'flex', gap: 1.5, flexWrap: 'wrap', mb: 1 }}>
                <TextField size="small" label="Net surplus" value={netSurplus} onChange={num(setNetSurplus)} sx={{ width: 190 }} />
                <TextField size="small" label="General assembly resolution" value={resolution} onChange={num(setResolution)} helperText="Its number or date" sx={{ width: 230 }} />
              </Box>
              {funds.map((f, i) => (
                <Box key={i} sx={{ display: 'flex', gap: 1, mb: 0.75, alignItems: 'center' }}>
                  <TextField size="small" label="Statutory fund" value={f.name} onChange={(e) => { setFunds(funds.map((x, k) => (k === i ? { ...x, name: e.target.value } : x))); setAsked(null) }} sx={{ width: 260 }} />
                  <TextField size="small" label="% of net surplus" value={f.percent} onChange={(e) => { setFunds(funds.map((x, k) => (k === i ? { ...x, percent: e.target.value } : x))); setAsked(null) }} sx={{ width: 150 }} />
                  <IconButton size="small" aria-label={`Remove ${f.name || 'fund'}`} onClick={() => { setFunds(funds.filter((_, k) => k !== i)); setAsked(null) }}><FiTrash2 /></IconButton>
                </Box>
              ))}
              <Button size="small" startIcon={<FiPlus />} onClick={() => { setFunds([...funds, { name: '', percent: '' }]); setAsked(null) }} sx={{ mb: 1 }}>Add statutory fund</Button>
              <Typography sx={{ fontSize: 13, mb: 1 }}>Available for distribution: <b>{availableNow === null ? '—' : money(availableNow)}</b></Typography>
              <Box sx={{ display: 'flex', gap: 1.5, flexWrap: 'wrap' }}>
                <TextField size="small" label="Allocated to interest on share capital" value={iscAllocation} onChange={num(setIscAllocation)} sx={{ width: 270 }} />
                <TextField size="small" label="Allocated to patronage refund" value={prAllocation} onChange={num(setPrAllocation)} sx={{ width: 250 }} />
              </Box>
            </Box>
          )}
          <Button variant="contained" disabled={preview.isFetching} onClick={identify}>Identify</Button>
          {preview.isLoading && <Skeleton variant="rounded" height={60} sx={{ mt: 1.5 }} />}
          {preview.error && <Alert severity="warning" sx={{ mt: 1.5 }}>{(preview.error as Error).message}</Alert>}
        </>
      )}

      {step === 1 && (
        <>
          {label('Step 2: review the figures')}
          {preview.isLoading && <Skeleton variant="rounded" height={160} />}
          {preview.error && <Alert severity="warning" sx={{ mb: 1 }}>{(preview.error as Error).message}</Alert>}
          {p && (
            <>
              <Typography sx={{ fontSize: 13, mb: 0.5 }}>Cut-off {p.cutoff}, shares and loans of {p.year} · {p.members.toLocaleString()} members · <b>{money(p.dividend + p.refund)}</b> to distribute</Typography>
              <Box sx={{ display: 'grid', gridTemplateColumns: 'auto auto', justifyContent: 'start', columnGap: 3, rowGap: 0.25, fontSize: 13, mb: 1 }}>
                <span>Total average share-month</span><span>{money(p.totalAverageShareMonth)}</span>
                <span>Interest on share capital rate</span><span>{pct(p.dividendRate)}</span>
                <span>Interest on share capital</span><span>{money(p.dividend)}</span>
                <span>Total qualified patronage</span><span>{money(p.totalQualifiedPatronage)}</span>
                <span>Patronage refund rate</span><span>{pct(p.patronageRate)}</span>
                <span>Patronage refund</span><span>{money(p.refund)}</span>
              </Box>
              {p.fromAllocation && (
                <Box sx={{ border: 1, borderColor: 'divider', borderRadius: '5px', p: 1, mb: 1, fontSize: 13 }}>
                  <Typography sx={{ fontWeight: 700, fontSize: 13, mb: 0.5 }}>Reconciliation with the approved allocation</Typography>
                  <Box sx={{ display: 'grid', gridTemplateColumns: 'auto auto auto auto', justifyContent: 'start', columnGap: 3, rowGap: 0.25 }}>
                    <span /><b>Allocated</b><b>Distributed</b><b>Difference</b>
                    <span>Interest on share capital</span><span>{money(p.iscAllocation ?? 0)}</span><span>{money(p.dividend)}</span><span>{money((p.iscAllocation ?? 0) - p.dividend)}</span>
                    <span>Patronage refund</span><span>{money(p.prAllocation ?? 0)}</span><span>{money(p.refund)}</span><span>{money((p.prAllocation ?? 0) - p.refund)}</span>
                  </Box>
                  <Typography sx={{ fontSize: 12, mt: 0.5 }} color="text.secondary">
                    Available for distribution after the statutory funds: {money(p.availableForDistribution ?? 0)}. A small difference is the centavo each member is rounded to, or members left out below.
                  </Typography>
                </Box>
              )}
              {p.controls.map((c) => <Alert key={c} severity="error" sx={{ mb: 1 }}>{c}</Alert>)}
              {p.alreadyEncoded && <Alert severity="info" sx={{ mb: 1 }}>{p.year + 1} already has dividend vouchers, so it cannot be encoded again.</Alert>}
              {p.heldCount > 0 && (
                <Alert severity="warning" sx={{ mb: 1 }}>
                  {p.heldCount} member(s) are left out because they have no active savings or share capital account to credit
                  {p.held.length > 0 && <>: {p.held.map((h) => `${h.clientId} (${money(h.total)})`).join(', ')}{p.heldCount > p.held.length ? ', …' : ''}</>}.
                </Alert>
              )}
              <Box sx={{ display: 'flex', gap: 1, alignItems: 'center', flexWrap: 'wrap', mb: 1.5 }}>
                <Button variant="outlined" size="small" disabled={register.isPending} onClick={() => register.mutate()}>Download register (CSV)</Button>
                <TextField size="small" label="Member no." value={statementOf} onChange={(e) => setStatementOf(e.target.value.replace(/\D/g, ''))} sx={{ width: 130 }} />
                <Button variant="outlined" size="small" disabled={!statementOf || showStatement.isPending} onClick={() => showStatement.mutate()}>Member statement</Button>
              </Box>
            </>
          )}
          <Box sx={{ display: 'flex', gap: 1 }}>
            <Button onClick={() => setStep(0)}>Back</Button>
            <Button variant="contained" disabled={!p || p.controls.length > 0} onClick={() => setStep(2)}>Next: allocation</Button>
          </Box>
        </>
      )}

      {step === 2 && p && (
        <>
          {label('Step 3: allocation')}
          <Typography sx={{ fontSize: 12, mb: 1 }} color="text.secondary">
            With the dividend and refund known, direct part of each member&apos;s share to the mutual benefit fund and to hospitalization assistance. What is left goes to the member&apos;s savings,
            or to share capital when there is no savings account. Leave both empty to send it all there.
          </Typography>
          <Box sx={{ display: 'flex', gap: 1.5, flexWrap: 'wrap', mb: 1 }}>
            <TextField size="small" label="Mutual benefit per member" value={mutualBenefit} onChange={(e) => { setMutualBenefit(e.target.value); setSplitEdited(true) }} sx={{ width: 190 }} />
            <TextField size="small" label="Hospitalization per member" value={hospitalization} onChange={(e) => { setHospitalization(e.target.value); setSplitEdited(true) }} sx={{ width: 200 }} />
          </Box>
          <TextField size="small" multiline minRows={2} maxRows={6} fullWidth sx={{ mb: 1 }} label="Different insurance amounts for some members (optional)" value={overrides} onChange={(e) => { setOverrides(e.target.value); setSplitEdited(true) }}
            placeholder={'one member per line: member no., mutual benefit, hospitalization (blank or - keeps the standard)\n12345, 402.17, 697.83'} />
          <Button variant="outlined" size="small" disabled={!splitEdited || preview.isFetching} onClick={() => { setError(null); const r = build(); if (r) { setAsked(r); setSplitEdited(false) } }} sx={{ mb: 1.5 }}>Apply to the allocation</Button>
          <Box sx={{ display: 'grid', gridTemplateColumns: 'auto auto', justifyContent: 'start', columnGap: 3, rowGap: 0.25, fontSize: 13, mb: 1.5 }}>
            <span>Dividend and patronage refund</span><span>{money(p.dividend + p.refund)}</span>
            <span>To mutual benefit</span><span>{money(p.mutualBenefit)}</span>
            <span>To hospitalization</span><span>{money(p.hospitalization)}</span>
            <span>To savings</span><span>{money(p.savings)}</span>
            <span>To share capital</span><span>{money(p.shareCapital)}</span>
          </Box>
          <Box sx={{ display: 'flex', gap: 1, alignItems: 'center', mb: 2 }}>
            <Button onClick={() => setStep(1)}>Back</Button>
            {!p.alreadyEncoded && <Button variant="contained" disabled={encode.isPending || p.controls.length > 0 || splitEdited} onClick={() => encode.mutate()}>Encode {p.vouchers} vouchers</Button>}
            {splitEdited && <Typography sx={{ fontSize: 12 }} color="text.secondary">Apply the allocation first.</Typography>}
          </Box>
          {p.alreadyEncoded && <Alert severity="info" sx={{ mb: 1 }}>{p.year + 1} already has dividend vouchers, so it cannot be encoded again.</Alert>}
        </>
      )}

      {(vouchers.data ?? []).length > 0 && (
        <Box sx={{ mt: 1 }}>
          {label('Vouchers of this year')}
          {vouchers.data!.map((v) => (
            <Box key={`${v.year}-${v.controlNo}`} sx={{ display: 'flex', alignItems: 'center', gap: 1.5, py: 0.9, borderTop: '1px solid', borderColor: 'divider' }}>
              <Box sx={{ flex: 1, minWidth: 0 }}>
                <Typography sx={{ fontSize: 13 }}>{v.documentNo} · {money(v.total)}</Typography>
                <Typography sx={{ fontSize: 12 }} color="text.secondary">{v.lines.toLocaleString()} lines</Typography>
              </Box>
              <Chip size="small" variant="outlined" color={statusColor(v.status)} label={v.status === 'WAITING FOR APPROVAL' ? 'Waiting for approval' : v.status === 'APPROVED' ? 'Approved' : 'Posted'} />
              {v.status === 'WAITING FOR APPROVAL' && <Button size="small" variant="contained" disabled={act.isPending} onClick={() => act.mutate({ kind: 'approve', r: v })}>Approve</Button>}
              {v.status === 'APPROVED' && (
                <ConfirmButton size="small" variant="contained" color="success" disabled={act.isPending} onConfirm={() => act.mutate({ kind: 'post', r: v })} title={`Post dividend voucher ${v.documentNo}?`}
                  message={<>It credits <b>{money(v.total)}</b> to members’ accounts in {v.lines.toLocaleString()} lines. A posted voucher cannot be edited.</>}>Post</ConfirmButton>
              )}
            </Box>
          ))}
        </Box>
      )}
    </Box>
  )
}
