import { Alert, Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogContentText, DialogTitle, TextField, Typography } from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { askFoOverride, decideFoOverride, getFoOverrideForDraft, getFoOverridesPending, type FoOverride } from './api'
import { money } from './FoTransactionForm'
import { glass } from './glass'

/** For a supervisor: requests waiting for a decision. A supervisor cannot decide their own request (the server enforces it). */
export function OverridesCard() {
  const qc = useQueryClient()
  const pending = useQuery({ queryKey: ['fo-overrides-pending'], queryFn: getFoOverridesPending, refetchInterval: 20_000 })
  const [rejecting, setRejecting] = useState<FoOverride | null>(null)
  const [note, setNote] = useState('')
  const [error, setError] = useState<string | null>(null)
  const decide = useMutation({
    mutationFn: (v: { o: FoOverride; approve: boolean; note: string }) => decideFoOverride(v.o.id, v.approve, v.note),
    onSuccess: () => { setError(null); setRejecting(null); setNote(''); void qc.invalidateQueries({ queryKey: ['fo-overrides-pending'] }); void qc.invalidateQueries({ queryKey: ['fo-pulse'] }) },
    onError: (e) => setError((e as Error).message),
  })
  const rows = pending.data ?? []
  if (rows.length === 0) return null
  return (
    <Box sx={(t) => ({ p: 1.5, flexShrink: 0, ...glass(t) })}>
      <Typography sx={{ fontSize: 12, fontWeight: 700, letterSpacing: '0.06em', textTransform: 'uppercase', mb: 0.75 }} color="text.secondary">Waiting for your approval</Typography>
      {error && <Alert severity="error" sx={{ mb: 1 }} onClose={() => setError(null)}>{error}</Alert>}
      {rows.map((o) => (
        <Box key={o.id} sx={{ display: 'flex', alignItems: 'center', gap: 1.5, py: 0.75, borderTop: '1px solid', borderColor: 'divider' }}>
          <Box sx={{ flex: 1, minWidth: 0 }}>
            <Typography sx={{ fontSize: 14 }}>{o.description}</Typography>
            <Typography sx={{ fontSize: 12 }} color="text.secondary">
              {o.requestedBy} · {o.requestedAt} · {money(o.amount)}{o.limitAmount ? ` against a limit of ${money(o.limitAmount)}` : ''}
            </Typography>
          </Box>
          <Button size="small" variant="contained" disabled={decide.isPending} onClick={() => decide.mutate({ o, approve: true, note: '' })}>Approve</Button>
          <Button size="small" color="inherit" disabled={decide.isPending} onClick={() => { setRejecting(o); setNote('') }}>Reject</Button>
        </Box>
      ))}
      <Dialog open={!!rejecting} onClose={() => setRejecting(null)} fullWidth maxWidth="xs">
        <DialogTitle>Reject this request?</DialogTitle>
        <DialogContent>
          <DialogContentText sx={{ mb: 1.5 }}>{rejecting?.description}</DialogContentText>
          <TextField autoFocus fullWidth size="small" label="Reason" value={note} onChange={(e) => setNote(e.target.value)} />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setRejecting(null)}>Cancel</Button>
          <Button color="error" variant="contained" disabled={note.trim().length < 3 || decide.isPending} onClick={() => rejecting && decide.mutate({ o: rejecting, approve: false, note: note.trim() })}>Reject</Button>
        </DialogActions>
      </Dialog>
    </Box>
  )
}

/**
 * Shown on a saved draft that is over the person's limit: ask a supervisor, and see whether they approved. The approval is recorded
 * for when posting of these transactions is built; nothing is posted by it.
 */
export function OverrideAsk({ draftId, amount, limit, summary }: { draftId: number; amount: number; limit: number; summary: string }) {
  const qc = useQueryClient()
  const current = useQuery({ queryKey: ['fo-override', draftId], queryFn: () => getFoOverrideForDraft(draftId), refetchInterval: 15_000 })
  const [error, setError] = useState<string | null>(null)
  const ask = useMutation({
    mutationFn: () => askFoOverride({ draftId, kind: 'OVER_LIMIT', description: summary, amount, limitAmount: limit }),
    onSuccess: () => { setError(null); void qc.invalidateQueries({ queryKey: ['fo-override', draftId] }); void qc.invalidateQueries({ queryKey: ['fo-pulse'] }) },
    onError: (e) => setError((e as Error).message),
  })
  const o = current.data
  return (
    <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, flexWrap: 'wrap' }}>
      {o && <Chip size="small" variant="outlined" color={o.status === 'APPROVED' ? 'success' : o.status === 'REJECTED' ? 'error' : 'warning'}
        label={o.status === 'APPROVED' ? `Approved by ${o.decidedBy}` : o.status === 'REJECTED' ? `Rejected by ${o.decidedBy}${o.note ? `: ${o.note}` : ''}` : 'Waiting for a supervisor'} />}
      {(!o || o.status === 'REJECTED') && <Button size="small" variant="outlined" disabled={ask.isPending} onClick={() => ask.mutate()}>{ask.isPending ? 'Asking…' : 'Ask a supervisor'}</Button>}
      {error && <Typography sx={{ fontSize: 12.5 }} color="error">{error}</Typography>}
    </Box>
  )
}
