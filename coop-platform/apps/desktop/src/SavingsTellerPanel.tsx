import { Alert, Avatar, Box, Button, ButtonBase, Chip, Skeleton, Table, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { useMemo, useRef, useState } from 'react'
import { FiActivity, FiArrowDownCircle, FiBriefcase, FiChevronLeft, FiChevronRight, FiClock, FiCreditCard, FiDollarSign, FiGrid, FiInfo, FiPercent, FiSearch, FiUser } from 'react-icons/fi'
import { getFoTellerTotals, getKyc, getLoans, getLookups, getMember, getMembershipPolicy, getSavings, getShares, listFoTransactions, type FoSetup, type KycStatus } from './api'
import { displayName, memberNo, statusOf, type Id } from './clients'
import MemberSearchBox from './MemberSearchBox'
import { money } from './FoTransactionForm'
import { IconChip } from './appleUi'
import { glass } from './glass'
import LoansTab from './LoansTab'
import { useMemberImage } from './memberImage'
import { fmtDate } from './profile/format'
import { SavingsTab } from './profile/Savings'
import TellerTransaction, { type TellerPreset } from './TellerTransaction'
import { ShareCapitalTab } from './profile/ShareCapital'
import { TimeDepositsTab } from './profile/TimeDeposits'
import { tileFill } from './theme'

const SECTIONS = ['Savings', 'Share Capital', 'Loans', 'Time Deposits'] as const
type Section = (typeof SECTIONS)[number]

/** What a teller does most, each one opening the account type it belongs to (the posting itself, with its limits, holds and approvals, is in the section below). */
const QUICK: { label: string; section: Section; preset?: Omit<TellerPreset, 'seq'>; icon: React.ReactNode; color: string }[] = [
  { label: 'Savings', section: 'Savings', preset: { kind: 'savings', mode: 'deposit' }, icon: <FiArrowDownCircle />, color: tileFill.green },
  { label: 'Share capital', section: 'Share Capital', preset: { kind: 'share', mode: 'payment' }, icon: <FiPercent />, color: tileFill.blue },
  { label: 'Loan', section: 'Loans', preset: { kind: 'loan', mode: 'payment' }, icon: <FiDollarSign />, color: tileFill.orange },
  { label: 'Time deposit', section: 'Time Deposits', icon: <FiClock />, color: tileFill.purple },
]

const REMINDERS = [
  'Verify the member’s identity for large transactions.',
  'Count your drawer at the start and the end of your shift (Summary tab).',
  'Print a receipt for every deposit and withdrawal.',
  'Follow the KYC and anti-money-laundering rules.',
]

const COLS_KEY = 'coop.teller.cols'
const DEFAULT_COLS = [0.3, 0.4, 0.3]
/** A column narrower than this share of the width is dragged away: it is hidden. */
const HIDE_BELOW = 0.1

const storedCols = (): number[] => {
  try {
    const v = JSON.parse(localStorage.getItem(COLS_KEY) ?? 'null')
    if (Array.isArray(v) && v.length === 3 && v.every((x) => typeof x === 'number' && x >= 0) && Math.abs(v[0] + v[1] + v[2] - 1) < 0.01 && v.some((x) => x > 0)) return v
  } catch { /* storage unavailable */ }
  return DEFAULT_COLS
}

/**
 * The border between two columns: drag it to move the boundary, and drag it far enough to the side and the column there is hidden (a small tab stays to bring it back; a double
 * click also brings it back). The widths are kept for next time.
 */
function Splitter({ index, cols, setCols, container }: { index: 0 | 1; cols: number[]; setCols: (c: number[]) => void; container: React.RefObject<HTMLDivElement | null> }) {
  const drag = useRef<{ x: number; start: number[]; width: number } | null>(null)
  const left = cols[index], right = cols[index + 1]
  const restore = (side: 0 | 1) => {
    const next = [...cols]
    const i = index + side
    const donor = next.map((v, k) => ({ v, k })).filter((o) => o.k !== i).sort((a, b) => b.v - a.v)[0].k
    const give = Math.min(0.28, next[donor] - 0.12)
    next[i] = give
    next[donor] -= give
    setCols(next)
  }
  const onMove = (e: React.PointerEvent) => {
    const d = drag.current
    if (!d) return
    const dx = (e.clientX - d.x) / d.width
    const next = [...d.start]
    let a = d.start[index] + dx, b = d.start[index + 1] - dx
    const total = d.start[index] + d.start[index + 1]
    if (a < HIDE_BELOW) { a = 0; b = total } else if (b < HIDE_BELOW) { b = 0; a = total } else { a = Math.max(HIDE_BELOW, Math.min(total - HIDE_BELOW, a)); b = total - a }
    next[index] = a
    next[index + 1] = b
    setCols(next)
  }
  const down = (e: React.PointerEvent<HTMLDivElement>) => {
    const w = container.current?.getBoundingClientRect().width
    if (!w) return
    e.currentTarget.setPointerCapture(e.pointerId)
    drag.current = { x: e.clientX, start: [...cols], width: w }
  }
  const up = () => { drag.current = null; try { localStorage.setItem(COLS_KEY, JSON.stringify(cols)) } catch { /* storage unavailable */ } }
  return (
    <Box
      role="separator" aria-orientation="vertical" aria-label={index === 0 ? 'Resize the left column' : 'Resize the right column'} tabIndex={0}
      onPointerDown={down} onPointerMove={onMove} onPointerUp={up} onPointerCancel={up}
      onDoubleClick={() => { if (left === 0) restore(0); else if (right === 0) restore(1); else setCols(DEFAULT_COLS) }}
      onKeyDown={(e) => {
        if (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight') return
        e.preventDefault()
        const step = e.key === 'ArrowLeft' ? -0.03 : 0.03
        const total = left + right
        const a = Math.max(0, Math.min(total, left + step))
        const next = [...cols]
        next[index] = a < HIDE_BELOW ? 0 : total - a < HIDE_BELOW ? total : a
        next[index + 1] = total - next[index]
        setCols(next)
      }}
      sx={(t) => ({
        position: 'relative', alignSelf: 'stretch', minHeight: 240, cursor: 'col-resize', touchAction: 'none', borderLeft: `1px solid ${t.palette.divider}`, mx: 0.25,
        '&:hover, &:focus-visible, &:active': { borderLeftColor: t.palette.primary.main, boxShadow: `inset 1px 0 0 ${t.palette.primary.main}`, outline: 'none' },
      })}
    >
      {left === 0 && <Tab_ label="Show the left column" side="left" onClick={() => restore(0)} />}
      {right === 0 && <Tab_ label="Show the right column" side="right" onClick={() => restore(1)} />}
    </Box>
  )
}

/** The small tab left at the edge where a column was hidden. */
function Tab_({ label, side, onClick }: { label: string; side: 'left' | 'right'; onClick: () => void }) {
  return (
    <ButtonBase
      aria-label={label} title={label} onClick={onClick} onPointerDown={(e) => e.stopPropagation()}
      sx={(t) => ({
        position: 'absolute', top: 8, [side === 'left' ? 'left' : 'right']: -1, width: 18, height: 40, zIndex: 2, bgcolor: t.palette.background.paper, color: 'text.secondary',
        border: `1px solid ${t.palette.divider}`, borderRadius: side === 'left' ? '0 5px 5px 0' : '5px 0 0 5px', '&:hover': { color: t.palette.primary.main },
      })}
    >
      {side === 'left' ? <FiChevronRight size={14} /> : <FiChevronLeft size={14} />}
    </ButtonBase>
  )
}

const Card = ({ title, icon, color = tileFill.blue, action, children, sx }: { title: string; icon?: React.ReactNode; color?: string; action?: React.ReactNode; children: React.ReactNode; sx?: object }) => (
  <Box component="section" aria-label={title} sx={(t) => ({ p: 1.75, ...glass(t), ...sx })}>
    <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1.25 }}>
      {icon && <IconChip icon={icon} color={color} />}
      <Typography component="h2" noWrap sx={{ flex: 1, minWidth: 0, fontSize: 15, fontWeight: 600, letterSpacing: '-0.01em' }}>{title}</Typography>
      {action}
    </Box>
    {children}
  </Box>
)

