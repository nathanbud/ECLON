import { getMember, type Lookups, type Member } from '../api'
import { BLANK_MEMBER, FRESH_GROUPS, GROUPS, TABS, TAB_KEYS } from './constants'
import { BillingTab, DetailsTab, HistoryTab, MembershipTab, PersonalTab } from './InfoTabs'
import { BeneficiariesTab } from './Beneficiaries'
import { ShareCapitalTab } from './ShareCapital'
import { SavingsTab } from './Savings'
import { TimeDepositsTab } from './TimeDeposits'
import DocumentsPanel from '../DocumentsPanel'
import LoansTab from '../LoansTab'
import { type Id } from '../clients'
import { type ReactNode, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Alert, Avatar, Box, Skeleton, Tab, Tabs, Typography } from '@mui/material'
import { FiUser } from 'react-icons/fi'
import { LIGHT, SIDE, glass } from '../glass'
import { tabIcon } from './navIcons'
import { ProfileHeader, Ring, SideFrame } from './ProfileHeader'
import { ProfileStrip } from './ProfileStrip'
import { StatTiles } from './StatTiles'

function TabContent({ tab, m, lk }: { tab: number; m: Member; lk?: Lookups }) {
  const key = TAB_KEYS[tab]
  if (key === 'membership') return <MembershipTab m={m} lk={lk} />
  if (key === 'personal') return <PersonalTab m={m} lk={lk} />
  if (key === 'history') return <HistoryTab id={m.id} />
  if (key === 'beneficiaries') return <BeneficiariesTab id={m.id} lk={lk} />
  if (key === 'billing') return <BillingTab id={m.id} />
  if (key === 'shares') return <ShareCapitalTab id={m.id} />
  if (key === 'savings') return <SavingsTab id={m.id} />
  if (key === 'timedeposits') return <TimeDepositsTab id={m.id} />
  if (key === 'documents') return <DocumentsPanel entityType="MEMBER" entityId={String(m.id.clientId)} />
  if (key === 'loans') return <LoansTab branchCode={m.id.branchCode} clientId={m.id.clientId} />
  return <DetailsTab id={m.id} tab={key} title={TABS[tab]} lk={lk} />
}

/**
 * The section menu in the dark panel: every section of the profile, one row each under its group (Info, Accounts, Other), the open one lit as a light bar. Each group is its own
 * vertical tab list so the arrow keys move within it, as the two tab strips did before.
 */
function SideTabs({ groups, tab, onChange }: { groups: { label: string; tabs: number[] }[]; tab: number; onChange: (tab: number) => void }) {
  return (
    <Box component="nav" aria-label="Profile sections" sx={{ pb: 1 }}>
      {groups.map((g) => (
        <Box key={g.label} sx={{ mt: 0.25 }}>
          <Typography component="h3" sx={{ px: 2, pt: 1, pb: 0.5, fontSize: 11, fontWeight: 700, letterSpacing: '0.08em', textTransform: 'uppercase', color: SIDE.muted }}>{g.label}</Typography>
          <Tabs
            orientation="vertical" value={g.tabs.includes(tab) ? tab : false} onChange={(_, v: number) => onChange(v)} aria-label={`${g.label} sections`}
            sx={{
              minHeight: 0,
              '& .MuiTabs-indicator': { display: 'none' },
              '& .MuiTab-root': {
                minHeight: 34, px: 2, justifyContent: 'flex-start', textAlign: 'left', textTransform: 'none', fontSize: 13.5, fontWeight: 400, maxWidth: 'none', gap: 1.25,
                color: SIDE.muted, borderRadius: 0, alignItems: 'center',
                '& .MuiTab-iconWrapper': { fontSize: 17, m: 0 },
                '&:hover': { backgroundColor: SIDE.hover, color: SIDE.text },
                '&.Mui-focusVisible': { outline: `2px solid ${SIDE.selected}`, outlineOffset: -2 },
              },
              '& .MuiTab-root.Mui-selected': { backgroundColor: SIDE.selected, color: SIDE.selectedText, fontWeight: 600 },
            }}
          >
            {g.tabs.map((i) => <Tab key={TABS[i]} value={i} icon={tabIcon(TAB_KEYS[i])} iconPosition="start" label={TABS[i]} disableRipple />)}
          </Tabs>
        </Box>
      ))}
    </Box>
  )
}

