import { Alert, Button, Dialog, DialogActions, DialogContent, DialogTitle, Stack, TextField } from '@mui/material'
import { useState } from 'react'

/** Asks for a reason, then runs the action. */
export function ReasonDialog({ title, label, onRun, onClose }: { title: string; label: string; onRun: (note: string) => Promise<unknown>; onClose: () => void }) {
  const [note, setNote] = useState('')
  const [error, setError] = useState<string | null>(null)
  return (
    <Dialog open onClose={onClose} maxWidth="xs" fullWidth>
      <DialogTitle>{title}</DialogTitle>
      <DialogContent>
        <Stack spacing={1.5} sx={{ pt: 1 }}>
          {error && <Alert severity="error">{error}</Alert>}
          <TextField size="small" autoFocus label={label} value={note} onChange={(e) => setNote(e.target.value)} />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Back</Button>
        <Button variant="contained" disabled={!note.trim()} onClick={() => onRun(note.trim()).then(onClose, (e: Error) => setError(e.message))}>Confirm</Button>
      </DialogActions>
    </Dialog>
  )
}