/** A label and its value on one line, the label quiet. */
const Fact = ({ label, children }: { label: string; children: React.ReactNode }) => (
  <>
    <Typography component="dt" sx={{ fontSize: 12, m: 0 }} color="text.secondary">{label}</Typography>
    <Typography component="dd" sx={{ fontSize: 13, m: 0, fontVariantNumeric: 'tabular-nums', minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{children}</Typography>
  </>
)

function Balance({ label, value, color, icon }: { label: string; value: number | null; color: string; icon: React.ReactNode }) {
  return (
    <Box sx={{ flex: '1 1 104px', minWidth: 0, px: 1, py: 1, borderRadius: '10px', bgcolor: `${color}14`, boxShadow: `inset 0 0 0 1px ${color}30` }}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5, mb: 0.5, color }}>
        <Box aria-hidden sx={{ display: 'flex', fontSize: 13, flexShrink: 0 }}>{icon}</Box>
        <Typography sx={{ fontSize: 12, minWidth: 0 }} color="text.secondary" noWrap title={label}>{label}</Typography>
      </Box>
      <Typography sx={{ fontSize: 17, fontWeight: 600, fontVariantNumeric: 'tabular-nums', letterSpacing: '-0.01em' }} noWrap>{value === null ? '…' : money(value)}</Typography>
    </Box>
  )
}

