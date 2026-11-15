import { Button, Dialog, DialogActions, DialogContent, DialogContentText, DialogTitle } from '@mui/material'
import { useEffect, useState } from 'react'
import { closeThisWindow } from '../detachedWindow'
import { listenForEscape } from './back'

/**
 * In a window of its own (the loan application, Loan Setup), Esc is "close this window": it asks first, because what was typed there is not
 * kept. Enter closes; Esc again stays. As in the main window, Esc first closes whatever is open on top and leaves a field being typed in.
 */
export default function CloseOnEscape({ what }: { what: string }) {
  const [asking, setAsking] = useState(false)
  useEffect(() => listenForEscape(() => setAsking(true)), [])
  if (!asking) return null
  return (
    <Dialog open onClose={() => setAsking(false)} maxWidth="xs" fullWidth aria-labelledby="close-window-title">
      <DialogTitle id="close-window-title">Close the {what} window?</DialogTitle>
      <DialogContent><DialogContentText>Anything not saved in it is lost.</DialogContentText></DialogContent>
      <DialogActions>
        <Button onClick={() => setAsking(false)}>Stay</Button>
        <Button variant="contained" autoFocus onClick={() => { setAsking(false); void closeThisWindow() }}>Close window</Button>
      </DialogActions>
    </Dialog>
  )
}
