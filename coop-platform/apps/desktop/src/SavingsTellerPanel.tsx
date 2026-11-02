import { Avatar, Box, Button, ButtonBase, Chip, Skeleton, Table, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { FiArrowDownCircle, FiArrowUpCircle, FiClock, FiDollarSign, FiPercent, FiUser } from 'react-icons/fi'
import { getFoTellerTotals, getLoans, getLookups, getMember, getSavings, getShares, listFoTransactions, type FoSetup } from './api'
import { displayName, memberNo, statusOf, type Id } from './clients'
import MemberSearchBox from './MemberSearchBox'
import { money } from './FoTransactionForm'
import { glass } from './glass'
import LoansTab from './LoansTab'
import { useMemberImage } from './memberImage'
import { fmtDate } from './profile/format'
import { SavingsTab } from './profile/Savings'
import { ShareCapitalTab } from './profile/ShareCapital'
import { TimeDepositsTab } from './profile/TimeDeposits'
import { tileFill } from './theme'

const SECTIONS = ['Savings', 'Share Capital', 'Loans', 'Time Deposits'] as const
type Section = (typeof SECTIONS)[number]

/** What a teller does most, each one opening the account type it belongs to (the posting itself, with its limits, holds and approvals, is in the section below). */
const QUICK: { label: string; hint: string; section: Section; icon: React.ReactNode; color: string }[] = [
  { label: 'Deposit', hint: 'Savings', section: 'Savings', icon: <FiArrowDownCircle />, color: tileFill.green },
  { label: 'Withdraw', hint: 'Savings', section: 'Savings', icon: <FiArrowUpCircle />, color: tileFill.red },
  { label: 'Share capital', hint: 'Pay or withdraw', section: 'Share Capital', icon: <FiPercent />, color: tileFill.blue },
  { label: 'Loan payment', hint: 'Pay a loan', section: 'Loans', icon: <FiDollarSign />, color: tileFill.orange },
  { label: 'Time deposit', hint: 'Place or pay out', section: 'Time Deposits', icon: <FiClock />, color: tileFill.purple },
]

const REMINDERS = [
  'Verify the member’s identity for large transactions.',
  'Count your drawer at the start and the end of your shift (Summary tab).',
  'Print a receipt for every deposit and withdrawal.',
  'Follow the KYC and anti-money-laundering rules.',
]

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

  return (
    <Box sx={{ minWidth: 0, display: 'flex', flexDirection: 'column', gap: 1.25 }}>
      <MemberSearchBox lk={lk} onPick={setId} width={{ xs: '100%', md: '25%' }} />

      <Box sx={{ display: 'grid', gap: 1.25, alignItems: 'start', gridTemplateColumns: 'minmax(0, 3fr) minmax(0, 4fr) minmax(0, 3fr)' }}>
        <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.25, minWidth: 0 }}>
          {id ? <MemberCard id={id} onChange={() => setId(null)} lk={lk} /> : (
            <Card title="Member information"><Typography sx={{ fontSize: 13 }} color="text.secondary">Search for the member to take a deposit from or pay a withdrawal to.</Typography></Card>
          )}
          <Card title="Quick actions">
            <Box sx={{ display: 'grid', gap: 0.75, gridTemplateColumns: 'repeat(3, minmax(0, 1fr))' }}>
              {QUICK.map((q) => (
                <ButtonBase
                  key={q.label} disabled={!id} onClick={() => setSection(q.section)} aria-label={q.label} aria-pressed={!!id && section === q.section && q.label !== 'Withdraw'}
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

        <Box sx={{ minWidth: 0 }}>
          {!id ? (
            <Box sx={{ p: 4, textAlign: 'center' }}><Typography color="text.secondary">Search for a member above to start a transaction.</Typography></Box>
          ) : (
            <>
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

        <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.25, minWidth: 0 }}>
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
