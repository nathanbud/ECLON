import { getMember, type Lookups, type Member } from '../api'
import { BLANK_MEMBER, FRESH_GROUPS, GROUPS, GROUP_KEYS, TABS, TAB_KEYS, groupForKey } from './constants'
import { BillingTab, DetailsTab, HistoryTab, MembershipTab, PersonalTab } from './InfoTabs'
import { BeneficiariesTab } from './Beneficiaries'
import { ShareCapitalTab } from './ShareCapital'
import { SavingsTab } from './Savings'
import { TimeDepositsTab } from './TimeDeposits'
import DocumentsPanel from '../DocumentsPanel'
import LoansTab from '../LoansTab'
import { type Id } from '../clients'
import { type ReactElement, type ReactNode, useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Alert, Avatar, Box, ButtonBase, Skeleton, Tab, Tabs, Typography } from '@mui/material'
import { FiChevronDown, FiChevronRight, FiDatabase, FiList, FiUser } from 'react-icons/fi'
import { LIGHT, SIDE, glass } from '../glass'
import { tabIcon } from './navIcons'
import { ProfileHeader, Ring, SideFrame } from './ProfileHeader'
import { ProfileStrip } from './ProfileStrip'
import { StatTiles } from './StatTiles'
import { VOICE_SECTION, type SectionRequest } from '../voice/bus'

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

/** What each group is called in the main menu, and its icon. `ownPage` is for a group whose first section is the entry itself rather than a row in its sub-menu (none now). */
const MAIN: Record<string, { label: string; icon: ReactElement; ownPage: boolean }> = {
  Info: { label: 'Member info', icon: <FiUser />, ownPage: false },
  Accounts: { label: 'Accounts', icon: <FiDatabase />, ownPage: false },
  Other: { label: 'Other', icon: <FiList />, ownPage: false },
}

/**
 * The section menu in the dark panel: a main menu of the groups (Member info, Accounts, Other). Opening one shows its sections as a sub-menu under it, the open section lit as a light
 * bar. Only one group is open at a time. Pressing a main entry opens its first section (Membership, under Member info). Each sub-menu is a vertical tab
 * list, so the arrow keys move within it, as the two tab strips did before.
 */
