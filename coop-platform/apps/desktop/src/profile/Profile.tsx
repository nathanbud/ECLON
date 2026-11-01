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
import { Alert, Box, Skeleton, Tab, Tabs } from '@mui/material'
import { LIGHT, glass } from '../glass'
import { ProfileHeader } from './ProfileHeader'

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
  return (
    <>
      {fresh ? newHeader : <ProfileHeader m={m} lk={lk} canPhoto={canPhoto} onNotice={onNotice} />}
      {/* One folder: the tab strip and the page below share a frame, and the open tab takes the page's own fill so the two read as connected. */}
      <Box sx={(t) => ({ mt: 1, flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column', overflow: 'hidden', ...glass(t) })}>
        <Tabs value={group} onChange={(_, v) => setTab(groups[v].tabs[0])} aria-label="Profile sections"
          sx={(t) => {
            const dark = t.palette.mode === 'dark'
            const page = dark ? 'rgba(255,255,255,0.07)' : LIGHT.panel
            return {
              minHeight: 40, px: 0.5, pt: 0.5,
              background: dark ? 'rgba(0,0,0,0.22)' : 'rgba(110,120,150,0.12)',
              borderBottom: `1px solid ${dark ? 'rgba(255,255,255,0.12)' : 'rgba(0,0,0,0.08)'}`,
              '& .MuiTabs-indicator': { display: 'none' },
              '& .MuiTab-root': {
                minHeight: 38, fontSize: 14.5, px: 2.5, textTransform: 'none', color: 'text.secondary',
                borderRadius: '8px 8px 0 0', mr: 0.25, mb: '-1px', border: '1px solid transparent', borderBottom: 'none',
              },
              '& .MuiTab-root.Mui-selected': {
                color: 'text.primary', fontWeight: 700, background: page,
                borderColor: dark ? 'rgba(255,255,255,0.12)' : 'rgba(0,0,0,0.08)',
                boxShadow: `inset 0 2px 0 ${t.palette.primary.main}`,
              },
            }
          }}>
          {groups.map((g) => <Tab key={g.label} label={g.label} />)}
        </Tabs>
        <Tabs value={tab} onChange={(_, v) => setTab(v)} variant="scrollable" scrollButtons="auto" aria-label={`${groups[group].label} sections`}
          sx={(t) => {
            const dark = t.palette.mode === 'dark'
            return {
              minHeight: 36, px: 1.5,
              background: dark ? 'rgba(255,255,255,0.07)' : LIGHT.panel,
              borderBottom: `1px solid ${dark ? 'rgba(255,255,255,0.12)' : 'rgba(0,0,0,0.08)'}`,
              '& .MuiTab-root': { minHeight: 36, fontSize: 13.5, px: 1.5, textTransform: 'none' },
            }
          }}>
          {groups[group].tabs.map((i) => <Tab key={TABS[i]} value={i} label={TABS[i]} />)}
        </Tabs>
        <Box sx={(t) => ({ flex: 1, overflow: 'auto', p: 1.5, background: t.palette.mode === 'dark' ? 'rgba(255,255,255,0.07)' : LIGHT.panel })}>
          <TabContent tab={tab} m={m} lk={lk} />
        </Box>
      </Box>
    </>
  )
}