export function Profile({ id, lk, canPhoto = false, onNotice, fresh = false, newHeader }: { id: Id; lk?: Lookups; canPhoto?: boolean; onNotice?: (msg: string) => void; fresh?: boolean; newHeader?: ReactNode }) {
  const groups = fresh ? FRESH_GROUPS : GROUPS
  const [tab, setTab] = useState(groups[0].tabs[0])
  const group = Math.max(0, groups.findIndex((g) => g.tabs.includes(tab)))
  const loaded = useQuery({
    queryKey: ['member', id],
    queryFn: () => getMember(id.branchCode, id.clientId),
    enabled: !fresh,
  })
  const m = fresh ? BLANK_MEMBER : loaded.data
  const { error, isLoading } = loaded

  if (error) return <Alert severity="error" sx={{ m: 2 }}>{(error as Error).message}</Alert>
  if (isLoading || !m) {
    return (
      <Box sx={(t) => ({ p: 2.5, ...glass(t) })}>
        <Box sx={{ display: 'flex', gap: 2.5, mb: 3 }}>
          <Skeleton variant="circular" width={72} height={72} />
          <Box sx={{ flex: 1 }}><Skeleton width="50%" height={36} /><Skeleton width="30%" /></Box>
        </Box>
        <Skeleton variant="rounded" height={220} />
      </Box>
    )
  }
  const nav = <SideTabs groups={groups} tab={tab} onChange={setTab} />
  return (
    <Box sx={{
      flex: 1, minHeight: 0, mt: 0, display: 'grid', gap: 1.5, gridTemplateColumns: { xs: 'minmax(0, 1fr)', md: '272px minmax(0, 1fr)' }, gridTemplateRows: { md: 'minmax(0, 1fr)' },
      overflowY: { xs: 'auto', md: 'visible' },
    }}>
      {fresh
        ? (
          <SideFrame label="New member">
            <Box sx={{ px: 2, pt: 2.5, pb: 1.5, textAlign: 'center' }}>
              <Ring color={SIDE.line}><Avatar sx={{ width: 92, height: 92, borderRadius: '50%', bgcolor: 'rgba(255,255,255,0.14)', color: SIDE.text }}><FiUser size={42} aria-hidden /></Avatar></Ring>
              <Typography component="h2" sx={{ mt: 1.5, fontSize: 20, fontWeight: 600, letterSpacing: '-0.01em' }}>New member</Typography>
              <Typography sx={{ fontSize: 13, mt: 0.25, color: SIDE.muted }}>Not registered yet</Typography>
            </Box>
            {nav}
          </SideFrame>
        )
        : <ProfileHeader m={m} lk={lk} canPhoto={canPhoto} onNotice={onNotice}>{nav}</ProfileHeader>}

      <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5, minHeight: 0, minWidth: 0 }}>
        {fresh ? newHeader : <ProfileStrip m={m} lk={lk} title={TABS[tab]} group={groups[group].label} canPhoto={canPhoto} onNotice={onNotice} />}
        {!fresh && <StatTiles id={m.id} onOpen={setTab} />}
        <Box role="region" aria-label={TABS[tab]} sx={(t) => ({ flex: 1, minHeight: 0, overflow: 'auto', p: 1.5, ...glass(t), background: t.palette.mode === 'dark' ? 'rgba(255,255,255,0.07)' : LIGHT.panel })}>
          <TabContent tab={tab} m={m} lk={lk} />
        </Box>
      </Box>
    </Box>
  )
}
