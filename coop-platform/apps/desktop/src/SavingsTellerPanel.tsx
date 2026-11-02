import { Avatar, Box, Button, ButtonBase, Chip, Skeleton, Table, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { useMemo, useRef, useState } from 'react'
import { FiArrowDownCircle, FiChevronLeft, FiChevronRight, FiClock, FiDollarSign, FiPercent, FiUser } from 'react-icons/fi'
import { getFoTellerTotals, getLoans, getLookups, getMember, getSavings, getShares, listFoTransactions, type FoSetup } from './api'
import { displayName, memberNo, statusOf, type Id } from './clients'
import MemberSearchBox from './MemberSearchBox'
import { money } from './FoTransactionForm'
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
        position: 'relative', cursor: 'col-resize', touchAction: 'none', borderLeft: `1px solid ${t.palette.divider}`, mx: 0.25,
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

const Card = ({ title, action, children, sx }: { title: string; action?: React.ReactNode; children: React.ReactNode; sx?: object }) => (
  <Box component="section" aria-label={title} sx={(t) => ({ p: 1.5, ...glass(t), ...sx })}>
    <Box sx={{ display: 'flex', alignItems: 'center', mb: 1 }}>
      <Typography component="h2" sx={{ flex: 1, fontSize: 14.5, fontWeight: 700 }}>{title}</Typography>
      {action}
    </Box>
    {children}
  </Box>
)

function Balance({ label, value, color }: { label: string; value: number | null; color: string }) {
  return (
    <Box sx={{ flex: 1, minWidth: 0, p: 1, borderRadius: '5px', bgcolor: `${color}22`, boxShadow: `inset 0 0 0 1px ${color}44` }}>
      <Typography sx={{ fontSize: 11.5 }} color="text.secondary" noWrap>{label}</Typography>
      <Typography sx={{ fontSize: 15, fontWeight: 700, fontVariantNumeric: 'tabular-nums' }} noWrap>{value === null ? '…' : money(value)}</Typography>
    </Box>
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
    <Card title="Member information" action={<Button size="small" onClick={onChange}>Change member</Button>}>
      <Box sx={{ display: 'flex', gap: 1.5, mb: 1.25 }}>
        <Avatar variant="rounded" src={photo ?? undefined} alt={m ? `Photo of ${displayName(m)}` : ''} sx={{ width: 72, height: 72, borderRadius: '5px' }}><FiUser size={32} aria-hidden /></Avatar>
        <Box sx={{ minWidth: 0 }}>
          <Typography sx={{ fontWeight: 700, fontSize: 16 }} noWrap>{m ? displayName(m) : '…'}</Typography>
          <Typography sx={{ fontSize: 12.5, fontVariantNumeric: 'tabular-nums' }} color="text.secondary">Member no. {memberNo(id)}</Typography>
          {type && <Typography sx={{ fontSize: 12.5 }} color="text.secondary">Type: {type}</Typography>}
          {m?.dateOpened && <Typography sx={{ fontSize: 12.5 }} color="text.secondary">Member since {fmtDate(m.dateOpened)}</Typography>}
          {status && <Chip size="small" label={status.label} color={status.kind === 'default' ? 'default' : status.kind} sx={{ mt: 0.5, height: 20 }} />}
        </Box>
      </Box>
      <Box sx={{ display: 'flex', gap: 0.75 }}>
        <Balance label="Savings" value={savings.data ? savings.data.totalBalance : savings.isError ? 0 : null} color={tileFill.green} />
        <Balance label="Share capital" value={shares.data ? shares.data.totalBalance : shares.isError ? 0 : null} color={tileFill.blue} />
        <Balance label="Loans owed" value={loanBalance} color={tileFill.orange} />
      </Box>
    </Card>
  )
}

/** The accounts of the member and what can be taken out of each. */
function AccountSummary({ id }: { id: Id }) {
  const savings = useQuery({ queryKey: ['teller-savings', id], queryFn: () => getSavings(id.branchCode, id.clientId), retry: false })
  return (
    <Card title="Account summary">
      {!savings.data && <Skeleton variant="rounded" height={60} />}
      {savings.data && savings.data.accounts.length === 0 && <Typography sx={{ fontSize: 13 }} color="text.secondary">No savings accounts.</Typography>}
      {savings.data?.accounts.filter((a) => a.status === 15).map((a) => (
        <Box key={`${a.typeCode}-${a.refNo}`} sx={{ mb: 1, '&:last-child': { mb: 0 } }}>
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
            <Typography sx={{ flex: 1, fontSize: 13, fontWeight: 600 }} noWrap>{a.type ?? 'Savings'} {a.refNoText ?? a.refNo}</Typography>
            <Chip size="small" label={a.statusName} variant="outlined" sx={{ height: 20 }} />
          </Box>
          {([['Current balance', a.balance], ['Available balance', a.available], ['Hold amount', a.held + a.uncleared]] as const).map(([k, v]) => (
            <Box key={k} sx={{ display: 'flex', justifyContent: 'space-between', fontSize: 13 }}>
              <span>{k}</span><b style={{ fontVariantNumeric: 'tabular-nums' }}>{money(v)}</b>
            </Box>
          ))}
        </Box>
      ))}
    </Card>
  )
}

/** The signed-in teller's own cash for the day. A full count is done on the Summary tab. */
function DrawerCard({ setup }: { setup: FoSetup }) {
  const totals = useQuery({ queryKey: ['fo-totals', setup.systemDate], queryFn: () => getFoTellerTotals(setup.systemDate), staleTime: 15_000, refetchInterval: 30_000 })
  const c = totals.data?.cash
  const rows: [string, number | undefined][] = [['Beginning balance', c?.beginning], ['Cash in', c?.in], ['Cash out', c?.out], ['Pending in', c?.pendingIn], ['Pending out', c?.pendingOut]]
  return (
    <Card title="Cash drawer">
      {totals.data && !totals.data.known && <Typography sx={{ fontSize: 13 }} color="text.secondary">{totals.data.reason}</Typography>}
      {!totals.data && <Skeleton variant="rounded" height={90} />}
      {totals.data?.known && (
        <>
          {rows.map(([k, v]) => (
            <Box key={k} sx={{ display: 'flex', justifyContent: 'space-between', fontSize: 13, py: 0.15 }}><span>{k}</span><span style={{ fontVariantNumeric: 'tabular-nums' }}>{money(v ?? 0)}</span></Box>
          ))}
          <Box sx={{ display: 'flex', justifyContent: 'space-between', fontSize: 14, fontWeight: 700, borderTop: 1, borderColor: 'divider', mt: 0.5, pt: 0.5 }}>
            <span>Total cash</span><span style={{ fontVariantNumeric: 'tabular-nums' }}>{money(c?.ending ?? 0)}</span>
          </Box>
        </>
      )}
    </Card>
  )
}

/** The day's transactions as they come in, newest first. It refreshes by itself. */
function TodayCard({ setup }: { setup: FoSetup }) {
  const ids = useMemo(() => setup.tabs.flatMap((t) => t.types).filter((t) => t.canFind).map((t) => t.id), [setup])
  const { data, isFetching } = useQuery({ queryKey: ['fo-summary', setup.systemDate, ids], queryFn: () => listFoTransactions(setup.systemDate, ids, ''), enabled: ids.length > 0, refetchInterval: 15_000 })
  const rows = (data ?? []).slice(0, 8)
  return (
    <Card title="Today’s transactions" action={<Chip size="small" color={isFetching ? 'default' : 'success'} variant="outlined" label="Live" sx={{ height: 20 }} />}>
      <Table size="small" aria-label="Today’s transactions" sx={{ '& td, & th': { py: 0.4, px: 0.75, fontSize: 12.5 } }}>
        <TableHead><TableRow><TableCell>Time</TableCell><TableCell>Type</TableCell><TableCell>Member</TableCell><TableCell align="right">Amount</TableCell></TableRow></TableHead>
        <TableBody>
          {rows.map((r) => (
            <TableRow key={`${r.year}-${r.module}-${r.code}-${r.ctlNo}`}>
              <TableCell sx={{ fontVariantNumeric: 'tabular-nums' }}>{r.time?.slice(0, 5)}</TableCell><TableCell>{r.type}</TableCell>
              <TableCell sx={{ maxWidth: 110, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{r.clientName ?? '—'}</TableCell>
              <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>{money(r.total)}</TableCell>
            </TableRow>
          ))}
          {data && rows.length === 0 && <TableRow><TableCell colSpan={4}><Typography sx={{ p: 1, textAlign: 'center', fontSize: 12.5 }} color="text.secondary">Nothing yet today.</Typography></TableCell></TableRow>}
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
      <MemberSearchBox lk={lk} onPick={setId} width={{ xs: '100%', md: '25%' }} />

      <Box ref={container} sx={{ display: 'grid', alignItems: 'start', gridTemplateColumns: `${cols[0] === 0 ? '0px' : `minmax(0, ${cols[0]}fr)`} 10px ${cols[1] === 0 ? '0px' : `minmax(0, ${cols[1]}fr)`} 10px ${cols[2] === 0 ? '0px' : `minmax(0, ${cols[2]}fr)`}` }}>
        <Box className="col" sx={{ display: 'flex', flexDirection: 'column', gap: 1.25, minWidth: 0, ...(cols[0] === 0 && { visibility: 'hidden', overflow: 'hidden' }) }}>
          {id ? <MemberCard id={id} onChange={() => setId(null)} lk={lk} /> : (
            <Card title="Member information"><Typography sx={{ fontSize: 13 }} color="text.secondary">Search for the member to take a deposit from or pay a withdrawal to.</Typography></Card>
          )}
          <Card title="Go to account">
            <Box sx={{ display: 'grid', gap: 0.75, gridTemplateColumns: 'repeat(3, minmax(0, 1fr))' }}>
              {QUICK.map((q) => (
                <ButtonBase
                  key={q.label} disabled={!id} onClick={() => { setSection(q.section); if (q.preset) setPreset({ ...q.preset, seq: (preset?.seq ?? 0) + 1 }) }} aria-label={q.label}
                  sx={(t) => ({ flexDirection: 'column', gap: 0.25, py: 1, borderRadius: '5px', border: 1, borderColor: 'divider', '&:hover': { bgcolor: t.palette.action.hover }, '&.Mui-disabled': { opacity: 0.45 } })}
                >
                  <Box aria-hidden sx={{ display: 'flex', fontSize: 22, color: q.color }}>{q.icon}</Box>
                  <Typography sx={{ fontSize: 12.5, fontWeight: 600 }}>{q.label}</Typography>
                </ButtonBase>
              ))}
            </Box>
          </Card>
          <TodayCard setup={setup} />
        </Box>

        <Splitter index={0} cols={cols} setCols={setCols} container={container} />
        <Box className="col" sx={{ minWidth: 0, ...(cols[1] === 0 && { visibility: 'hidden', overflow: 'hidden' }) }}>
          {!id ? (
            <Box sx={{ p: 4, textAlign: 'center' }}><Typography color="text.secondary">Search for a member above to start a transaction.</Typography></Box>
          ) : (
            <>
              <TellerTransaction id={id} preset={preset} />
              <Box role="tablist" aria-label="Member accounts" sx={(t) => ({ display: 'flex', gap: 0.5, p: 0.5, mb: 1, ...glass(t) })}>
                {SECTIONS.map((x) => (
                  <Button key={x} role="tab" aria-selected={section === x} size="small" variant={section === x ? 'contained' : 'text'} onClick={() => setSection(x)} sx={{ textTransform: 'none' }}>{x}</Button>
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
          <Card title="Notes and reminders">
            <Box component="ul" sx={{ m: 0, pl: 2, fontSize: 12.5, display: 'grid', gap: 0.5 }}>{REMINDERS.map((r) => <li key={r}>{r}</li>)}</Box>
          </Card>
        </Box>
      </Box>
    </Box>
  )
}