const KYC_TEXT: Partial<Record<KycStatus, string>> = {
  NONE: 'This member has no KYC record.',
  INCOMPLETE: 'This member’s KYC record is incomplete.',
  EXPIRED: 'The ID on this member’s KYC record has expired.',
}

/**
 * The member's KYC standing, so the teller knows before counting cash. When the cooperative requires KYC for withdrawals (Membership policy), a missing, incomplete or
 * expired record stops every pay-out and the server refuses it with the same reason; otherwise it is a reminder. An overdue review never stops a withdrawal.
 */
export function KycNotice({ id }: { id: Id }) {
  const kyc = useQuery({ queryKey: ['kyc', id.branchCode, id.clientId], queryFn: () => getKyc(id.branchCode, id.clientId), retry: false })
  const policy = useQuery({ queryKey: ['membership-policy'], queryFn: getMembershipPolicy, retry: false })
  const s = kyc.data?.status
  if (!s || s === 'COMPLETE') return null
  if (s === 'REVIEW_DUE') return <Alert severity="info" sx={{ mt: 1 }}>KYC review is overdue{kyc.data?.nextReview ? ` (due ${fmtDate(kyc.data.nextReview)})` : ''}. Withdrawals are not affected.</Alert>
  const blocking = policy.data?.kycBlocksWithdrawals ?? false
  return (
    <Alert severity={blocking ? 'error' : 'warning'} sx={{ mt: 1 }}>
      {KYC_TEXT[s]} {blocking ? 'Withdrawals are stopped until it is put right (Member info > KYC).' : 'Ask for the member’s ID and record it (Member info > KYC).'}
    </Alert>
  )
}

