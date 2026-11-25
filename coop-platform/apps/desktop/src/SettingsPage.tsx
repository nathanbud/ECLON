import { Box, ButtonBase, InputAdornment, TextField, Typography } from '@mui/material'
import { useMemo, useState, type KeyboardEvent, type ReactNode } from 'react'
import { FiBook, FiFilePlus, FiPackage, FiDatabase, FiImage, FiBookOpen, FiLayers, FiMoon, FiPercent, FiChevronRight, FiClipboard, FiDownloadCloud, FiFileText, FiGitMerge, FiLock, FiMapPin, FiMonitor, FiSearch, FiSend, FiShield, FiSliders, FiToggleRight, FiUserCheck, FiUserPlus, FiUsers } from 'react-icons/fi'
import type { View } from './Dashboard'
import { appBackground, glass } from './glass'
import { tint } from './theme'
import { useMe } from './session'
import { nameCase } from './ui/format'
import { SchemaStatusNotice } from './SchemaStatusNotice'
import { can, featureAllows, screenFor, type Who } from './nav/screens'

/**
 * An item opens a screen (view, and tab where the screen has several); who may open it comes from the screen list, not from here. An item without a
 * view is not built yet. `who` is only used for an item without a screen in the list.
 */
interface Item { label: string; note: string; icon: ReactNode; color: string; view?: View; tab?: string; who: Who }

/** Who may open an item: its screen's rule from the screen list, else the item's own. */
const whoOf = (i: Item): Who => (i.view ? screenFor(i.view, i.tab)?.who : undefined) ?? i.who

/** Who may open it, in words, for the lock shown to those who may not. */
const WHO_WORDS: Partial<Record<Who, string>> = {
  admin: 'Administrators', superadmin: 'Superadmin', privacy: 'Data protection officers', anyone: 'Everyone', accountant: 'Accountants',
  documents: 'Accountants and document clerks', officer: 'Membership Officers', memberrecords: 'Member editors', settings: 'Administrators',
}
interface Category { id: string; label: string; icon: ReactNode; color: string; items: Item[] }

const EASE = 'cubic-bezier(.2,.8,.2,1)'
const LAST = 'coop.settings.category'

