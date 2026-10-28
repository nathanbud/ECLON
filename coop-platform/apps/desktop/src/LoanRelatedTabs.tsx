import { Alert, Autocomplete, Box, Button, Chip, MenuItem, TextField, Skeleton, Tab, Table, TableBody, TableCell, TableHead, TableRow, Tabs, Typography } from '@mui/material'
import { useQueries, useQuery } from '@tanstack/react-query'
import { useEffect, useState, type ReactNode } from 'react'
import { getCoMakerAccounts, getLoans, getMember, getSavings, searchMembers, type Member } from './api'
import { displayName, memberNo, statusOf } from './clients'
import { money } from './FoTransactionForm'
import MoneyField from './MoneyField'
import { tabular } from './ui/format'

const num = tabular

/** What the member has on hold: the savings holds that reduce what they can use. */
function HoldsTab({ branchCode, clientId }: { branchCode: number; clientId: number }) {
  const { data, error, isLoading } = useQuery({ queryKey: ['member-savings', branchCode, clientId], queryFn: () => getSavings(branchCode, clientId), staleTime: 60_000 })
  if (isLoading) return <Skeleton variant="rounded" height={90} />
  if (error) return <Alert severity="info">{(error as Error).message}</Alert>
  const rows = data?.accounts.filter((a) => a.statusName !== 'Closed') ?? []
  if (rows.length === 0) return <Typography color="text.secondary" sx={{ fontSize: 13 }}>This member has no open savings accounts.</Typography>
  return (
    <>
      <Typography sx={{ fontSize: 13, mb: 1, ...num }}>Total on hold <strong>{money(data?.totalHeld ?? 0)}</strong></Typography>
      <Table size="small" aria-label="Savings holds">
        <TableHead><TableRow><TableCell>Account</TableCell><TableCell align="right">Balance</TableCell><TableCell align="right">On hold</TableCell><TableCell align="right">Available</TableCell></TableRow></TableHead>
        <TableBody>
          {rows.map((a) => (
            <TableRow key={`${a.typeCode}-${a.refNo}`}>
              <TableCell>{a.type ?? a.typeCode} · {a.refNoText ?? a.refNo}</TableCell>
              <TableCell align="right" sx={num}>{money(a.balance)}</TableCell>
              <TableCell align="right" sx={num}>{money(a.held)}</TableCell>
              <TableCell align="right" sx={num}>{money(a.available)}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </>
  )
}

/** The member's other loans with a balance, so the clerk sees what they already owe. */
function ExistingLoansTab({ branchCode, clientId }: { branchCode: number; clientId: number }) {
  const { data, error, isLoading } = useQuery({ queryKey: ['member-loans', branchCode, clientId, false], queryFn: () => getLoans(branchCode, clientId, false), staleTime: 60_000 })
  if (isLoading) return <Skeleton variant="rounded" height={90} />
  if (error) return <Alert severity="info">{(error as Error).message}</Alert>
  if (!data || data.loans.length === 0) return <Typography color="text.secondary" sx={{ fontSize: 13 }}>This member has no loans with a balance.</Typography>
  return (
    <>
      <Typography sx={{ fontSize: 13, mb: 1, ...num }}>Outstanding <strong>{money(data.totalOutstanding)}</strong>{data.totalPastDue > 0 && <> · past due <strong>{money(data.totalPastDue)}</strong></>}</Typography>
      <Table size="small" aria-label="Existing loans">
        <TableHead><TableRow><TableCell>Loan</TableCell><TableCell align="right">Outstanding</TableCell><TableCell align="right">Days late</TableCell></TableRow></TableHead>
        <TableBody>
          {data.loans.map((l) => (
            <TableRow key={`${l.typeCode}-${l.refNo}`}>
              <TableCell>{l.type ?? l.typeCode} #{l.refNo}</TableCell>
              <TableCell align="right" sx={num}>{money(l.outstanding)}</TableCell>
              <TableCell align="right" sx={num}>{l.daysPastDue > 0 ? l.daysPastDue : '—'}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </>
  )
}

export interface CoMakerHoldInput { amount: string; account: string } // account is "class/type/ref"

/**
 * The co-makers added so far, one row each: who they are, the account of theirs the agreed amount is held on, and the amount. An empty amount
 * means the co-maker signs without a hold. The hold is placed when the loan release is posted.
 */
export function CoMakerRows({ branchCode, applicant, ids, holds, onChange, onRemove }: {
  branchCode: number; applicant: number; ids: number[]; holds: Record<number, CoMakerHoldInput>
  onChange: (id: number, v: CoMakerHoldInput) => void; onRemove: (id: number) => void
}) {
  const members = useQueries({ queries: ids.map((id) => ({ queryKey: ['member', branchCode, id], queryFn: () => getMember(branchCode, id), staleTime: 60_000, retry: false })) })
  const accounts = useQueries({ queries: ids.map((id) => ({ queryKey: ['comaker-accounts', branchCode, id], queryFn: () => getCoMakerAccounts(branchCode, applicant, id), staleTime: 30_000, retry: false })) })
  if (ids.length === 0) return <Typography sx={{ fontSize: 13, mt: 1 }} color="text.secondary">No co-makers yet. Search above to add one.</Typography>
  return (
    <Box sx={{ display: 'grid', gap: 1.25, mt: 1.25 }}>
      {ids.map((id, i) => {
        const m = members[i]?.data
        const list = accounts[i]?.data ?? []
        const st = m ? statusOf(m) : null
        const h = holds[id] ?? { amount: '', account: '' }
        // the type the administrator chose to suggest first (share capital to begin with) is where the hold goes unless the clerk picks another
        const def = list.find((x) => x.preferred) ?? list.find((x) => x.slc === 31) ?? list[0]
        const key = h.account || (def ? `${def.slc}/${def.slt}/${def.refNo}` : '')
        const acct = list.find((x) => `${x.slc}/${x.slt}/${x.refNo}` === key)
        const over = acct != null && Number(h.amount) > acct.available
        return (
          <Box key={id} sx={{ border: 1, borderColor: 'divider', borderRadius: '8px', p: 1.25 }}>
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
              <Typography sx={{ flex: 1, fontSize: 14, fontWeight: 600 }}>
                {members[i]?.isLoading ? `${id} …` : m ? `${displayName(m)} · ${id}` : `${id} · not a member`}
              </Typography>
              {st?.kind === 'default' && <Chip size="small" color="warning" variant="outlined" label="Not active" />}
              <Button size="small" color="inherit" onClick={() => onRemove(id)}>Remove</Button>
            </Box>
            {m && (
              <Box sx={{ display: 'grid', gridTemplateColumns: '1.4fr 1fr', gap: 1, mt: 1 }}>
                <TextField size="small" select label="Hold on account" value={key} disabled={list.length === 0}
                  onChange={(e) => onChange(id, { ...h, account: e.target.value })}
                  helperText={list.length === 0 ? 'No active savings or share capital account' : acct ? `Free to hold: ${money(acct.available)}` : undefined}>
                  {list.map((x) => <MenuItem key={`${x.slc}/${x.slt}/${x.refNo}`} value={`${x.slc}/${x.slt}/${x.refNo}`}>{x.type ?? x.slt} · {x.refNo} · free {money(x.available)}</MenuItem>)}
                </TextField>
                <MoneyField size="small" label="Amount agreed" value={h.amount} disabled={list.length === 0} error={over}
                  helperText={over ? 'More than is free' : undefined} onValueChange={(v) => onChange(id, { ...h, account: key, amount: v })} />
              </Box>
            )}
          </Box>
        )
      })}
    </Box>
  )
}

/** Search for a member by name or number and add them as a co-maker. The applicant and anyone already added are left out of the results. */
export function CoMakerPicker({ applicant, ids, onAdd }: { applicant: number; ids: number[]; onAdd: (id: number) => void }) {
  const [text, setText] = useState('')
  const [q, setQ] = useState('')
  useEffect(() => {
    const h = setTimeout(() => setQ(text.trim()), 300)
    return () => clearTimeout(h)
  }, [text])
  const found = useQuery({ queryKey: ['comaker-search', q], queryFn: () => searchMembers(q, 0, 10), enabled: q.length >= 2 })
  const options = (found.data?.content ?? []).filter((m) => m.id.clientId !== applicant && !ids.includes(m.id.clientId))
  return (
    <Autocomplete<Member> size="small" fullWidth options={options} value={null} inputValue={text} filterOptions={(o) => o} loading={found.isFetching}
      getOptionLabel={(m) => `${displayName(m)} · ${memberNo(m.id)}`} isOptionEqualToValue={(a, b) => a.id.clientId === b.id.clientId}
      noOptionsText={q.length < 2 ? 'Type at least 2 letters of the name, or the member number' : 'No member found'}
      onInputChange={(_, v, reason) => { if (reason !== 'reset') setText(v) }}
      onChange={(_, m) => { if (m) { onAdd(m.id.clientId); setText(''); setQ('') } }}
      renderOption={({ key, ...props }, m) => (
        <li key={key} {...props}>
          <Box><Typography sx={{ fontSize: 14 }}>{displayName(m)}</Typography>
            <Typography sx={{ fontSize: 12 }} color="text.secondary">{memberNo(m.id)}{m.city ? ` · ${m.city}` : ''}</Typography></Box>
        </li>
      )}
      renderInput={(params) => <TextField {...params} label="Search a member to add as co-maker" />} />
  )
}

/** The right half of the loan profile: everything about the loan that is not its amount and term, one tab each. */
export default function LoanRelatedTabs({ branchCode, clientId, coMakers, collateral }: { branchCode: number; clientId: number; coMakers: ReactNode; collateral: ReactNode }) {
  const [tab, setTab] = useState(0)
  const tabs: [string, ReactNode][] = [
    ['Co-makers', coMakers],
    ['Collateral', collateral],
    ['On hold', <HoldsTab key="h" branchCode={branchCode} clientId={clientId} />],
    ['Existing loans', <ExistingLoansTab key="l" branchCode={branchCode} clientId={clientId} />],
  ]
  return (
    <Box component="section" aria-label="Related to the loan" sx={{ border: 1, borderColor: 'divider', borderRadius: '8px', minHeight: 280 }}>
      <Tabs value={tab} onChange={(_, v) => setTab(v)} variant="scrollable" scrollButtons={false} sx={{ borderBottom: 1, borderColor: 'divider', minHeight: 40, '& .MuiTab-root': { minHeight: 40, textTransform: 'none', fontSize: 13 } }}>
        {tabs.map(([label]) => <Tab key={label} label={label} />)}
      </Tabs>
      <Box role="tabpanel" sx={{ p: 1.5 }}>{tabs[tab][1]}</Box>
    </Box>
  )
}