function MemberCard({ id, onChange, lk }: { id: Id; onChange: () => void; lk: ReturnType<typeof getLookups> extends Promise<infer T> ? T | undefined : never }) {
  const member = useQuery({ queryKey: ['member', id], queryFn: () => getMember(id.branchCode, id.clientId) })
  const photo = useMemberImage('photo', id)
  const savings = useQuery({ queryKey: ['teller-savings', id], queryFn: () => getSavings(id.branchCode, id.clientId), retry: false })
  const shares = useQuery({ queryKey: ['teller-shares', id], queryFn: () => getShares(id.branchCode, id.clientId), retry: false })
  const loans = useQuery({ queryKey: ['teller-loans', id], queryFn: () => getLoans(id.branchCode, id.clientId, false), retry: false })
  const m = member.data
  const status = m ? statusOf(m, lk) : null
  const type = m?.clientType != null ? lk?.clientType?.[m.clientType] : null
  const loanBalance = loans.data ? (Array.isArray(loans.data) ? loans.data : []).reduce((n, l) => n + (l.outstanding ?? 0), 0) : null
  return (
    <Card title="Member information" icon={<FiUser />} color={tileFill.blue} action={<Button size="small" onClick={onChange}>Change member</Button>}>
      <Box sx={{ display: 'flex', gap: 1.75, alignItems: 'center', mb: 1.5 }}>
        <Avatar src={photo ?? undefined} alt={m ? `Photo of ${displayName(m)}` : ''} sx={(t) => ({ width: 76, height: 76, borderRadius: '50%', flexShrink: 0, bgcolor: t.palette.mode === 'dark' ? '#3A3A3C' : '#E5E7EB', color: 'text.secondary' })}><FiUser size={34} aria-hidden /></Avatar>
        <Box sx={{ minWidth: 0, flex: 1 }}>
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 0.75, minWidth: 0 }}>
            <Typography sx={{ fontWeight: 600, fontSize: 17, letterSpacing: '-0.01em', minWidth: 0 }} noWrap>{m ? displayName(m) : '…'}</Typography>
            {status && <Chip size="small" label={status.label} color={status.kind === 'default' ? 'default' : status.kind} variant="outlined" sx={{ flexShrink: 0 }} />}
          </Box>
          <Box component="dl" sx={{ m: 0, display: 'grid', gridTemplateColumns: 'auto minmax(0, 1fr)', columnGap: 1.5, rowGap: 0.25 }}>
            <Fact label="Member no.">{memberNo(id)}</Fact>
            {type && <Fact label="Type">{type}</Fact>}
            {m?.dateOpened && <Fact label="Member since">{fmtDate(m.dateOpened)}</Fact>}
          </Box>
        </Box>
      </Box>
      <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 0.75 }}>
        <Balance label="Savings" icon={<FiArrowDownCircle />} value={savings.data ? savings.data.totalBalance : savings.isError ? 0 : null} color={tileFill.green} />
        <Balance label="Share capital" icon={<FiPercent />} value={shares.data ? shares.data.totalBalance : shares.isError ? 0 : null} color={tileFill.blue} />
        <Balance label="Loans owed" icon={<FiDollarSign />} value={loanBalance} color={tileFill.orange} />
      </Box>
      <KycNotice id={id} />
    </Card>
  )
}

/** The accounts of the member and what can be taken out of each. */
function AccountSummary({ id }: { id: Id }) {
  const savings = useQuery({ queryKey: ['teller-savings', id], queryFn: () => getSavings(id.branchCode, id.clientId), retry: false })
  return (
    <Card title="Account summary" icon={<FiCreditCard />} color={tileFill.green}>
      {!savings.data && <Skeleton variant="rounded" height={60} />}
      {savings.data && savings.data.accounts.length === 0 && <Typography sx={{ fontSize: 13 }} color="text.secondary">No savings accounts.</Typography>}
      <Box sx={{ display: 'grid', gap: 1 }}>
        {savings.data?.accounts.filter((a) => a.status === 15).map((a) => (
          <Box key={`${a.typeCode}-${a.refNo}`} sx={(t) => ({ p: 1.25, borderRadius: '8px', border: `1px solid ${t.palette.divider}`, bgcolor: t.palette.mode === 'dark' ? 'rgba(255,255,255,0.03)' : 'rgba(255,255,255,0.55)' })}>
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 0.75 }}>
              <Box sx={{ flex: 1, minWidth: 0 }}>
                <Typography sx={{ fontSize: 13, fontWeight: 600 }} noWrap>{a.type ?? 'Savings'}</Typography>
                <Typography sx={{ fontSize: 12, fontVariantNumeric: 'tabular-nums' }} color="text.secondary" noWrap>{a.refNoText ?? a.refNo}</Typography>
              </Box>
              <Chip size="small" label={a.statusName} color="success" variant="outlined" />
            </Box>
            {([['Current balance', a.balance, false], ['Available balance', a.available, true], ['Hold amount', a.held + a.uncleared, false]] as const).map(([k, v, main]) => (
              <Box key={k} sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', py: 0.2 }}>
                <Typography sx={{ fontSize: 12 }} color="text.secondary">{k}</Typography>
                <Typography sx={{ fontSize: main ? 15 : 13, fontWeight: main ? 600 : 500, fontVariantNumeric: 'tabular-nums' }}>{money(v)}</Typography>
              </Box>
            ))}
          </Box>
        ))}
      </Box>
    </Card>
  )
}

