import { useQueryClient } from '@tanstack/react-query'
import { type Id } from '../clients'
import { approveChange, rejectChange, type Change, withdrawChange } from '../api'
import { useMe } from '../session'
import { useState } from 'react'
import { Alert, Box, Button, Dialog, DialogActions, DialogContent, DialogContentText, DialogTitle, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography } from '@mui/material'
import { glass } from '../glass'
import { FiCheckCircle, FiClock, FiEdit2, FiSave } from 'react-icons/fi'
import { when } from './format'

/** Re-reads everything a decision on a change can alter. */
const refreshAfterDecision = (qc: ReturnType<typeof useQueryClient>, id: Id) =>
  Promise.all(['member', 'member-details', 'pending-change'].map((k) => qc.invalidateQueries({ queryKey: [k, id] })).concat([
    qc.invalidateQueries({ queryKey: ['members'] }),
    qc.invalidateQueries({ queryKey: ['pending-count'] }),
    qc.invalidateQueries({ queryKey: ['changes'] }),
  ]))

/** A change is waiting for approval: shows what is proposed, and offers the actions this person is allowed. */
export function PendingBanner({ change, id, canApprove, onDone }: { change: Change; id: Id; canApprove: boolean; onDone: (message: string) => void }) {
  const qc = useQueryClient()
  const me = useMe()
  const mine = change.requestedBy.toLowerCase() === me.username.toLowerCase()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [rejecting, setRejecting] = useState(false)
  const [note, setNote] = useState('')

  const run = async (action: () => Promise<unknown>, done: string) => {
    setBusy(true)
    setError(null)
    try {
      await action()
      await refreshAfterDecision(qc, id)
      setRejecting(false)
      onDone(done)
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Box sx={(t) => ({ mb: 1.5, p: 2, ...glass(t), borderColor: 'warning.main' })} role="region" aria-label="Change waiting for approval">
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1 }}>
        <FiClock aria-hidden />
        <Typography sx={{ fontWeight: 600, fontSize: 13 }}>Waiting for approval</Typography>
      </Box>
      <Typography sx={{ fontSize: 13, mb: 1.25 }} color="text.secondary">
        {mine ? 'You' : change.requestedByName} asked for {change.items.length} change{change.items.length === 1 ? '' : 's'} on {when(change.requestedAt)}.
        {' '}{mine ? 'A Membership Officer who is not you has to approve it.' : canApprove ? '' : 'A Membership Officer has to approve it.'}
      </Typography>
      <Table size="small" aria-label="Proposed changes" sx={{ mb: 1.25 }}>
        <TableHead><TableRow><TableCell>Field</TableCell><TableCell>Now</TableCell><TableCell>Proposed</TableCell></TableRow></TableHead>
        <TableBody>
          {change.items.map((i) => (
            <TableRow key={i.column}>
              <TableCell>{i.label}</TableCell>
              <TableCell sx={{ color: 'text.secondary' }}>{i.fromText ?? '—'}</TableCell>
              <TableCell sx={{ fontWeight: 600 }}>{i.toText ?? '—'}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
      {error && <Alert severity="error" sx={{ mb: 1 }}>{error}</Alert>}
      <Box sx={{ display: 'flex', gap: 1, justifyContent: 'flex-end' }}>
        {mine && <Button size="small" disabled={busy} onClick={() => run(() => withdrawChange(change.id), 'Change withdrawn')}>Withdraw</Button>}
        {canApprove && !mine && (
          <>
            <Button size="small" color="error" disabled={busy} onClick={() => setRejecting(true)}>Reject</Button>
            <Button size="small" variant="contained" disabled={busy} startIcon={<FiCheckCircle />} onClick={() => run(() => approveChange(change.id), 'Change approved and saved')}>
              Approve
            </Button>
          </>
        )}
      </Box>

      <Dialog open={rejecting} onClose={() => setRejecting(false)} fullWidth maxWidth="xs" aria-labelledby="reject-title">
        <DialogTitle id="reject-title">Reject this change?</DialogTitle>
        <DialogContent>
          <DialogContentText sx={{ mb: 1.5 }}>Tell {change.requestedByName} why, so they can correct it.</DialogContentText>
          <TextField label="Reason" value={note} onChange={(e) => setNote(e.target.value)} fullWidth autoFocus multiline minRows={2} />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setRejecting(false)}>Keep it</Button>
          <Button color="error" disabled={busy || note.trim().length < 3} onClick={() => run(() => rejectChange(change.id, note.trim()), 'Change rejected')}>Reject</Button>
        </DialogActions>
      </Dialog>
    </Box>
  )
}

/** Shown while a profile is being edited: what is pending, and the way to save or leave. */
/** `direct`: this person is a Membership Officer, so saving applies the changes at once (and logs them) instead of sending them for approval. */
export function EditBar({ changes, saving, error, direct = false, onSave, onCancel }: {
  changes: number; saving: boolean; error: string | null; direct?: boolean; onSave: () => void; onCancel: () => void
}) {
  return (
    <Box sx={{ mb: 1.5 }}>
      <Box sx={(t) => ({ display: 'flex', alignItems: 'center', gap: 1.5, px: 2, py: 1, ...glass(t) })}>
        <FiEdit2 aria-hidden />
        <Typography sx={{ flex: 1, fontSize: 13 }} role="status">
          Editing. {changes === 0 ? 'No changes yet.' : `${changes} change${changes === 1 ? '' : 's'} ready.`} {direct ? 'Saving applies them at once and records them in the audit log.' : 'Saving sends them to a Membership Officer for approval.'}
        </Typography>
        <Button size="small" onClick={onCancel} disabled={saving}>Cancel</Button>
        <Button size="small" variant="contained" startIcon={<FiSave />} onClick={onSave} disabled={saving || changes === 0}>
          {direct ? (saving ? 'Saving…' : 'Save changes') : (saving ? 'Sending…' : 'Send for approval')}
        </Button>
      </Box>
      {error && <Alert severity="error" sx={{ mt: 1 }}>{error}</Alert>}
    </Box>
  )
}
