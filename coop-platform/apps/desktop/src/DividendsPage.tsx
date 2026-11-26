import { Box, Typography } from '@mui/material'
import { useState } from 'react'
import { Segmented } from './appleUi'
import AppropriationTab from './AppropriationTab'
import { DistributionList, DistributionSettingsView, DistributionsFrame } from './Distributions'
import { DividendPanel } from './DividendPanel'
import { appBackground } from './glass'

type Part = 'appropriation' | 'list' | 'prepare' | 'settings'

/**
 * The yearly interest on share capital (dividend) and patronage refund (Accounting > Dividends): Appropriation sets the closed year's net surplus aside to the statutory
 * funds and allocates what members receive (RA 9520 Article 86), posted as a journal; Prepare works a year out and stores it; Distributions lists every
 * year's versions, where one is submitted, approved by someone else, made into vouchers (approved and posted by two more people) and reported on; Settings holds
 * what counts and who takes part.
 */
export default function DividendsPage({ initialTab }: { initialTab?: string }) {
  const [part, setPart] = useState<Part>(initialTab?.toLowerCase().startsWith('appropriation') ? 'appropriation' : 'list')
  const [openId, setOpenId] = useState<number | null>(null)
  return (
    <Box component="main" sx={(t) => ({ minHeight: 'calc(100dvh - 44px)', p: { xs: 2, md: 3 }, background: appBackground(t) })}>
      <Box sx={{ maxWidth: 1100, mx: 'auto' }}>
        <Typography component="h1" sx={{ fontSize: 20, fontWeight: 700, letterSpacing: '-0.02em', mb: 1.5 }}>Dividends and patronage refund</Typography>
        <Box sx={{ mb: 1.5 }}>
          <Segmented label="Dividends" value={part} onChange={(v) => { setPart(v); if (v !== 'list') setOpenId(null) }}
            options={[{ value: 'appropriation', label: 'Appropriation' }, { value: 'list', label: 'Distributions' }, { value: 'prepare', label: 'Prepare' }, { value: 'settings', label: 'Settings' }]} />
        </Box>
        {part === 'appropriation' && <AppropriationTab />}
        {part === 'prepare' && <DividendPanel onPrepared={(id) => { setOpenId(id); setPart('list') }} />}
        {part === 'list' && <DistributionsFrame><DistributionList openId={openId} onOpen={setOpenId} /></DistributionsFrame>}
        {part === 'settings' && <DistributionsFrame><DistributionSettingsView /></DistributionsFrame>}
      </Box>
    </Box>
  )
}