// Every administrator control lives here. An item without a view is not built yet.
const categories: Category[] = [
  // Grouped the way core banking and cooperative systems group their setup: the organization, who may do what, the products members hold, membership
  // rules, the books, compliance and records, the server itself, then help. Products used to be split between Accounting (Account Types) and Loans.
  {
    id: 'organization', label: 'Organization', icon: <FiImage />, color: tint.indigo[0],
    items: [
      { label: 'Sign-in screen and branding', note: 'The cooperative’s logo, name and tagline shown on the sign-in screen', icon: <FiImage />, color: tint.indigo[0], view: 'branding', who: 'superadmin' },
      { label: 'Offices', note: 'The cooperative’s offices: the main office and its satellites, which members and users belong to', icon: <FiMapPin />, color: tint.indigo[0], view: 'offices', who: 'admin' },
      { label: 'Departments', note: 'The main office, satellites and buildings that members, users and the books are kept by', icon: <FiLayers />, color: tint.indigo[0], view: 'departments', who: 'admin' },
      { label: 'Branches, calendar and preferences', note: 'Branch details, holidays and system preferences', icon: <FiShield />, color: tint.gray[0], who: 'admin' },
    ],
  },
  {
    id: 'people', label: 'Users and Access', icon: <FiUsers />, color: tint.blue[0],
    items: [
      { label: 'Users', note: 'Temporary passwords, levels, permissions and who may do loans alone', icon: <FiUserCheck />, color: tint.blue[0], view: 'users', who: 'admin' },
      { label: 'Approval workflows', note: 'Extra approvals for purchases and stock adjustments, and the amounts they start at', icon: <FiGitMerge />, color: tint.orange[0], view: 'workflows', who: 'accountant' },
    ],
  },
  {
    id: 'products', label: 'Products', icon: <FiLayers />, color: tint.teal[0],
    items: [
      { label: 'Savings and deposit products', note: 'Savings, time deposit and investment deposit plan types: interest rates, minimum and maximum balances, dormancy, and the chart accounts they post to', icon: <FiLayers />, color: tint.teal[0], view: 'accounttypes', tab: 'deposits', who: 'admin' },
      { label: 'Share capital products', note: 'Paid-up and preferred share capital types, the share capital a member must hold, and the chart accounts they post to', icon: <FiLayers />, color: tint.blue[0], view: 'accounttypes', tab: 'shares', who: 'admin' },
      { label: 'Fund and payable products', note: 'Member funds (mutual benefit, hospitalization) and payables kept per member, and the chart accounts they post to', icon: <FiLayers />, color: tint.purple[0], view: 'accounttypes', tab: 'funds', who: 'admin' },
      { label: 'Receivable products', note: 'Receivables kept per member, such as cash advances, and the chart accounts they post to', icon: <FiLayers />, color: tint.orange[0], view: 'accounttypes', tab: 'receivables', who: 'admin' },
      { label: 'Loan products', note: 'Loan types, purposes, collateral types and the deposits that can be held for co-makers', icon: <FiSliders />, color: tint.green[0], view: 'loansetup', who: 'admin' },
      { label: 'Amortization methods', note: 'How each kind of loan schedule is worked out, and a calculator to try one', icon: <FiPercent />, color: tint.purple[0], view: 'amortization', who: 'admin' },
      { label: 'Account opening (SL Controls)', note: 'Which savings, fund and share capital accounts open by themselves when a member is registered or reactivated, per member type', icon: <FiToggleRight />, color: tint.teal[0], view: 'slcontrols', who: 'admin' },
      { label: 'Product roles', note: 'Which of your own account types the system posts to by itself, such as vouchers payable for loan proceeds and vouchers', icon: <FiToggleRight />, color: tint.orange[0], view: 'productroles', who: 'admin' },
    ],
  },
  {
    id: 'membership', label: 'Membership', icon: <FiUserPlus />, color: tint.blue[0],
    items: [
      { label: 'Member defaults', note: 'The department and office a new member is given, and the member types this cooperative registers', icon: <FiUserPlus />, color: tint.green[0], view: 'memberdefaults', who: 'admin' },
      { label: 'Membership policy', note: 'Whether a returning member must pay share capital again, and whether withdrawals need a complete KYC', icon: <FiUserPlus />, color: tint.blue[0], view: 'membershippolicy', who: 'admin' },
      { label: 'Savings dormancy', note: 'Savings accounts with no deposit or withdrawal for their type’s period, to mark dormant; and reactivating dormant ones', icon: <FiMoon />, color: tint.indigo[0], view: 'dormancy', who: 'admin' },
    ],
  },
  {
    id: 'accounting', label: 'Accounting', icon: <FiBookOpen />, color: tint.orange[0],
    items: [
      { label: 'Chart of Accounts', note: 'Add, rename, move and switch off ledger accounts', icon: <FiBookOpen />, color: tint.orange[0], view: 'accounts', who: 'accountant' },
      { label: 'Cash and teller drawers', note: 'The cash accounts and each cashier’s and teller’s drawer, and the chart accounts they post to', icon: <FiLayers />, color: tint.green[0], view: 'accounttypes', tab: 'cash', who: 'admin' },
      { label: 'Opening balances', note: 'The balances the cooperative starts with: ledger accounts and each member’s savings and share capital, typed or imported, approved and posted once', icon: <FiFilePlus />, color: tint.green[1], view: 'accounting', tab: 'Opening balances', who: 'accountant' },
      { label: 'Periods, banks and year-end close', note: 'Accounting periods, bank accounts, the trial balance, statements and closing the year', icon: <FiBookOpen />, color: tint.orange[1], view: 'accounting', who: 'accountant' },
    ],
  },
  {
    id: 'compliance', label: 'Compliance and Audit', icon: <FiShield />, color: tint.purple[0],
    items: [
      { label: 'Audit Log', note: 'Every change made in the system, who made it and why', icon: <FiClipboard />, color: tint.gray[1], view: 'audit', who: 'admin' },
      { label: 'Data privacy', note: 'Requests about people’s data, incidents, sharing, assessments, notices, retention and the export log', icon: <FiShield />, color: tint.purple[0], view: 'privacy', who: 'privacy' },
      { label: 'CIC submission', note: 'Header record, members’ and loans’ data files, encryption and sending to the Credit Information Corporation', icon: <FiSend />, color: tint.purple[0], view: 'cic', who: 'accountant' },
      { label: 'Documents', note: 'Scanned IDs, contracts and deeds kept for members, assets and other records, and who may read them', icon: <FiFileText />, color: tint.indigo[0], view: 'documents', who: 'documents' },
    ],
  },
  {
    id: 'system', label: 'System', icon: <FiMonitor />, color: tint.gray[0],
    items: [
      { label: 'Features', note: 'Which areas this cooperative uses: savings, time deposits, loans, the store and the rest; a super administrator switches them', icon: <FiToggleRight />, color: tint.indigo[0], view: 'features', who: 'admin' },
      { label: 'Database connection', note: 'Which database this server works on, the saved connections and where each one is', icon: <FiDatabase />, color: tint.teal[0], view: 'connections', who: 'superadmin' },
      { label: 'Starter setup', note: 'Load the standard lists a new database needs: account statuses, terms, payment modes, genders and the rest', icon: <FiPackage />, color: tint.indigo[0], view: 'startersetup', who: 'admin' },
      { label: 'Error log', note: 'Unexpected failures with their technical cause and what to do, found by the reference a person quotes', icon: <FiClipboard />, color: tint.red[0], view: 'errors', who: 'admin' },
      { label: 'Backup', note: 'Back up the application’s data and see past backups', icon: <FiDownloadCloud />, color: tint.teal[1], view: 'backup', who: 'admin' },
    ],
  },
  {
    id: 'documentation', label: 'Help', icon: <FiBook />, color: tint.indigo[0],
    items: [
      { label: 'Documentation', note: 'Keyboard shortcuts, the rules the system follows, where to do things, and the design notes', icon: <FiBook />, color: tint.indigo[0], view: 'documentation', who: 'anyone' },
    ],
  },
]

