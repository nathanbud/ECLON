import { Box, ButtonBase, Skeleton, Typography } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { FiCreditCard, FiDatabase, FiDollarSign, FiTrendingUp } from 'react-icons/fi'
import { getLoans, getSavings, getShares, getTimeDeposits } from '../api'
import { type Id } from '../clients'
import { glass } from '../glass'
import { tileFill } from '../theme'
import { money, tabular } from '../ui/format'
import { TABS } from './constants'

interface TileProps {
  label: string
  icon: ReactNode
  /** The figure, or null while it loads or when it cannot be read. */
  value: number | null
  loading: boolean
  /** The small line under the figure. */
  foot: string
  /** The first tile is filled with colour, like the lead card of a dashboard. */
  lead?: boolean
  openLabel: string
  onOpen: () => void
}

function Tile({ label, icon, value, loading, foot, lead = false, openLabel, onOpen }: TileProps) {
  return (
    <ButtonBase
      onClick={onOpen} aria-label={`${label}: ${value == null ? 'not available' : money(value)}. Open ${openLabel}`}
      sx={(t) => ({
        display: 'block', textAlign: 'left', width: '100%', p: 1.75, borderRadius: '5px', minHeight: 112,
        transition: 'transform 140ms cubic-bezier(.2,.8,.2,1), box-shadow 140ms',
        ...(lead
          ? { backgroundColor: tileFill.blue, color: '#fff', boxShadow: '0 6px 16px rgba(0,98,204,0.28)' }
          : { ...glass(t), color: 'text.primary' }),
        '&:hover': { transform: 'translateY(-1px)' },
        '&.Mui-focusVisible': { outline: `2px solid ${t.palette.primary.main}`, outlineOffset: 2 },
        '@media (prefers-reduced-motion: reduce)': { transition: 'none', '&:hover': { transform: 'none' } },
      })}
    >
      <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 1 }}>
        <Typography sx={{ fontSize: 12, fontWeight: 600, letterSpacing: '0.06em', textTransform: 'uppercase', color: lead ? 'rgba(255,255,255,0.9)' : 'text.secondary' }}>{label}</Typography>
        <Box aria-hidden sx={{ display: 'flex', fontSize: 17, color: lead ? 'rgba(255,255,255,0.9)' : 'text.secondary' }}>{icon}</Box>
      </Box>
      {loading
        ? <Skeleton width="70%" height={36} sx={lead ? { bgcolor: 'rgba(255,255,255,0.3)' } : undefined} />
        : <Typography sx={{ fontSize: 26, fontWeight: 300, lineHeight: 1.25, letterSpacing: '-0.01em', ...tabular }}>{value == null ? '—' : money(value)}</Typography>}
      <Typography sx={{ fontSize: 12.5, mt: 0.75, color: lead ? 'rgba(255,255,255,0.85)' : 'text.secondary' }}>{loading ? ' ' : foot}</Typography>
    </ButtonBase>
  )
}

const plural = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`

/**
 * The four balances of a member at a glance, as the lead cards of the profile: share capital, savings, loans owed and time deposits. They read the same summaries as the account
 * tabs (same query keys), so opening a tab afterwards costs nothing, and pressing a card opens that tab. A balance that cannot be read shows a dash and never stops the page.
 */
export function StatTiles({ id, onOpen }: { id: Id; onOpen: (tab: number) => void }) {
  const shares = useQuery({ queryKey: ['member-shares', id], queryFn: () => getShares(id.branchCode, id.clientId), staleTime: 60_000, retry: false })
  const savings = useQuery({ queryKey: ['member-savings', id], queryFn: () => getSavings(id.branchCode, id.clientId), staleTime: 60_000, retry: false })
  const loans = useQuery({ queryKey: ['member-loans', id.branchCode, id.clientId, false], queryFn: () => getLoans(id.branchCode, id.clientId, false), staleTime: 60_000, retry: false })
  const deposits = useQuery({ queryKey: ['member-time-deposits', id, false], queryFn: () => getTimeDeposits(id.branchCode, id.clientId, false), staleTime: 60_000, retry: false })
  const at = (name: string) => TABS.indexOf(name)

  return (
    <Box role="group" aria-label="Balances" sx={{ display: 'grid', gap: 1.5, gridTemplateColumns: { xs: 'repeat(2, minmax(0, 1fr))', lg: 'repeat(4, minmax(0, 1fr))' } }}>
      <Tile lead label="Share capital" icon={<FiDatabase />} openLabel="Share Capital" onOpen={() => onOpen(at('Share Capital'))}
        value={shares.data?.totalBalance ?? null} loading={shares.isLoading} foot={shares.data ? plural(shares.data.accounts.length, 'account', 'accounts') : 'Not available'} />
      <Tile label="Savings" icon={<FiDollarSign />} openLabel="Savings" onOpen={() => onOpen(at('Savings'))}
        value={savings.data?.totalBalance ?? null} loading={savings.isLoading}
        foot={savings.data ? `${plural(savings.data.accounts.length, 'account', 'accounts')}${savings.data.totalHeld ? ` · held ${money(savings.data.totalHeld)}` : ''}` : 'Not available'} />
      <Tile label="Loans owed" icon={<FiCreditCard />} openLabel="Loans" onOpen={() => onOpen(at('Loans'))}
        value={loans.data?.totalOutstanding ?? null} loading={loans.isLoading}
        foot={loans.data ? `${plural(loans.data.loans.length, 'loan', 'loans')}${loans.data.totalPastDue ? ` · past due ${money(loans.data.totalPastDue)}` : ''}` : 'Not available'} />
      <Tile label="Time deposits" icon={<FiTrendingUp />} openLabel="Time Deposits" onOpen={() => onOpen(at('Time Deposits'))}
        value={deposits.data?.totalBalance ?? null} loading={deposits.isLoading}
        foot={deposits.data ? `${plural(deposits.data.deposits.length, 'deposit', 'deposits')}${deposits.data.totalAccrued ? ` · accrued ${money(deposits.data.totalAccrued)}` : ''}` : 'Not available'} />
    </Box>
  )
}