/** The signed-in teller's own cash for the day. A full count is done on the Summary tab. */
function DrawerCard({ setup }: { setup: FoSetup }) {
  const totals = useQuery({ queryKey: ['fo-totals', setup.systemDate], queryFn: () => getFoTellerTotals(setup.systemDate), staleTime: 15_000, refetchInterval: 30_000 })
  const c = totals.data?.cash
  const rows: [string, number | undefined][] = [['Beginning balance', c?.beginning], ['Cash in', c?.in], ['Cash out', c?.out], ['Pending in', c?.pendingIn], ['Pending out', c?.pendingOut]]
  return (
    <Card title="Cash drawer" icon={<FiBriefcase />} color={tileFill.teal}>
      {totals.data && !totals.data.known && <Typography sx={{ fontSize: 13 }} color="text.secondary">{totals.data.reason}</Typography>}
      {!totals.data && <Skeleton variant="rounded" height={90} />}
      {totals.data?.known && (
        <>
          {rows.map(([k, v]) => (
            <Box key={k} sx={{ display: 'flex', justifyContent: 'space-between', py: 0.2 }}>
              <Typography sx={{ fontSize: 12 }} color="text.secondary">{k}</Typography><Typography sx={{ fontSize: 13, fontVariantNumeric: 'tabular-nums' }}>{money(v ?? 0)}</Typography>
            </Box>
          ))}
          <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', borderTop: 1, borderColor: 'divider', mt: 0.75, pt: 0.75 }}>
            <Typography sx={{ fontSize: 13, fontWeight: 600 }}>Total cash</Typography><Typography sx={{ fontSize: 17, fontWeight: 600, fontVariantNumeric: 'tabular-nums' }}>{money(c?.ending ?? 0)}</Typography>
          </Box>
        </>
      )}
    </Card>
  )
}