const stored = () => { try { return sessionStorage.getItem(LAST) } catch { return null } }

/** The coloured rounded square that carries an icon, as in the system Settings of macOS and iOS. */
function Badge({ color, size, children }: { color: string; size: number; children: ReactNode }) {
  return (
    <Box aria-hidden sx={{
      width: size, height: size, flexShrink: 0, borderRadius: `${Math.round(size * 0.27)}px`, color: '#fff', bgcolor: color,
      display: 'grid', placeItems: 'center', fontSize: size * 0.58, boxShadow: 'inset 0 0 0 0.5px rgba(0,0,0,0.12), inset 0 1px 0 rgba(255,255,255,0.25)',
      backgroundImage: 'linear-gradient(180deg, rgba(255,255,255,0.18), rgba(255,255,255,0))',
    }}>{children}</Box>
  )
}

function Row({ item, allowed, onOpen }: { item: Item; allowed: boolean; onOpen: (v: View, tab?: string) => void }) {
  const enabled = !!item.view && allowed
  return (
    <ButtonBase
      disabled={!enabled} onClick={() => item.view && onOpen(item.view, item.tab)} focusRipple={false}
      aria-label={enabled ? item.label : item.view ? `${item.label} (not allowed for your role)` : `${item.label} (coming soon)`}
      sx={(t) => ({
        width: '100%', display: 'flex', alignItems: 'center', gap: 1.5, textAlign: 'left', px: 1.75, py: 1.25, position: 'relative',
        transition: `background-color 160ms ${EASE}`,
        '&:not(:last-of-type)::after': { content: '""', position: 'absolute', left: 62, right: 0, bottom: 0, height: '0.5px', bgcolor: t.palette.divider },
        '&:hover': enabled ? { bgcolor: t.palette.action.hover } : undefined,
        '&:active': enabled ? { bgcolor: t.palette.action.selected } : undefined,
        '&.Mui-disabled': { opacity: 0.6 },
        '&.Mui-focusVisible': { outline: `2px solid ${t.palette.primary.main}`, outlineOffset: -2, borderRadius: '10px' },
      })}
    >
      <Badge color={item.color} size={32}>{item.icon}</Badge>
      <Box sx={{ minWidth: 0, flex: 1 }}>
        <Typography sx={{ fontSize: 15, fontWeight: 500, lineHeight: 1.3 }}>{item.label}</Typography>
        <Typography color="text.secondary" sx={{ fontSize: 12, lineHeight: 1.35 }}>{item.note}</Typography>
      </Box>
      {enabled ? (
        <Box aria-hidden sx={{ color: 'text.disabled', display: 'flex' }}><FiChevronRight /></Box>
      ) : (
        <Typography color="text.secondary" sx={{ fontSize: 12, display: 'flex', alignItems: 'center', gap: 0.5, flexShrink: 0 }}>
          {item.view ? <><FiLock size={12} />{WHO_WORDS[whoOf(item)] ?? 'Not your role'}</> : 'Coming soon'}
        </Typography>
      )}
    </ButtonBase>
  )
}