function SideTabs({ groups, tab, onChange }: { groups: { label: string; tabs: number[] }[]; tab: number; onChange: (tab: number) => void }) {
  const current = Math.max(0, groups.findIndex((g) => g.tabs.includes(tab)))
  // The group whose sub-menu is showing. It follows the open section, and pressing the open group's main entry folds it.
  const [shown, setShown] = useState<number | null>(current)
  const [followed, setFollowed] = useState(current)
  if (followed !== current) { setFollowed(current); setShown(current) }

  const row = (active: boolean) => ({
    width: '100%', display: 'flex', alignItems: 'center', gap: 1.25, minHeight: 38, px: 2, justifyContent: 'flex-start', textAlign: 'left', borderRadius: 0, fontSize: 14,
    fontWeight: active ? 600 : 500, color: active ? SIDE.selectedText : SIDE.text, backgroundColor: active ? SIDE.selected : 'transparent',
    '&:hover': { backgroundColor: active ? SIDE.selected : SIDE.hover },
    '&.Mui-focusVisible': { outline: `2px solid ${SIDE.selected}`, outlineOffset: -2 },
  }) as const

  return (
    <Box component="nav" aria-label="Profile sections" sx={{ pb: 1, mt: 0.5, borderTop: `1px solid ${SIDE.line}` }}>
      {groups.map((g, gi) => {
        const main = MAIN[g.label] ?? { label: g.label, icon: <FiList />, ownPage: false }
        const open = shown === gi
        const inGroup = gi === current
        const subs = main.ownPage ? g.tabs.slice(1) : g.tabs
        const mainActive = main.ownPage && tab === g.tabs[0]
        return (
          <Box key={g.label}>
            <ButtonBase
              aria-expanded={open} aria-controls={`profile-sub-${gi}`} aria-keyshortcuts={GROUP_KEYS[g.label]}
              onClick={() => {
                if (inGroup && (!main.ownPage || tab === g.tabs[0])) setShown(open ? null : gi)
                else { onChange(g.tabs[0]); setShown(gi) }
              }}
              sx={{ ...row(mainActive), ...(inGroup && !mainActive && { boxShadow: `inset 3px 0 0 ${SIDE.selected}` }) }}
            >
              <Box aria-hidden sx={{ display: 'flex', fontSize: 17, flexShrink: 0, width: 20, justifyContent: 'center' }}>{main.icon}</Box>
              <Box component="span" sx={{ flex: 1, minWidth: 0 }}>{main.label}</Box>
              {GROUP_KEYS[g.label] && (
                <Box component="kbd" aria-hidden sx={{ fontFamily: 'inherit', fontSize: 10.5, fontWeight: 600, lineHeight: 1, px: 0.6, py: 0.35, borderRadius: '4px', border: `1px solid ${mainActive ? 'rgba(0,0,0,0.25)' : SIDE.line}`, opacity: 0.8 }}>{GROUP_KEYS[g.label]}</Box>
              )}
              {subs.length > 0 && <Box aria-hidden sx={{ display: 'flex', fontSize: 15, opacity: 0.8 }}>{open ? <FiChevronDown /> : <FiChevronRight />}</Box>}
            </ButtonBase>
            {open && subs.length > 0 && (
              <Tabs
                id={`profile-sub-${gi}`} orientation="vertical" value={subs.includes(tab) ? tab : false} onChange={(_, v: number) => onChange(v)} aria-label={`${main.label} sections`}
                sx={{
                  minHeight: 0, backgroundColor: 'rgba(0,0,0,0.16)',
                  '& .MuiTabs-indicator': { display: 'none' },
                  '& .MuiTab-root': {
                    minHeight: 34, pl: 5.5, pr: 2, justifyContent: 'flex-start', textAlign: 'left', textTransform: 'none', fontSize: 13.5, fontWeight: 400, maxWidth: 'none', gap: 1.25,
                    color: SIDE.muted, borderRadius: 0, alignItems: 'center',
                    '& .MuiTab-iconWrapper': { fontSize: 15, m: 0 },
                    '&:hover': { backgroundColor: SIDE.hover, color: SIDE.text },
                    '&.Mui-focusVisible': { outline: `2px solid ${SIDE.selected}`, outlineOffset: -2 },
                  },
                  '& .MuiTab-root.Mui-selected': { backgroundColor: SIDE.selected, color: SIDE.selectedText, fontWeight: 600 },
                }}
              >
                {subs.map((i) => <Tab key={TABS[i]} value={i} icon={tabIcon(TAB_KEYS[i])} iconPosition="start" label={TABS[i]} disableRipple />)}
              </Tabs>
            )}
          </Box>
        )
      })}
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

  // "open savings" or "accounts" by voice shows that section, or the first section of that menu group.
  useEffect(() => {
    const on = (e: Event) => {
      const want = (e as CustomEvent<SectionRequest>).detail
      if (want.tab !== undefined && groups.some((g) => g.tabs.includes(want.tab as number))) setTab(want.tab)
      else if (want.group) { const g = groups.find((x) => x.label === want.group); if (g) setTab(g.tabs[0]) }
    }
    window.addEventListener(VOICE_SECTION, on)
    return () => window.removeEventListener(VOICE_SECTION, on)
  }, [groups])

  // I, A and O open Member info, Accounts and Other (their first section). Like H and M, they do nothing while typing or with a dialog or menu open.
  useEffect(() => {
    const on = (e: KeyboardEvent) => {
      const g = groupForKey(e, groups)
      if (g < 0) return
      const t = e.target as HTMLElement | null
      if (t && (t.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(t.tagName))) return
      if (document.querySelector('[role="dialog"], [role="menu"], [role="listbox"]')) return
      e.preventDefault()
      setTab(groups[g].tabs[0])
    }
    window.addEventListener('keydown', on)
    return () => window.removeEventListener('keydown', on)
  }, [groups])

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
        {fresh ? newHeader : <ProfileStrip m={m} lk={lk} title={TABS[tab]} group={groups[group].label} />}
        {/* The balance cards belong to Accounts: they are shown there only, not on every section. */}
        {!fresh && groups[group].label === 'Accounts' && <StatTiles id={m.id} onOpen={setTab} />}
        <Box role="region" aria-label={TABS[tab]} sx={(t) => ({ flex: 1, minHeight: 0, overflow: 'auto', p: 1.5, ...glass(t), background: t.palette.mode === 'dark' ? 'rgba(255,255,255,0.07)' : LIGHT.panel })}>
          <TabContent tab={tab} m={m} lk={lk} />
        </Box>
      </Box>
    </Box>
  )
}
