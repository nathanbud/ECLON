import { Alert, Button, Dialog, DialogActions, DialogContent, DialogTitle, Stack, TextField, Typography, type ButtonProps } from '@mui/material'
import { useState, type ReactNode } from 'react'

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

/**
 * A button for an action that cannot be taken back (posting to the ledger, closing a period): it first shows what will happen and asks.
 * The dialog closes when the action is confirmed; the screen shows the result or the server's refusal as before.
 */
export function ConfirmButton({ title, message, confirmLabel, onConfirm, children, ...button }: ButtonProps & {
  title: string
  /** What will happen, in plain words: amounts, who it affects, and that it cannot be edited afterwards. */
  message: ReactNode
  confirmLabel?: string
  onConfirm: () => void
}) {
  const [open, setOpen] = useState(false)
  return (
    <>
      <Button {...button} onClick={() => setOpen(true)}>{children}</Button>
      {open && (
        <Dialog open onClose={() => setOpen(false)} maxWidth="xs" fullWidth>
          <DialogTitle>{title}</DialogTitle>
          <DialogContent>
            <Typography component="div">{message}</Typography>
          </DialogContent>
          <DialogActions>
            <Button onClick={() => setOpen(false)}>Back</Button>
            <Button variant="contained" color={button.color === 'warning' || button.color === 'error' ? button.color : 'primary'}
              onClick={() => { setOpen(false); onConfirm() }}>{confirmLabel ?? (typeof children === 'string' ? children : 'Confirm')}</Button>
          </DialogActions>
        </Dialog>
      )}
    </>
  )
}
