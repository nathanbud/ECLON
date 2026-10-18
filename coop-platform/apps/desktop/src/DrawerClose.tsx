import { Alert, Box, Button, TextField, Typography } from '@mui/material'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { closeFoDrawer, getFoHandoverMine, type FoCloseResult, type FoSetup } from './api'
import { CountGrid, countTotal, toNumbers, type Counts } from './CountGrid'
import { money } from './FoTransactionForm'
import { glass } from './glass'

/**
 * End-of-day count of the person's own drawer. A blind count: the figure the ledger expects is not shown until the count is
 * submitted, so the count is a real check and not a copy of the number. A difference needs a reason and is recorded.
 */
export default function DrawerClose({ setup, date }: { setup: FoSetup; date: string }) {
  const qc = useQueryClient()
  const mine = useQuery({ queryKey: ['fo-handover-mine'], queryFn: getFoHandoverMine, staleTime: 15_000 })
  const drawer = mine.data?.drawers[0]
  const [counts, setCounts] = useState<Counts>({})
  const [reason, setReason] = useState('')
  const [askReason, setAskReason] = useState(false)
  const [result, setResult] = useState<FoCloseResult | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  // Counting only makes sense for today: the ledger figure it is checked against is today's.
  if (!drawer || date !== setup.systemDate) return null

  const counted = countTotal(setup, counts)
  const entered = Object.values(counts).some((v) => v)
  const done = result ?? (drawer.closedAt ? { expected: 0, counted: 0, variance: drawer.variance ?? 0, closedAt: drawer.closedAt } : null)

  const submit = async () => {
    setBusy(true)
    setError(null)
    try {
      const r = await closeFoDrawer(drawer.event, toNumbers(counts), reason)
      setResult(r); setAskReason(false); setReason(''); setCounts({})
      void qc.invalidateQueries({ queryKey: ['fo-handover-mine'] })
      void qc.invalidateQueries({ queryKey: ['fo-handover-drawers'] })
    } catch (e) {
      const m = (e as Error).message
      if (m.includes('does not match')) { setAskReason(true); setError(m) } else setError(m)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Box sx={(t) => ({ p: 1.5, flexShrink: 0, ...glass(t) })}>
      <Typography sx={{ fontSize: 12, fontWeight: 700, letterSpacing: '0.06em', textTransform: 'uppercase', mb: 1 }} color="text.secondary">Count the drawer</Typography>
      {done && (
        <Alert severity={done.variance === 0 ? 'success' : 'warning'} sx={{ mb: 1.25 }} onClose={result ? () => setResult(null) : undefined}>
          Counted at {done.closedAt}: {done.variance === 0 ? 'balanced.' : `${done.variance < 0 ? 'short' : 'over'} ${money(Math.abs(done.variance))}, recorded.`}
          {result && result.variance !== 0 && ` The ledger expected ${money(result.expected)}; you counted ${money(result.counted)}.`}
        </Alert>
      )}
      <Typography sx={{ fontSize: 12.5, mb: 1 }} color="text.secondary">Count every note and coin first. The expected amount is shown after you submit.</Typography>
      <Box sx={{ mb: 1 }}><CountGrid setup={setup} counts={counts} onChange={setCounts} onSubmit={() => { if (entered && !busy) void submit() }} /></Box>
      {error && <Alert severity={askReason ? 'warning' : 'error'} sx={{ mb: 1 }} role="alert">{error}</Alert>}
      {askReason && (
        <TextField fullWidth size="small" label="Reason for the difference" value={reason} onChange={(e) => setReason(e.target.value)} sx={{ mb: 1 }} slotProps={{ htmlInput: { maxLength: 200 } }} />
      )}
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 2 }}>
        <Typography sx={{ fontSize: 13.5 }}>Counted <b style={{ fontVariantNumeric: 'tabular-nums' }}>{money(counted)}</b></Typography>
        <Box sx={{ flex: 1 }} />
        <Button variant="contained" disabled={!entered || busy || (askReason && reason.trim().length < 5)} onClick={() => { void submit() }}>
          {busy ? 'Submitting…' : done ? 'Count again' : 'Submit count'}
        </Button>
      </Box>
    </Box>
  )
}
