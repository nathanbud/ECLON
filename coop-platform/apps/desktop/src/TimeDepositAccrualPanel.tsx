import { Alert, Box, Button, Checkbox, Chip, FormControlLabel, MenuItem, Skeleton, TextField, Typography } from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { approveAccrual, encodeAccrual, getAccrualPreview, getAccrualVouchers, postAccrual, type AccrualGroup, type AccrualVoucher } from './api'
import { money } from './FoTransactionForm'
import { glass } from './glass'
import { ConfirmButton } from './ui/dialogs'
import { AboutNote } from './appleUi'

const iso = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`

/** The date the old system would have run to: the end of last month for Lock In, the last December 31 for Short Term. */
function defaultDate(group: AccrualGroup) {
  const now = new Date()
  if (group === 'LOCK_IN') return iso(new Date(now.getFullYear(), now.getMonth(), 0))
  return `${now.getFullYear() - 1}-12-31`
}

const statusColor = (s: AccrualVoucher['status']) => (s === 'POSTED' ? 'success' : s === 'APPROVED' ? 'info' : 'warning')

/**
 * Interest accrued on time deposit certificates, as the old "Accrued Interest on Deposits (Computer Generated)": Lock In certificates to each month end, Short Term
 * ones once a year to December 31. A person starts each run: preview, encode the vouchers, then a second person approves and a third posts them.
 */
export function TimeDepositAccrualPanel() {
  const qc = useQueryClient()
  const [group, setGroup] = useState<AccrualGroup>('LOCK_IN')
  const [to, setTo] = useState(defaultDate('LOCK_IN'))
  const [asked, setAsked] = useState<{ group: AccrualGroup; to: string } | null>(null)
  const [skip, setSkip] = useState<number[]>([])
  const [error, setError] = useState<string | null>(null)
  const preview = useQuery({ queryKey: ['td-accrual-preview', asked], queryFn: () => getAccrualPreview(asked!.group, asked!.to), enabled: !!asked, retry: false })
  const vouchers = useQuery({ queryKey: ['td-accrual-vouchers', asked], queryFn: () => getAccrualVouchers(asked!.group, asked!.to), enabled: !!asked, retry: false, refetchInterval: 20_000 })
  const refresh = () => { void qc.invalidateQueries({ queryKey: ['td-accrual-preview'] }); void qc.invalidateQueries({ queryKey: ['td-accrual-vouchers'] }) }
  const encode = useMutation({ meta: { done: 'Accrual vouchers made. They now wait for approval.' },
    mutationFn: () => encodeAccrual({ group: asked!.group, to: asked!.to, types: (preview.data?.types ?? []).filter((t) => !t.alreadyEncoded && !skip.includes(t.typeCode)).map((t) => t.typeCode) }),
    onSuccess: () => { setError(null); refresh() },
    onError: (e) => setError((e as Error).message),
  })
  const act = useMutation({ meta: { done: (_, v) => ((v as { kind: string }).kind === 'approve' ? 'Voucher approved. Someone else can now post it.' : 'Accrual voucher posted.') },
    mutationFn: (v: { kind: 'approve' | 'post'; r: AccrualVoucher }) => (v.kind === 'approve' ? approveAccrual : postAccrual)(v.r.year, v.r.controlNo),
    onSuccess: () => { setError(null); refresh() },
    onError: (e) => setError((e as Error).message),
  })

  const p = preview.data
  const names = new Map((p?.types ?? []).map((t) => [t.typeCode, t.type]))
  const pending = (p?.types ?? []).filter((t) => !t.alreadyEncoded && !skip.includes(t.typeCode))
  return (
    <Box sx={(t) => ({ p: 1.5, flex: 1, minHeight: 0, overflow: 'auto', ...glass(t) })}>
      <Typography sx={{ fontSize: 12, fontWeight: 700, letterSpacing: '0.06em', textTransform: 'uppercase', mb: 0.75 }} color="text.secondary">Time deposit interest accruals</Typography>
      <AboutNote sx={{ mb: 1.5 }}>
        Interest earned so far on certificates, taken to the interest expense account. Nothing runs by itself: choose the period, look at the figures, then encode the vouchers.
        Another person approves and a third posts them.
      </AboutNote>
      <Box sx={{ display: 'flex', gap: 1.5, alignItems: 'flex-start', flexWrap: 'wrap', mb: 1.5 }}>
        <TextField select size="small" label="Certificates" value={group} sx={{ minWidth: 220 }} onChange={(e) => { const g = e.target.value as AccrualGroup; setGroup(g); setTo(defaultDate(g)); setAsked(null); setSkip([]) }}>
          <MenuItem value="LOCK_IN">Lock In (monthly)</MenuItem>
          <MenuItem value="SHORT_TERM">Short Term (year end)</MenuItem>
        </TextField>
        <TextField size="small" type="date" label="Accrue to" value={to} onChange={(e) => { setTo(e.target.value); setAsked(null) }} slotProps={{ inputLabel: { shrink: true } }}
          helperText={group === 'LOCK_IN' ? 'Last day of a month' : 'December 31'} />
        <Button variant="outlined" sx={{ mt: 0.25 }} disabled={!to} onClick={() => { setError(null); setSkip([]); setAsked({ group, to }) }}>Preview</Button>
      </Box>
      {error && <Alert severity="error" sx={{ mb: 1 }} onClose={() => setError(null)}>{error}</Alert>}
      {preview.isLoading && <Skeleton variant="rounded" height={80} />}
      {preview.error && <Alert severity="warning" sx={{ mb: 1 }}>{(preview.error as Error).message}</Alert>}
      {p && (
        <Box sx={{ mb: 2 }}>
          <Typography sx={{ fontSize: 13, mb: 0.5 }}>{p.accrualType}: {p.from} to {p.to} · {p.certificates} certificates · <b>{money(p.total)}</b></Typography>
          {p.withoutDepartment > 0 && <Alert severity="warning" sx={{ mb: 1 }}>{p.withoutDepartment} certificate owner(s) have no department and are left out until it is set.</Alert>}
          {p.types.length === 0 && <Typography color="text.secondary">No certificate earns interest for this period.</Typography>}
          {p.types.map((t) => (
            <Box key={t.typeCode} sx={{ display: 'flex', alignItems: 'center', gap: 1.5, py: 0.5, borderTop: '1px solid', borderColor: 'divider' }}>
              <FormControlLabel sx={{ flex: 1, m: 0 }} label={`${t.type} · ${t.certificates} certificates · ${money(t.total)}`}
                control={<Checkbox size="small" disabled={t.alreadyEncoded} checked={!t.alreadyEncoded && !skip.includes(t.typeCode)} onChange={(e) => setSkip(e.target.checked ? skip.filter((x) => x !== t.typeCode) : [...skip, t.typeCode])} />} />
              {t.alreadyEncoded && <Chip size="small" variant="outlined" label="Already has vouchers" />}
            </Box>
          ))}
          {pending.length > 0 && <Button sx={{ mt: 1 }} variant="contained" disabled={encode.isPending} onClick={() => encode.mutate()}>Encode vouchers</Button>}
        </Box>
      )}
      {(vouchers.data ?? []).length > 0 && (
        <>
          <Typography sx={{ fontSize: 12, fontWeight: 700, letterSpacing: '0.06em', textTransform: 'uppercase', mb: 0.5 }} color="text.secondary">Vouchers for this date</Typography>
          {vouchers.data!.map((v) => (
            <Box key={`${v.year}-${v.controlNo}`} sx={{ display: 'flex', alignItems: 'center', gap: 1.5, py: 0.9, borderTop: '1px solid', borderColor: 'divider' }}>
              <Box sx={{ flex: 1, minWidth: 0 }}>
                <Typography sx={{ fontSize: 13 }}>{v.documentNo} · {names.get(v.typeCode) ?? `Type ${v.typeCode}`} · {money(v.total)}</Typography>
                <Typography sx={{ fontSize: 12 }} color="text.secondary">{v.lines} lines</Typography>
              </Box>
              <Chip size="small" variant="outlined" color={statusColor(v.status)} label={v.status === 'WAITING FOR APPROVAL' ? 'Waiting for approval' : v.status === 'APPROVED' ? 'Approved' : 'Posted'} />
              {v.status === 'WAITING FOR APPROVAL' && <Button size="small" variant="contained" disabled={act.isPending} onClick={() => act.mutate({ kind: 'approve', r: v })}>Approve</Button>}
              {v.status === 'APPROVED' && (
                <ConfirmButton size="small" variant="contained" color="success" disabled={act.isPending} onConfirm={() => act.mutate({ kind: 'post', r: v })} title={`Post accrual voucher ${v.documentNo}?`}
                  message={<>It writes <b>{money(v.total)}</b> of accrued interest in {v.lines.toLocaleString()} lines. A posted voucher cannot be edited.</>}>Post</ConfirmButton>
              )}
            </Box>
          ))}
        </>
      )}
    </Box>
  )
}
