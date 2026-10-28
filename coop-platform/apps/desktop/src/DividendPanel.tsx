import { Alert, Box, Button, Chip, Skeleton, TextField, Typography } from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { approveDividend, encodeDividend, getDividendVouchers, postDividend, previewDividend, type DividendMemberAmounts, type DividendReq, type DividendVoucher } from './api'
import { money } from './FoTransactionForm'
import { glass } from './glass'

const statusColor = (s: DividendVoucher['status']) => (s === 'POSTED' ? 'success' : s === 'APPROVED' ? 'info' : 'warning')

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

/**
 * The yearly dividend on share capital and patronage refund on loan charges, as the old "Dividend and Patronage Refund Transaction (Computer Generated)". The accountant types
 * the two rates and the mutual benefit and hospitalization amounts; the screen shows the figures, then encodes the vouchers. Another person approves and a third posts them.
 */
export function DividendPanel() {
  const qc = useQueryClient()
  const year = new Date().getFullYear()
  const [cutoff, setCutoff] = useState(`${year}-02-21`)
  const [dividendRate, setDividendRate] = useState('8')
  const [patronageRate, setPatronageRate] = useState('8.23')
  const [mutualBenefit, setMutualBenefit] = useState('902.17')
  const [hospitalization, setHospitalization] = useState('864.06')
  const [overrides, setOverrides] = useState('')
  const [asked, setAsked] = useState<DividendReq | null>(null)
  const [error, setError] = useState<string | null>(null)

  const build = (): DividendReq | null => {
    const o = parseOverrides(overrides)
    if (o.error) { setError(o.error); return null }
    const nums = [dividendRate, patronageRate, mutualBenefit, hospitalization].map((x) => Number(x))
    if (!cutoff || nums.some((n) => !(n >= 0))) { setError('Give a cut-off date and the rates and amounts as numbers of zero or more.'); return null }
    return { cutoff, dividendRate: nums[0], patronageRate: nums[1], mutualBenefit: nums[2], hospitalization: nums[3], overrides: o.rows }
  }
  const preview = useQuery({ queryKey: ['dividend-preview', asked], queryFn: () => previewDividend(asked!), enabled: !!asked, retry: false, staleTime: Infinity })
  const vouchers = useQuery({ queryKey: ['dividend-vouchers', asked?.cutoff], queryFn: () => getDividendVouchers(asked!.cutoff), enabled: !!asked, retry: false, refetchInterval: 20_000 })
  const refresh = () => { void qc.invalidateQueries({ queryKey: ['dividend-preview'] }); void qc.invalidateQueries({ queryKey: ['dividend-vouchers'] }) }
  const encode = useMutation({ mutationFn: () => encodeDividend(asked!), onSuccess: () => { setError(null); refresh() }, onError: (e) => setError((e as Error).message) })
  const act = useMutation({
    mutationFn: (v: { kind: 'approve' | 'post'; r: DividendVoucher }) => (v.kind === 'approve' ? approveDividend : postDividend)(v.r.year, v.r.controlNo),
    onSuccess: () => { setError(null); refresh() },
    onError: (e) => setError((e as Error).message),
  })

  const p = preview.data
  const num = (set: (v: string) => void) => (e: React.ChangeEvent<HTMLInputElement>) => { set(e.target.value); setAsked(null) }
  return (
    <Box sx={(t) => ({ p: 1.5, flex: 1, minHeight: 0, overflow: 'auto', ...glass(t) })}>
      <Typography sx={{ fontSize: 12, fontWeight: 700, letterSpacing: '0.06em', textTransform: 'uppercase', mb: 0.75 }} color="text.secondary">Dividend and patronage refund</Typography>
      <Typography sx={{ fontSize: 13, mb: 1.5 }} color="text.secondary">
        The dividend is a rate of each member's average month-end share capital in the year before the cut-off; the patronage refund is a rate of the loan interest, service fees and charges they paid that year.
        Type the rates the general assembly set and the mutual benefit and hospitalization amounts (what is left goes to the member's savings, or to share capital without savings).
        Nothing runs by itself: look at the figures, encode the vouchers, then another person approves and a third posts them. A year can be encoded only once.
      </Typography>
      <Box sx={{ display: 'flex', gap: 1.5, alignItems: 'flex-start', flexWrap: 'wrap', mb: 1.5 }}>
        <TextField size="small" type="date" label="Cut-off date" value={cutoff} onChange={(e) => { setCutoff(e.target.value); setAsked(null) }} slotProps={{ inputLabel: { shrink: true } }} />
        <TextField size="small" label="Dividend rate %" value={dividendRate} onChange={num(setDividendRate)} sx={{ width: 140 }} />
        <TextField size="small" label="Patronage refund rate %" value={patronageRate} onChange={num(setPatronageRate)} sx={{ width: 180 }} />
        <TextField size="small" label="Mutual benefit per member" value={mutualBenefit} onChange={num(setMutualBenefit)} sx={{ width: 190 }} />
        <TextField size="small" label="Hospitalization per member" value={hospitalization} onChange={num(setHospitalization)} sx={{ width: 200 }} />
      </Box>
      <TextField size="small" multiline minRows={2} maxRows={6} fullWidth sx={{ mb: 1.5 }} label="Different amounts for some members (optional)" value={overrides} onChange={num(setOverrides)}
        placeholder={'one member per line: member no., mutual benefit, hospitalization (blank or - keeps the standard)\n12345, 402.17, 697.83'} />
      <Button variant="outlined" onClick={() => { setError(null); const r = build(); if (r) setAsked(r) }}>Preview</Button>
      {error && <Alert severity="error" sx={{ mt: 1.5 }} onClose={() => setError(null)}>{error}</Alert>}
      {preview.isLoading && <Skeleton variant="rounded" height={90} sx={{ mt: 1.5 }} />}
      {preview.error && <Alert severity="warning" sx={{ mt: 1.5 }}>{(preview.error as Error).message}</Alert>}
      {p && (
        <Box sx={{ mt: 2, mb: 2 }}>
          <Typography sx={{ fontSize: 14, mb: 0.5 }}>Cut-off {p.cutoff}, shares and loans of {p.year} · {p.members.toLocaleString()} members · <b>{money(p.total)}</b></Typography>
          <Box sx={{ display: 'grid', gridTemplateColumns: 'auto auto', justifyContent: 'start', columnGap: 3, rowGap: 0.25, fontSize: 14, mb: 1 }}>
            <span>Dividend on share capital</span><span>{money(p.dividend)}</span>
            <span>Patronage refund</span><span>{money(p.refund)}</span>
            <span>To mutual benefit</span><span>{money(p.mutualBenefit)}</span>
            <span>To hospitalization</span><span>{money(p.hospitalization)}</span>
            <span>To savings</span><span>{money(p.savings)}</span>
            <span>To share capital</span><span>{money(p.shareCapital)}</span>
          </Box>
          {p.alreadyEncoded && <Alert severity="info" sx={{ mb: 1 }}>{p.year + 1} already has dividend vouchers, so it cannot be encoded again.</Alert>}
          {p.heldCount > 0 && (
            <Alert severity="warning" sx={{ mb: 1 }}>
              {p.heldCount} member(s) are left out because they have no active savings or share capital account to credit
              {p.held.length > 0 && <>: {p.held.map((h) => `${h.clientId} (${money(h.total)})`).join(', ')}{p.heldCount > p.held.length ? ', …' : ''}</>}.
            </Alert>
          )}
          {!p.alreadyEncoded && <Button variant="contained" disabled={encode.isPending} onClick={() => encode.mutate()}>Encode {p.vouchers} vouchers</Button>}
        </Box>
      )}
      {(vouchers.data ?? []).length > 0 && (
        <>
          <Typography sx={{ fontSize: 12, fontWeight: 700, letterSpacing: '0.06em', textTransform: 'uppercase', mb: 0.5 }} color="text.secondary">Vouchers of this year</Typography>
          {vouchers.data!.map((v) => (
            <Box key={`${v.year}-${v.controlNo}`} sx={{ display: 'flex', alignItems: 'center', gap: 1.5, py: 0.9, borderTop: '1px solid', borderColor: 'divider' }}>
              <Box sx={{ flex: 1, minWidth: 0 }}>
                <Typography sx={{ fontSize: 14 }}>{v.documentNo} · {money(v.total)}</Typography>
                <Typography sx={{ fontSize: 12 }} color="text.secondary">{v.lines.toLocaleString()} lines</Typography>
              </Box>
              <Chip size="small" variant="outlined" color={statusColor(v.status)} label={v.status === 'WAITING FOR APPROVAL' ? 'Waiting for approval' : v.status === 'APPROVED' ? 'Approved' : 'Posted'} />
              {v.status === 'WAITING FOR APPROVAL' && <Button size="small" variant="contained" disabled={act.isPending} onClick={() => act.mutate({ kind: 'approve', r: v })}>Approve</Button>}
              {v.status === 'APPROVED' && <Button size="small" variant="contained" color="success" disabled={act.isPending} onClick={() => act.mutate({ kind: 'post', r: v })}>Post</Button>}
            </Box>
          ))}
        </>
      )}
    </Box>
  )
}
