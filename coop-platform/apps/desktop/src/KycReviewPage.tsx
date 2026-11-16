import { Alert, Box, Button, LinearProgress, Table, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { FiChevronLeft, FiChevronRight } from 'react-icons/fi'
import { getKycReview, type KycStatus } from './api'
import { Dot, listTable, PageTitle, Segmented, Surface } from './appleUi'
import { memberNo, type Id } from './clients'
import { appBackground } from './glass'

const SIZE = 50

/** The four kinds of attention, in the order they matter: an expired ID first, a member with nothing recorded last (the longest list). */
export const KYC_ATTENTION: { status: KycStatus; label: string; color: 'error' | 'warning' | 'grey'; empty: string }[] = [
  { status: 'EXPIRED', label: 'ID expired', color: 'error', empty: 'No active member has an expired ID on record.' },
  { status: 'INCOMPLETE', label: 'Incomplete', color: 'warning', empty: 'No KYC record is missing a required piece.' },
  { status: 'REVIEW_DUE', label: 'Review due', color: 'warning', empty: 'No review is overdue.' },
  { status: 'NONE', label: 'No record', color: 'grey', empty: 'Every active member has a KYC record.' },
]

const date = (s: string | null) => (s ? new Date(`${s.slice(0, 10)}T00:00:00`).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' }) : '—')
const risk = (r: string | null) => (r ? r[0] + r.slice(1).toLowerCase() : '—')

/**
 * Members whose KYC needs attention, one kind at a time, with the count of each. A row opens the member's profile on the KYC section, where it is put right.
 * For people who keep member records; the list is read only.
 */
export default function KycReviewPage({ onOpenMember }: { onOpenMember: (id: Id) => void }) {
  const [status, setStatus] = useState<KycStatus>('EXPIRED')
  const [page, setPage] = useState(0)
  const q = useQuery({ queryKey: ['kyc-review', status, page], queryFn: () => getKycReview(status, page, SIZE), placeholderData: keepPreviousData })
  const data = q.data
  const kind = KYC_ATTENTION.find((k) => k.status === status)!
  const count = (s: KycStatus) => data?.counts?.[s]
  const from = data && data.total ? page * SIZE + 1 : 0
  const to = data ? Math.min((page + 1) * SIZE, data.total) : 0
  const complete = count('COMPLETE')
  const active = data ? Object.values(data.counts).reduce((a, b) => a + b, 0) : 0

  return (
    <Box component="main" sx={(t) => ({ minHeight: 'calc(100dvh - 44px)', p: { xs: 2, md: 3 }, background: appBackground(t) })}>
      <Box sx={{ maxWidth: 1100, mx: 'auto' }}>
        <PageTitle title="KYC review">
          Active members whose Know Your Customer record needs attention. Open a member to put it right in the KYC section.
          {complete !== undefined && active > 0 && ` ${complete.toLocaleString()} of ${active.toLocaleString()} active members are complete.`}
        </PageTitle>

        <Box sx={{ mb: 1.5 }}>
          <Segmented
            label="Which members" value={status} onChange={(s) => { setStatus(s); setPage(0) }}
            options={KYC_ATTENTION.map((k) => ({ value: k.status, label: count(k.status) === undefined ? k.label : `${k.label} (${count(k.status)!.toLocaleString()})` }))}
          />
        </Box>

        {q.isError && <Alert severity="error" sx={{ mb: 1.5 }}>{(q.error as Error).message}</Alert>}

        <Surface label={kind.label}>
          {q.isFetching && <LinearProgress aria-label="Loading" sx={{ borderRadius: 0, height: 2 }} />}
          <Table size="small" sx={listTable}>
            <TableHead>
              <TableRow>
                <TableCell>Member</TableCell>
                <TableCell>Status</TableCell>
                <TableCell>ID</TableCell>
                <TableCell>ID expires</TableCell>
                <TableCell>Risk</TableCell>
                <TableCell>Next review</TableCell>
                <TableCell>Last changed</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {data?.rows.map((r) => (
                <TableRow
                  key={`${r.branchCode}-${r.clientId}`} hover tabIndex={0} sx={{ cursor: 'pointer' }}
                  aria-label={`Open ${r.name || memberNo(r)}`}
                  onClick={() => onOpenMember({ branchCode: r.branchCode, clientId: r.clientId })}
                  onKeyDown={(e) => { if (e.key === 'Enter') onOpenMember({ branchCode: r.branchCode, clientId: r.clientId }) }}
                >
                  <TableCell>
                    <Typography sx={{ fontSize: 13, fontWeight: 500 }}>{r.name || 'Unnamed client'}</Typography>
                    <Typography sx={{ fontSize: 12, fontVariantNumeric: 'tabular-nums' }} color="text.secondary">{memberNo(r)}</Typography>
                  </TableCell>
                  <TableCell><Dot color={kind.color}>{kind.label}</Dot></TableCell>
                  <TableCell>{r.idType ?? '—'}</TableCell>
                  <TableCell sx={{ fontVariantNumeric: 'tabular-nums' }}>{date(r.idExpiry)}</TableCell>
                  <TableCell>{risk(r.risk)}</TableCell>
                  <TableCell sx={{ fontVariantNumeric: 'tabular-nums' }}>{date(r.nextReview)}</TableCell>
                  <TableCell sx={{ fontVariantNumeric: 'tabular-nums' }}>{date(r.updatedAt)}</TableCell>
                </TableRow>
              ))}
              {data && data.rows.length === 0 && (
                <TableRow><TableCell colSpan={7} sx={{ py: 4, textAlign: 'center' }}><Typography color="text.secondary" sx={{ fontSize: 13 }}>{kind.empty}</Typography></TableCell></TableRow>
              )}
            </TableBody>
          </Table>
          {data && data.total > SIZE && (
            <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'flex-end', gap: 1, px: 1.5, py: 1 }}>
              <Typography sx={{ fontSize: 12, fontVariantNumeric: 'tabular-nums' }} color="text.secondary">{from.toLocaleString()}–{to.toLocaleString()} of {data.total.toLocaleString()}</Typography>
              <Button size="small" variant="outlined" aria-label="Previous page" disabled={page === 0} onClick={() => setPage((p) => p - 1)} sx={{ minWidth: 0, px: 0.75 }}><FiChevronLeft /></Button>
              <Button size="small" variant="outlined" aria-label="Next page" disabled={to >= data.total} onClick={() => setPage((p) => p + 1)} sx={{ minWidth: 0, px: 0.75 }}><FiChevronRight /></Button>
            </Box>
          )}
        </Surface>
      </Box>
    </Box>
  )
}
