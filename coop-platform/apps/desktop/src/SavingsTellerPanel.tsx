import { Box, Button, Tab, Tabs, Typography } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { getLookups, getMember } from './api'
import { displayName, memberNo, type Id } from './clients'
import { SavingsTab } from './profile/Savings'
import { ShareCapitalTab } from './profile/ShareCapital'
import { TimeDepositsTab } from './profile/TimeDeposits'
import { FindBody } from './FindClientDialog'
import { glass } from './glass'
import LoansTab from './LoansTab'

const SECTIONS = ['Savings', 'Share Capital', 'Loans', 'Time Deposits'] as const

/**
 * Every cash deposit and withdrawal on a member's accounts happens here, in the teller window, and nowhere else: savings deposits and withdrawals, share capital payments and
 * withdrawals, loan payments, and time deposit placements, interest withdrawals and pre-terminations. The member's profile only shows the accounts. The posting rules (open
 * drawer, limits, holds, supervisor approvals) belong to the cashier signed in here.
 */
export default function MemberAccountsPanel() {
  const { data: lk } = useQuery({ queryKey: ['lookups'], queryFn: getLookups, staleTime: Infinity })
  const [id, setId] = useState<Id | null>(null)
  const [section, setSection] = useState<(typeof SECTIONS)[number]>('Savings')
  const member = useQuery({ queryKey: ['member', id], queryFn: () => getMember(id!.branchCode, id!.clientId), enabled: !!id })

  if (!id) {
    return (
      <Box sx={{ display: 'flex', justifyContent: 'center', pt: '6vh' }}>
        <Box sx={(t) => ({ width: '100%', maxWidth: 580, overflow: 'hidden', ...glass(t, { elevated: true }) })}>
          <Typography sx={{ px: 2, pt: 1.5, fontSize: 13 }} color="text.secondary">Find the member to take a deposit from or pay a withdrawal to.</Typography>
          <FindBody inline lk={lk} onPick={setId} />
        </Box>
      </Box>
    )
  }
  return (
    <Box sx={{ minWidth: 0 }}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, mb: 1 }}>
        <Typography sx={{ fontWeight: 700, fontSize: 16 }}>{member.data ? displayName(member.data) : '…'}</Typography>
        <Typography sx={{ fontSize: 13, fontVariantNumeric: 'tabular-nums' }} color="text.secondary">{memberNo(id)}</Typography>
        <Box sx={{ flex: 1 }} />
        <Button size="small" onClick={() => setId(null)}>Change member</Button>
      </Box>
      <Tabs value={section} onChange={(_, v) => setSection(v)} aria-label="Member accounts" sx={(t) => ({ minHeight: 38, px: 1, mb: 1, ...glass(t), '& .MuiTab-root': { minHeight: 38, textTransform: 'none' } })}>
        {SECTIONS.map((x) => <Tab key={x} value={x} label={x} />)}
      </Tabs>
      {section === 'Savings' && <SavingsTab id={id} teller />}
      {section === 'Share Capital' && <ShareCapitalTab id={id} teller />}
      {section === 'Loans' && <LoansTab branchCode={id.branchCode} clientId={id.clientId} teller />}
      {section === 'Time Deposits' && <TimeDepositsTab id={id} teller />}
    </Box>
  )
}