/** A group of rows on one rounded surface, like an inset list in iOS Settings. */
function Group({ children }: { children: ReactNode }) {
  return <Box sx={(t) => ({ ...glass(t), overflow: 'hidden', '& > *': { display: 'flex' } })}>{children}</Box>
}

/** The home of the administrator controls: a sidebar of categories, and the controls of the chosen one. */
export default function SettingsPage({ onOpen }: { onOpen: (v: View, tab?: string) => void }) {
  const me = useMe()
  // Who may open a screen is decided once, in the screen list (nav/screens.tsx), which the side menu, the tiles and the search also read.
  const allowed = (i: Item) => !!i.view && can(me, whoOf(i)) && featureAllows(me, { view: i.view, tab: i.tab })
  const [pick, setPick] = useState(() => stored() ?? categories[0].id)
  const [q, setQ] = useState('')
  const query = q.trim().toLowerCase()

  const category = categories.find((c) => c.id === pick) ?? categories[0]
  const hits = useMemo(() => {
    if (!query) return []
    return categories.flatMap((c) => c.items.filter((i) => `${i.label} ${i.note} ${c.label}`.toLowerCase().includes(query)))
  }, [query])

  const choose = (id: string) => { setPick(id); try { sessionStorage.setItem(LAST, id) } catch { /* storage unavailable */ } }
  const onKey = (e: KeyboardEvent<HTMLElement>, i: number) => {
    const next = e.key === 'ArrowDown' ? i + 1 : e.key === 'ArrowUp' ? i - 1 : null
    if (next == null) return
    const c = categories[(next + categories.length) % categories.length]
    choose(c.id)
    ;(e.currentTarget.parentElement?.children[(next + categories.length) % categories.length] as HTMLElement | undefined)?.focus()
    e.preventDefault()
  }

  const initials = me.name.split(/\s+/).filter(Boolean).slice(0, 2).map((w) => w[0]!.toUpperCase()).join('') || me.username.slice(0, 1).toUpperCase()

  return (
    <Box component="main" sx={(t) => ({ minHeight: 'calc(100dvh - 44px)', p: { xs: 2, md: 3 }, background: appBackground(t) })}>
      <Box sx={{ maxWidth: 980, mx: 'auto', display: 'grid', gap: { xs: 2, md: 3 }, gridTemplateColumns: { xs: '1fr', md: '236px minmax(0, 1fr)' }, alignItems: 'start' }}>
        {/* sidebar */}
        <Box component="nav" aria-label="Settings categories" sx={{ position: { md: 'sticky' }, top: 24 }}>
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.25, px: 0.5, mb: 2 }}>
            <Box aria-hidden sx={{ width: 40, height: 40, borderRadius: '50%', display: 'grid', placeItems: 'center', fontWeight: 600, fontSize: 15, color: '#fff', backgroundImage: 'linear-gradient(160deg, #8E8E93, #636366)' }}>{initials}</Box>
            <Box sx={{ minWidth: 0 }}>
              <Typography noWrap sx={{ fontSize: 13, fontWeight: 600, lineHeight: 1.25 }}>{nameCase(me.name)}</Typography>
              <Typography noWrap color="text.secondary" sx={{ fontSize: 12 }}>{me.admin ? 'Administrator' : 'Accountant'}</Typography>
            </Box>
          </Box>
          <TextField
            size="small" fullWidth placeholder="Search" value={q} onChange={(e) => setQ(e.target.value)} sx={{ mb: 1.5 }}
            slotProps={{
              htmlInput: { 'aria-label': 'Search settings' },
              input: {
                startAdornment: <InputAdornment position="start"><FiSearch /></InputAdornment>,
                sx: (t: { palette: { mode: string } }) => ({ borderRadius: '10px', bgcolor: t.palette.mode === 'dark' ? 'rgba(255,255,255,0.08)' : 'rgba(0,0,0,0.05)', '& fieldset': { border: 'none' } }),
              },
            }}
          />
          <Box role="list" sx={{ display: 'flex', flexDirection: { xs: 'row', md: 'column' }, gap: 0.25, flexWrap: 'wrap' }}>
            {categories.map((c, i) => {
              const on = !query && c.id === category.id
              return (
                <ButtonBase
                  key={c.id} role="listitem" aria-current={on ? 'page' : undefined} onClick={() => { setQ(''); choose(c.id) }} onKeyDown={(e) => onKey(e, i)} focusRipple={false}
                  sx={(t) => ({
                    display: 'flex', alignItems: 'center', gap: 1.25, px: 1, py: 0.75, borderRadius: '10px', textAlign: 'left', justifyContent: 'flex-start',
                    width: { md: '100%' }, fontSize: 13, fontWeight: on ? 600 : 500,
                    bgcolor: on ? (t.palette.mode === 'dark' ? 'rgba(10,132,255,0.28)' : 'rgba(0,122,255,0.14)') : 'transparent',
                    transition: `background-color 160ms ${EASE}`,
                    '&:hover': { bgcolor: on ? undefined : t.palette.action.hover },
                    '&.Mui-focusVisible': { outline: `2px solid ${t.palette.primary.main}`, outlineOffset: -1 },
                  })}
                >
                  <Badge color={c.color} size={24}>{c.icon}</Badge>
                  {c.label}
                </ButtonBase>
              )
            })}
          </Box>
        </Box>

        {/* detail */}
        <Box component="section" aria-live="polite" key={query ? 'search' : category.id} sx={{
          animation: `settingsIn 260ms ${EASE} both`, '@keyframes settingsIn': { from: { opacity: 0, transform: 'translateY(6px)' }, to: { opacity: 1, transform: 'none' } },
          '@media (prefers-reduced-motion: reduce)': { animation: 'none' },
        }}>
          {me.admin && <SchemaStatusNotice canApply={can(me, 'superadmin')} />}
          <Typography component="h1" sx={{ fontSize: 20, fontWeight: 700, letterSpacing: '-0.02em', mb: 2, px: 0.5 }}>{query ? 'Search' : category.label}</Typography>
          {query ? (
            hits.length > 0 ? (
              <Group>{hits.map((i) => <Row key={i.label} item={i} allowed={allowed(i)} onOpen={onOpen} />)}</Group>
            ) : (
              <Typography color="text.secondary" sx={{ px: 0.5 }}>Nothing matches “{q.trim()}”.</Typography>
            )
          ) : (
            <Group>{category.items.map((i) => <Row key={i.label} item={i} allowed={allowed(i)} onOpen={onOpen} />)}</Group>
          )}
        </Box>
      </Box>
    </Box>
  )
}
