import { Box, Typography } from '@mui/material'
import { DividendPanel } from './DividendPanel'
import { appBackground } from './glass'

/** The yearly dividend on share capital and patronage refund (Accounting). The accountant works it here; another person approves and a third posts. */
export default function DividendsPage() {
  return (
    <Box component="main" sx={(t) => ({ minHeight: 'calc(100dvh - 44px)', p: { xs: 2, md: 3 }, background: appBackground(t) })}>
      <Box sx={{ maxWidth: 1100, mx: 'auto' }}>
        <Typography component="h1" variant="h5" sx={{ fontWeight: 700, mb: 1.5 }}>Dividends and patronage refund</Typography>
        <DividendPanel />
      </Box>
    </Box>
  )
}
