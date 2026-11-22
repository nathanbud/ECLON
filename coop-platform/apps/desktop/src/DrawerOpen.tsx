import { Alert, Box, Button, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography } from '@mui/material'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { getDrawerOpen, getDrawersOpenedToday, openDrawer, type DrawerOpened, type FoSetup } from './api'
import { CountGrid, countTotal, toNumbers, type Counts } from './CountGrid'
import { glass } from './glass'
import { money } from './ui/format'

/**
 * Opening the drawer at the start of the business day: a blind count, like the end-of-day one (the ledger's figure is shown only after the count is
 * submitted). Cash at the window waits until the drawer is opened, while the business day is in use and the rule is on (Business Day screen). A
 * difference needs a reason and is recorded; the drawer opens either way. The head cashier also sees every drawer opened today.
 */
export default function DrawerOpen({ setup, head }: { setup: FoSetup; head: boolean }) {
  const qc = useQueryClient()
  const mine = useQuery({ queryKey: ['drawer-open'], queryFn: getDrawerOpen, staleTime: 15_000, retry: false })
  const day = useQuery({ queryKey: ['drawer-open-day'], queryFn: getDrawersOpenedToday, enabled: head, staleTime: 15_000, retry: false })
  const [counts, setCounts] = useState<Counts>({})
  const [reason, setReason] = useState('')
  const [askReason, setAskReason] = useState(false)
  const [result, setResult] = useState<DrawerOpened | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const m = mine.data
  if (!m || !m.available) return null
  const closed = m.drawers.find((d) => !d.opened)
  const opened = m.drawers.filter((d) => d.opened)
  if (!closed && !head && !result) {
    // nothing to do: say once that the drawer is open
    return opened.length ? (
      <Typography sx={{ fontSize: 12, flexShrink: 0 }} color="text.secondary">
        {opened.map((d) => `${d.label} opened at ${d.openedAt}${d.difference ? ` (${d.difference < 0 ? 'short' : 'over'} ${money(Math.abs(d.difference))})` : ''}`).join(' · ')}
      </Typography>
    ) : null
  }

  const counted = countTotal(setup, counts)
  const entered = Object.values(counts).some((v) => v)
  const submit = async () => {
    if (!closed) return
    setBusy(true); setError(null)
    try {
      const r = await openDrawer(closed.event, toNumbers(counts), reason)
      setResult(r); setAskReason(false); setReason(''); setCounts({})
      void qc.invalidateQueries({ queryKey: ['drawer-open'] })
      void qc.invalidateQueries({ queryKey: ['drawer-open-day'] })
    } catch (e) {
      const msg = (e as Error).message
      if (msg.includes('does not match')) setAskReason(true)
      setError(msg)
    } finally { setBusy(false) }
  }

  return (
    <Box sx={(t) => ({ p: 1.5, flexShrink: 0, ...glass(t) })}>
      <Typography sx={{ fontSize: 12, fontWeight: 700, letterSpacing: '0.06em', textTransform: 'uppercase', mb: 1 }} color="text.secondary">Open my drawer</Typography>
      {result && (
        <Alert severity={result.difference === 0 ? 'success' : 'warning'} sx={{ mb: 1.25 }} onClose={() => setResult(null)}>
          {result.label} opened at {result.openedAt}: {result.difference === 0 ? 'the count matches the ledger.' : `${result.difference < 0 ? 'short' : 'over'} ${money(Math.abs(result.difference))}, recorded. The ledger expected ${money(result.expected)}; you counted ${money(result.counted)}.`}
        </Alert>
      )}
      {closed && (
        <>
          <Typography sx={{ fontSize: 13, mb: 1 }}>
            {m.required ? `Count ${closed.label} before taking or paying out cash today (${m.businessDate}).` : `Count ${closed.label} at the start of the day (${m.businessDate}).`}
            {' '}The expected amount is shown after you submit.
          </Typography>
          <Box sx={{ mb: 1 }}><CountGrid setup={setup} counts={counts} onChange={setCounts} onSubmit={() => { if (!busy) void submit() }} /></Box>
          {error && <Alert severity={askReason ? 'warning' : 'error'} sx={{ mb: 1 }} role="alert">{error}</Alert>}
          {askReason && <TextField fullWidth size="small" label="Reason for the difference" value={reason} onChange={(e) => setReason(e.target.value)} sx={{ mb: 1 }} slotProps={{ htmlInput: { maxLength: 200 } }} />}
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 2 }}>
            <Typography sx={{ fontSize: 13 }}>Counted <b style={{ fontVariantNumeric: 'tabular-nums' }}>{money(counted)}</b>{!entered ? ' (an empty drawer)' : ''}</Typography>
            <Box sx={{ flex: 1 }} />
            <Button variant="contained" disabled={busy || (askReason && reason.trim().length < 5)} onClick={() => { void submit() }}>{busy ? 'Opening…' : 'Open the drawer'}</Button>
          </Box>
        </>
      )}
      {head && (day.data?.length ?? 0) > 0 && (
        <Table size="small" aria-label="Drawers opened today" sx={{ mt: closed ? 1.5 : 0 }}>
          <TableHead><TableRow><TableCell>Drawer</TableCell><TableCell>Opened by</TableCell><TableCell>At</TableCell><TableCell align="right">Counted</TableCell><TableCell align="right">Difference</TableCell><TableCell>Reason</TableCell></TableRow></TableHead>
          <TableBody>
            {day.data!.map((r) => (
              <TableRow key={r.label}>
                <TableCell>{r.label}</TableCell><TableCell>{r.person}</TableCell><TableCell>{r.openedAt}</TableCell>
                <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>{money(r.counted)}</TableCell>
                <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>{r.difference === 0 ? '—' : money(r.difference)}</TableCell>
                <TableCell>{r.reason ?? ''}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
      {head && !closed && (day.data?.length ?? 0) === 0 && <Typography sx={{ fontSize: 13 }} color="text.secondary">No drawer has been opened today yet.</Typography>}
    </Box>
  )
}
