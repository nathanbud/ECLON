import { Box, ButtonBase, InputAdornment, TextField, Typography } from '@mui/material'
import { useMemo, useState, type KeyboardEvent, type ReactNode } from 'react'
import { FiBook, FiDatabase, FiImage, FiBookOpen, FiPercent, FiChevronRight, FiClipboard, FiDownloadCloud, FiFileText, FiGitMerge, FiLock, FiMonitor, FiSearch, FiSend, FiShield, FiSliders, FiUserCheck, FiUserPlus, FiUsers } from 'react-icons/fi'
import type { View } from './Dashboard'
import { appBackground, glass } from './glass'
import { tint } from './theme'
import { useMe } from './session'

type Who = 'admin' | 'privacy' | 'accountant' | 'documents' | 'anyone' | 'superadmin'
interface Item { label: string; note: string; icon: ReactNode; color: string; view?: View; who: Who }
interface Category { id: string; label: string; icon: ReactNode; color: string; items: Item[] }

const EASE = 'cubic-bezier(.2,.8,.2,1)'
const LAST = 'coop.settings.category'

// Every administrator control lives here. An item without a view is not built yet.
const categories: Category[] = [
  {
    id: 'people', label: 'People and security', icon: <FiUsers />, color: tint.blue[0],
    items: [
      { label: 'Users', note: 'Temporary passwords, levels, permissions and who may do loans alone', icon: <FiUserCheck />, color: tint.blue[0], view: 'users', who: 'admin' },
      { label: 'Audit Log', note: 'Every change made in the system, who made it and why', icon: <FiClipboard />, color: tint.gray[1], view: 'audit', who: 'admin' },
      { label: 'Data privacy', note: 'Requests about people’s data, incidents, sharing, assessments, notices, retention and the export log', icon: <FiShield />, color: tint.purple[0], view: 'privacy', who: 'privacy' },
      { label: 'Approval workflows', note: 'Extra approvals for purchases and stock adjustments, and the amounts they start at', icon: <FiGitMerge />, color: tint.orange[0], view: 'workflows', who: 'accountant' },
      { label: 'Documents', note: 'Scanned IDs, contracts and deeds kept for members, assets and other records, and who may read them', icon: <FiFileText />, color: tint.indigo[0], view: 'documents', who: 'documents' },
    ],
  },
  {
    id: 'membership', label: 'Membership', icon: <FiUserPlus />, color: tint.blue[0],
    items: [
      { label: 'Membership policy', note: 'Whether a returning member must pay share capital again before reactivation is approved', icon: <FiUserPlus />, color: tint.blue[0], view: 'membershippolicy', who: 'admin' },
    ],
  },
  {
    id: 'loans', label: 'Loans', icon: <FiSliders />, color: tint.green[0],
    items: [
      { label: 'Amortization', note: 'How each kind of schedule is worked out, and a calculator to try one', icon: <FiPercent />, color: tint.purple[0], view: 'amortization', who: 'admin' },
      { label: 'Loan Setup', note: 'Loan types, purposes, collateral types and the deposits that can be held for co-makers', icon: <FiSliders />, color: tint.green[0], view: 'loansetup', who: 'admin' },
    ],
  },
  {
    id: 'accounting', label: 'Accounting', icon: <FiBookOpen />, color: tint.orange[0],
    items: [
      { label: 'Chart of Accounts', note: 'Add, rename, move and switch off ledger accounts', icon: <FiBookOpen />, color: tint.orange[0], view: 'accounts', who: 'accountant' },
    ],
  },
  {
    id: 'credit', label: 'Credit reporting', icon: <FiSend />, color: tint.purple[0],
    items: [
      { label: 'CIC submission', note: 'Header record, members’ and loans’ data files, encryption and sending to the Credit Information Corporation', icon: <FiSend />, color: tint.purple[0], view: 'cic', who: 'accountant' },
    ],
  },
  {
    id: 'documentation', label: 'Documentation', icon: <FiBook />, color: tint.indigo[0],
    items: [
      { label: 'Documentation', note: 'Keyboard shortcuts, the rules the system follows, where to do things, and the design notes', icon: <FiBook />, color: tint.indigo[0], view: 'documentation', who: 'anyone' },
    ],
  },
  {
    id: 'system', label: 'System', icon: <FiMonitor />, color: tint.gray[0],
    items: [
      { label: 'System', note: 'Branch, dates and system preferences', icon: <FiShield />, color: tint.gray[0], who: 'admin' },
      { label: 'Sign-in screen', note: 'The cooperative\'s logo, name and tagline shown on the sign-in screen', icon: <FiImage />, color: tint.indigo[0], view: 'branding', who: 'superadmin' },
      { label: 'Database connection', note: 'Which database this server works on, the saved connections and where each one is', icon: <FiDatabase />, color: tint.teal[0], view: 'connections', who: 'superadmin' },
      { label: 'Backup', note: 'Back up the application’s data and see past backups', icon: <FiDownloadCloud />, color: '#5AC8FA', view: 'backup', who: 'admin' },
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

function Row({ item, allowed, onOpen }: { item: Item; allowed: boolean; onOpen: (v: View) => void }) {
  const enabled = !!item.view && allowed
  return (
    <ButtonBase
      disabled={!enabled} onClick={() => item.view && onOpen(item.view)} focusRipple={false}
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
        <Typography color="text.secondary" sx={{ fontSize: 12.5, lineHeight: 1.35 }}>{item.note}</Typography>
      </Box>
      {enabled ? (
        <Box aria-hidden sx={{ color: 'text.disabled', display: 'flex' }}><FiChevronRight /></Box>
      ) : (
        <Typography color="text.secondary" sx={{ fontSize: 12, display: 'flex', alignItems: 'center', gap: 0.5, flexShrink: 0 }}>
          {item.view ? <><FiLock size={12} />{item.who === 'admin' ? 'Administrators' : item.who === 'superadmin' ? 'Superadmin' : item.who === 'privacy' ? 'Data protection officers' : item.who === 'anyone' ? 'Everyone' : 'Accountants'}</> : 'Coming soon'}
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
export default function SettingsPage({ onOpen }: { onOpen: (v: View) => void }) {
  const me = useMe()
  const accountant = me.admin || me.roles.some((r) => r.replace('ROLE_', '') === 'ACCOUNTANT')
  const superadmin = me.roles.some((r) => r.replace('ROLE_', '') === 'SUPERADMIN')
  const dpo = me.admin || me.roles.some((r) => r.replace('ROLE_', '') === 'DATA_PROTECTION_OFFICER')
  const clerk = me.roles.some((r) => r.replace('ROLE_', '') === 'DOCUMENT_CLERK')
  const allowed = (i: Item) => (i.who === 'anyone' ? true : i.who === 'superadmin' ? superadmin : i.who === 'privacy' ? dpo : i.who === 'admin' ? me.admin : i.who === 'documents' ? accountant || clerk : accountant)
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
              <Typography noWrap sx={{ fontSize: 14, fontWeight: 600, lineHeight: 1.25 }}>{me.name}</Typography>
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
                    width: { md: '100%' }, fontSize: 14, fontWeight: on ? 600 : 500,
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
          <Typography component="h1" sx={{ fontSize: 28, fontWeight: 700, letterSpacing: '-0.025em', mb: 2, px: 0.5 }}>{query ? 'Search' : category.label}</Typography>
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