/** The kind of a transaction as a small coloured label: money in green, money out red, loans orange, the rest blue. */
function TypeBadge({ type }: { type: string | null }) {
  const t = type ?? '—'
  const c = /withdraw|disburse|payout|pay-out|voucher|release/i.test(t) ? tileFill.red : /loan/i.test(t) ? tileFill.orange : /deposit|receipt|payment|collection/i.test(t) ? tileFill.green : tileFill.blue
  return <Box component="span" sx={{ display: 'inline-block', maxWidth: '100%', px: 0.75, py: 0.1, borderRadius: '5px', fontSize: 11, fontWeight: 600, color: c, bgcolor: `${c}1A`, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', verticalAlign: 'middle' }}>{t}</Box>
}

/** The day's transactions as they come in, newest first. It refreshes by itself. */
function TodayCard({ setup }: { setup: FoSetup }) {
  const ids = useMemo(() => setup.tabs.flatMap((t) => t.types).filter((t) => t.canFind).map((t) => t.id), [setup])
  const { data, isFetching } = useQuery({ queryKey: ['fo-summary', setup.systemDate, ids], queryFn: () => listFoTransactions(setup.systemDate, ids, ''), enabled: ids.length > 0, refetchInterval: 15_000 })
  const rows = (data ?? []).slice(0, 8)
  return (
    <Card title="Today’s transactions" icon={<FiActivity />} color={tileFill.indigo} action={<Chip size="small" color={isFetching ? 'default' : 'success'} variant="outlined" label="Live" />}>
      <Table size="small" aria-label="Today’s transactions" sx={(t) => ({ '& td, & th': { py: 0.6, px: 0.75, fontSize: 12, borderBottom: `0.5px solid ${t.palette.divider}` }, '& th': { fontSize: 11, fontWeight: 600, color: 'text.secondary' }, '& tbody tr:last-of-type td': { borderBottom: 0 } })}>
        <TableHead><TableRow><TableCell>Time</TableCell><TableCell>Type</TableCell><TableCell>Member</TableCell><TableCell align="right">Amount</TableCell></TableRow></TableHead>
        <TableBody>
          {rows.map((r) => (
            <TableRow key={`${r.year}-${r.module}-${r.code}-${r.ctlNo}`}>
              <TableCell sx={{ fontVariantNumeric: 'tabular-nums' }}>{r.time?.slice(0, 5)}</TableCell><TableCell><TypeBadge type={r.type} /></TableCell>
              <TableCell sx={{ maxWidth: 110, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{r.clientName ?? '—'}</TableCell>
              <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>{money(r.total)}</TableCell>
            </TableRow>
          ))}
          {data && rows.length === 0 && <TableRow><TableCell colSpan={4}><Typography sx={{ p: 1, textAlign: 'center', fontSize: 12 }} color="text.secondary">Nothing yet today.</Typography></TableCell></TableRow>}
        </TableBody>
      </Table>
    </Card>
  )
}

/**
 * The teller's workspace. Search for the member, see who they are and what they hold, choose what they want to do (the quick actions), and do it in the section in the middle.
 * Every cash deposit and withdrawal on a member's accounts happens here, in the teller window, and nowhere else: savings deposits and withdrawals, share capital payments and
 * withdrawals, loan payments, and time deposit placements, interest withdrawals and pre-terminations. The member's profile only shows the accounts. The posting rules (open
 * drawer, limits, holds, supervisor approvals) belong to the cashier signed in here. The right side keeps the drawer and the day's work in view.
 */
export default function MemberAccountsPanel({ setup }: { setup: FoSetup }) {
  const { data: lk } = useQuery({ queryKey: ['lookups'], queryFn: getLookups, staleTime: Infinity })
  const [id, setId] = useState<Id | null>(null)
  const [section, setSection] = useState<Section>('Savings')
  const [preset, setPreset] = useState<TellerPreset | null>(null)
  const [cols, setColsState] = useState<number[]>(storedCols)
  const container = useRef<HTMLDivElement>(null)
  const setCols = (c: number[]) => setColsState(c)

  return (
    <Box sx={{ minWidth: 0, display: 'flex', flexDirection: 'column', gap: 1.25 }}>
      <MemberSearchBox lk={lk} onPick={setId} width={{ xs: '100%', md: '38%' }} />

      <Box ref={container} sx={{ display: 'grid', alignItems: 'start', gridTemplateColumns: `${cols[0] === 0 ? '0px' : `minmax(0, ${cols[0]}fr)`} 10px ${cols[1] === 0 ? '0px' : `minmax(0, ${cols[1]}fr)`} 10px ${cols[2] === 0 ? '0px' : `minmax(0, ${cols[2]}fr)`}` }}>
        <Box className="col" sx={{ display: 'flex', flexDirection: 'column', gap: 1.25, minWidth: 0, ...(cols[0] === 0 && { visibility: 'hidden', overflow: 'hidden' }) }}>
          {id ? <MemberCard id={id} onChange={() => setId(null)} lk={lk} /> : (
            <Card title="Member information" icon={<FiUser />} color={tileFill.blue}><Typography sx={{ fontSize: 13 }} color="text.secondary">Search for the member to take a deposit from or pay a withdrawal to.</Typography></Card>
          )}
          <Card title="Go to account" icon={<FiGrid />} color={tileFill.purple}>
            <Box sx={{ display: 'grid', gap: 0.75, gridTemplateColumns: 'repeat(2, minmax(0, 1fr))' }}>
              {QUICK.map((q) => (
                <ButtonBase
                  key={q.label} disabled={!id} onClick={() => { setSection(q.section); if (q.preset) setPreset({ ...q.preset, seq: (preset?.seq ?? 0) + 1 }) }} aria-label={q.label}
                  sx={(t) => ({ flexDirection: 'column', gap: 0.75, py: 1.25, borderRadius: '10px', border: `1px solid ${t.palette.divider}`, bgcolor: t.palette.mode === 'dark' ? 'rgba(255,255,255,0.03)' : 'rgba(255,255,255,0.55)', transition: 'background-color 140ms, transform 140ms', '&:hover': { bgcolor: t.palette.action.hover }, '&:active': { transform: 'scale(0.97)' }, '&.Mui-disabled': { opacity: 0.45 } })}
                >
                  <IconChip icon={q.icon} color={q.color} size={34} />
                  <Typography sx={{ fontSize: 12, fontWeight: 500 }}>{q.label}</Typography>
                </ButtonBase>
              ))}
            </Box>
          </Card>
          <TodayCard setup={setup} />
        </Box>

        <Splitter index={0} cols={cols} setCols={setCols} container={container} />
        <Box className="col" sx={{ minWidth: 0, ...(cols[1] === 0 && { visibility: 'hidden', overflow: 'hidden' }) }}>
          {!id ? (
            <Box sx={(t) => ({ ...glass(t), p: 5, display: 'grid', placeItems: 'center', textAlign: 'center', minHeight: 260 })}>
              <Box>
                <Box sx={{ display: 'flex', justifyContent: 'center', mb: 1.5 }}><IconChip icon={<FiSearch />} color={tileFill.blue} size={52} /></Box>
                <Typography sx={{ fontSize: 17, fontWeight: 600, mb: 0.5 }}>Find a member to begin</Typography>
                <Typography sx={{ fontSize: 13, maxWidth: 320, mx: 'auto' }} color="text.secondary">Search by name or member number above. Their accounts, balances and the transaction form open here.</Typography>
              </Box>
            </Box>
          ) : (
            <>
              <TellerTransaction id={id} preset={preset} />
              <Box role="tablist" aria-label="Member accounts" sx={(t) => ({ display: 'flex', gap: '2px', p: '3px', mb: 1.25, borderRadius: '9px', bgcolor: t.palette.mode === 'dark' ? 'rgba(255,255,255,0.08)' : 'rgba(0,0,0,0.05)' })}>
                {SECTIONS.map((x) => (
                  <ButtonBase
                    key={x} role="tab" aria-selected={section === x} onClick={() => setSection(x)}
                    sx={(t) => ({
                      flex: 1, minWidth: 0, minHeight: 28, px: 1, borderRadius: '7px', fontSize: 13, fontWeight: section === x ? 600 : 500, fontFamily: 'inherit',
                      color: section === x ? 'text.primary' : 'text.secondary', transition: 'background-color 160ms, color 160ms',
                      ...(section === x && { bgcolor: t.palette.mode === 'dark' ? 'rgba(255,255,255,0.16)' : '#FBFBFC', boxShadow: t.palette.mode === 'dark' ? '0 1px 2px rgba(0,0,0,0.4)' : '0 1px 3px rgba(0,0,0,0.12)' }),
                    })}
                  ><Box component="span" sx={{ minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{x}</Box></ButtonBase>
                ))}
              </Box>
              {section === 'Savings' && <SavingsTab id={id} teller />}
              {section === 'Share Capital' && <ShareCapitalTab id={id} teller />}
              {section === 'Loans' && <LoansTab branchCode={id.branchCode} clientId={id.clientId} teller />}
              {section === 'Time Deposits' && <TimeDepositsTab id={id} teller />}
            </>
          )}
        </Box>

        <Splitter index={1} cols={cols} setCols={setCols} container={container} />
        <Box className="col" sx={{ display: 'flex', flexDirection: 'column', gap: 1.25, minWidth: 0, ...(cols[2] === 0 && { visibility: 'hidden', overflow: 'hidden' }) }}>
          {id && <AccountSummary id={id} />}
          <DrawerCard setup={setup} />
          <Card title="Notes and reminders" icon={<FiInfo />} color={tileFill.gray}>
            <Box component="ul" sx={{ m: 0, pl: 2, fontSize: 12, display: 'grid', gap: 0.5, color: 'text.secondary' }}>{REMINDERS.map((r) => <li key={r}>{r}</li>)}</Box>
          </Card>
        </Box>
      </Box>
    </Box>
  )
}
