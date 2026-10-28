import { Box, Button, Typography } from '@mui/material'
import { closeThisWindow } from './detachedWindow'
import LoanSetupPage from './LoanSetupPage'

/**
 * Loan Setup in a window of its own, like the loan application: loan types (with their setup and COA links), purposes, collateral types and co-maker holds, and the amortization
 * reference, each a tab of the page. Opened from Settings; it asks the main window for the sign-in, so closing the main window or signing out closes this one too.
 */
export default function LoanSetupWindow() {
  const q = new URLSearchParams(window.location.hash.split('?')[1] ?? '')
  const tab = q.get('tab') === 'amortization' ? 'amortization' : 'types'
  return (
    <Box>
      <Box sx={(t) => ({ height: 44, px: 2, display: 'flex', alignItems: 'center', borderBottom: 1, borderColor: 'divider', bgcolor: t.palette.mode === 'dark' ? 'rgba(0,0,0,0.3)' : 'rgba(255,255,255,0.7)' })}>
        <Typography component="h1" sx={{ fontSize: 15, fontWeight: 700, flex: 1 }}>Loan Setup</Typography>
        <Button size="small" onClick={() => void closeThisWindow()}>Close</Button>
      </Box>
      <LoanSetupPage initialTab={tab} />
    </Box>
  )
}
