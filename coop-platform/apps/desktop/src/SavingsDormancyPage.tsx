import { Alert, Box, Button, Checkbox, Stack, Table, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { findDormant, getDormancyReview, markDormant, reactivateDormant, type DormancyKey, type DormancyRow } from './api'
import { listTable, PageTitle, ReasonDialog, SearchField, SectionLabel, Segmented, Surface } from './appleUi'
import { appBackground } from './glass'
import { useMe } from './session'
import { money, tabular } from './ui/format'
import { LoadingRow } from './ui/Loading'

const keyOf = (r: DormancyRow) => `${r.clientId}/${r.typeCode}/${r.refNo}`
const toKey = (r: DormancyRow): DormancyKey => ({ clientId: r.clientId, typeCode: r.typeCode, refNo: r.refNo })
const account = (r: DormancyRow) => `${r.typeName ?? `Type ${r.typeCode}`} ${r.accountNo ?? r.refNo}`

/**
 * Savings dormancy: the savings accounts whose member has not deposited or withdrawn for the period set on their type (Settings > Products), for an administrator to mark
 * dormant with a reason; and the dormant accounts, which an administrator or a Membership Officer reactivates with a reason. Nothing is marked by itself.
 */
export default function SavingsDormancyPage() {
  const me = useMe()
  const officer = me.roles.some((r) => r.replace('ROLE_', '') === 'MEMBERSHIP_OFFICER')
  const [tab, setTab] = useState<'review' | 'dormant'>(me.admin ? 'review' : 'dormant')
  return (
    <Box component="main" sx={(t) => ({ minHeight: 'calc(100dvh - 44px)', p: { xs: 2, md: 3 }, background: appBackground(t) })}>
      <Box sx={{ maxWidth: 1100, mx: 'auto' }}>
        <PageTitle title="Savings Dormancy">
          Savings accounts with no deposit or withdrawal for the period set on their type are listed here to be marked dormant. A dormant account takes no deposit or withdrawal until it is reactivated. Interest and dividends do not count as activity. Every change needs a reason and is kept in the audit log.
        </PageTitle>
        {me.admin && (
          <Box sx={{ mb: 2 }}>
            <Segmented label="Show" value={tab} onChange={setTab} options={[{ value: 'review', label: 'Due to go dormant' }, { value: 'dormant', label: 'Dormant accounts' }]} />
          </Box>
        )}
        {tab === 'review' && me.admin ? <Review /> : (me.admin || officer) ? <Dormant /> : <Alert severity="info">Only administrators and Membership Officers can open this.</Alert>}
      </Box>
    </Box>
  )
}

function Review() {
  const qc = useQueryClient()
  const review = useQuery({ queryKey: ['dormancy-review'], queryFn: getDormancyReview })
  const [chosen, setChosen] = useState<Set<string>>(new Set())
  const [asking, setAsking] = useState(false)
  const [done, setDone] = useState<{ marked: number; skipped: string[] } | null>(null)
  const rows = useMemo(() => review.data?.rows ?? [], [review.data])
  const mark = useMutation({
    mutationFn: (reason: string) => markDormant(rows.filter((r) => chosen.has(keyOf(r))).map(toKey), reason),
    onSuccess: (r) => { setDone(r); setAsking(false); setChosen(new Set()); void qc.invalidateQueries({ queryKey: ['dormancy-review'] }) },
  })
  const all = rows.length > 0 && chosen.size === rows.length
  const toggle = (k: string) => setChosen((cur) => { const n = new Set(cur); if (n.has(k)) n.delete(k); else n.add(k); return n })
  const periods = (review.data?.types ?? []).filter((t) => t.months != null)

  return (
    <Stack spacing={1.5}>
      {done && (
        <Alert severity={done.skipped.length ? 'warning' : 'success'} onClose={() => setDone(null)}>
          {done.marked.toLocaleString()} account{done.marked === 1 ? ' was' : 's were'} marked dormant.{done.skipped.length > 0 && ` Skipped: ${done.skipped.join(' ')}`}
        </Alert>
      )}
      {review.error && <Alert severity="error">{(review.error as Error).message}</Alert>}
      {review.data && (
        <Typography color="text.secondary">
          {periods.length === 0
            ? 'No savings type has a dormancy period, so no account goes dormant. Set one under Settings > Products.'
            : `As of ${review.data.asOf}. Periods: ${periods.map((t) => `${t.typeName ?? t.typeCode} ${t.months} months`).join(', ')}.`}
        </Typography>
      )}
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
        <Box sx={{ flex: 1 }}><SectionLabel>{review.isLoading ? 'Looking for quiet accounts…' : `${rows.length.toLocaleString()} account${rows.length === 1 ? '' : 's'} due${review.data?.more ? ' (the first 500; mark these and look again)' : ''}`}</SectionLabel></Box>
        <Button variant="contained" disabled={chosen.size === 0} onClick={() => { mark.reset(); setAsking(true) }}>Mark {chosen.size || ''} dormant</Button>
      </Box>
      <Surface label="Accounts due to go dormant">
        <Box sx={{ maxHeight: 'calc(100dvh - 330px)', overflow: 'auto' }}>
          <Table size="small" stickyHeader aria-label="Accounts due to go dormant" sx={(t) => ({ ...listTable(t), '& .MuiTableCell-stickyHeader': { bgcolor: t.palette.background.paper } })}>
            <TableHead>
              <TableRow>
                <TableCell padding="checkbox"><Checkbox size="small" checked={all} indeterminate={chosen.size > 0 && !all} disabled={rows.length === 0}
                  onChange={() => setChosen(all ? new Set() : new Set(rows.map(keyOf)))} slotProps={{ input: { 'aria-label': 'Choose every account' } }} /></TableCell>
                <TableCell>Member</TableCell><TableCell>Account</TableCell><TableCell>Opened</TableCell><TableCell>Last deposit or withdrawal</TableCell><TableCell align="right">Balance</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {rows.map((r) => (
                <TableRow key={keyOf(r)} hover selected={chosen.has(keyOf(r))} onClick={() => toggle(keyOf(r))} sx={{ cursor: 'pointer' }}>
                  <TableCell padding="checkbox"><Checkbox size="small" checked={chosen.has(keyOf(r))} slotProps={{ input: { 'aria-label': `Choose ${r.clientName} ${account(r)}` } }} /></TableCell>
                  <TableCell>{r.clientName} <Typography variant="body2" component="span" color="text.secondary">#{r.clientId}</Typography></TableCell>
                  <TableCell>{account(r)}</TableCell>
                  <TableCell sx={tabular}>{r.opened ?? '—'}</TableCell>
                  <TableCell sx={tabular}>{r.lastActivity ?? 'Never'}</TableCell>
                  <TableCell align="right" sx={tabular}>{money(r.balance)}</TableCell>
                </TableRow>
              ))}
              {review.data && rows.length === 0 && <TableRow><TableCell colSpan={6} align="center" sx={{ py: 4, color: 'text.secondary' }}>No account is due to go dormant.</TableCell></TableRow>}
            </TableBody>
          </Table>
        </Box>
      </Surface>
      <ReasonDialog open={asking} title={`Mark ${chosen.size} account${chosen.size === 1 ? '' : 's'} dormant`} confirm="Mark dormant" busy={mark.isPending}
        intro="Each account is checked again first; one that had a deposit or withdrawal in the meantime is skipped. A dormant account takes no deposit or withdrawal until it is reactivated."
        error={mark.error ? (mark.error as Error).message : null} onCancel={() => setAsking(false)} onConfirm={(r) => mark.mutate(r)} />
    </Stack>
  )
}

function Dormant() {
  const [q, setQ] = useState('')
  const [reactivating, setReactivating] = useState<DormancyRow | null>(null)
  const [done, setDone] = useState<string | null>(null)
  const term = q.trim()
  const ready = /^\d+$/.test(term) || term.length >= 3
  const found = useQuery({ queryKey: ['dormant', term], queryFn: () => findDormant(term), enabled: ready })
  const qc = useQueryClient()
  const save = useMutation({
    mutationFn: (reason: string) => reactivateDormant(toKey(reactivating!), reason),
    onSuccess: () => { setDone(`${account(reactivating!)} of ${reactivating!.clientName} is active again.`); setReactivating(null); void qc.invalidateQueries({ queryKey: ['dormant'] }) },
  })
  return (
    <Stack spacing={1.5}>
      {done && <Alert severity="success" onClose={() => setDone(null)}>{done}</Alert>}
      <SearchField value={q} onChange={setQ} placeholder="Member number or at least 3 letters of a name" width={360} />
      {found.error && <Alert severity="error">{(found.error as Error).message}</Alert>}
      {ready && (
        <Surface label="Dormant accounts">
          <Table size="small" aria-label="Dormant accounts" sx={listTable}>
            <TableHead><TableRow><TableCell>Member</TableCell><TableCell>Account</TableCell><TableCell>Last deposit or withdrawal</TableCell><TableCell align="right">Balance</TableCell><TableCell /></TableRow></TableHead>
            <TableBody>
              <LoadingRow q={found} what="the accounts" errors={false} />
              {(found.data ?? []).map((r) => (
                <TableRow key={keyOf(r)} hover>
                  <TableCell>{r.clientName} <Typography variant="body2" component="span" color="text.secondary">#{r.clientId}</Typography></TableCell>
                  <TableCell>{account(r)}</TableCell>
                  <TableCell sx={tabular}>{r.lastActivity ?? 'Never'}</TableCell>
                  <TableCell align="right" sx={tabular}>{money(r.balance)}</TableCell>
                  <TableCell align="right"><Button size="small" onClick={() => { save.reset(); setReactivating(r) }}>Reactivate</Button></TableCell>
                </TableRow>
              ))}
              {found.data?.length === 0 && <TableRow><TableCell colSpan={5} align="center" sx={{ py: 3, color: 'text.secondary' }}>No dormant savings account matches.</TableCell></TableRow>}
            </TableBody>
          </Table>
        </Surface>
      )}
      <ReasonDialog open={reactivating != null} title="Reactivate this account" confirm="Reactivate" busy={save.isPending}
        intro={reactivating ? `${account(reactivating)} of ${reactivating.clientName} will take deposits and withdrawals again. Its dormancy period starts over from today.` : undefined}
        error={save.error ? (save.error as Error).message : null} onCancel={() => setReactivating(null)} onConfirm={(r) => save.mutate(r)} />
    </Stack>
  )
}
