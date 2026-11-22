import { Alert, Box, Chip, FormControlLabel, Stack, Switch, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography } from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { getStarterSetup, loadStarterLists, type StarterList, type StarterStatus } from './api'
import { appBackground, glass } from './glass'
import { ConfirmButton } from './ui/dialogs'
import { LoadingBlock } from './ui/Loading'
import { Hinted, ScreenTitle } from './appleUi'

const plural = (n: number, one: string, many = one + 's') => `${n.toLocaleString()} ${n === 1 ? one : many}`

/** The standing of one list in words, for its Status cell. */
export function standing(l: StarterList): { text: string; color: 'success' | 'info' | 'warning' | 'error' } {
  if (l.problem) return { text: 'Cannot be loaded here', color: 'error' }
  if (l.missing > 0) return { text: `${plural(l.missing, 'row')} to add`, color: 'info' }
  if (l.leftOut > 0) return { text: `${plural(l.leftOut, 'row')} left out`, color: 'warning' }
  return { text: 'Complete', color: 'success' }
}

/**
 * Starter setup (Settings > System, administrators): the system's standard lists (account statuses, ledger postings, terms, payment modes, genders,
 * civil statuses, places and the rest) that the application ships, compared with the connected database. Missing rows are added with one button and a
 * reason; rows already there are never changed, nothing is deleted, and each list added to is recorded in the audit log.
 */
export default function StarterSetupPage() {
  const q = useQuery({ queryKey: ['starter-setup'], queryFn: getStarterSetup })
  return (
    <Box component="main" sx={(t) => ({ minHeight: 'calc(100dvh - 44px)', p: 2, background: appBackground(t) })}>
      <Box sx={{ maxWidth: 960, mx: 'auto' }}>
        <ScreenTitle title="Starter setup" sx={{ mb: 2 }}>
          The standard lists the system works with: account statuses and ledger postings, terms and payment modes, loan classes and purposes, genders, civil statuses,
          places, notes and coins, and others. A new database has none of them, and screens such as Products, New Member and the teller window cannot work without them.
          Loading adds only the rows that are missing; rows already here are kept as they are and nothing is deleted.
        </ScreenTitle>
        {q.data ? <Lists s={q.data} /> : <LoadingBlock q={q} what="the standard lists" />}
      </Box>
    </Box>
  )
}

function Lists({ s }: { s: StarterStatus }) {
  const qc = useQueryClient()
  const [reason, setReason] = useState('')
  const [onlyOpen, setOnlyOpen] = useState(s.missing > 0 || s.lists.some((l) => l.problem || l.leftOut > 0))
  const toAdd = s.lists.filter((l) => l.missing > 0)
  const load = useMutation({
    mutationFn: () => loadStarterLists(reason.trim()),
    meta: { done: (r: unknown) => { const x = r as { added: number; loaded: string[] }; return `${plural(x.added, 'row')} added to ${plural(x.loaded.length, 'list')} and recorded in the audit log.` } },
    onSuccess: (r) => { qc.setQueryData(['starter-setup'], r.status); setReason(''); void qc.invalidateQueries() },
  })
  const shown = onlyOpen ? s.lists.filter((l) => l.missing > 0 || l.leftOut > 0 || l.problem) : s.lists
  const groups = [...new Set(shown.map((l) => l.group))]

  return (
    <Stack spacing={2}>
      {s.missing === 0 && !s.lists.some((l) => l.problem)
        ? <Alert severity="success">Every standard list is complete on this database{s.leftOut > 0 ? `, apart from ${plural(s.leftOut, 'row')} left out (see below)` : ''}.</Alert>
        : <Alert severity="info">{plural(s.missing, 'row')} {s.missing === 1 ? 'is' : 'are'} missing in {plural(toAdd.length, 'list')}. Give a reason and load them below.</Alert>}
      {!s.canLoad && s.cannotLoad && <Alert severity="warning">{s.cannotLoad}</Alert>}

      {s.missing > 0 && (
        <Box sx={(t) => ({ ...glass(t), p: 2 })}>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} sx={{ alignItems: { sm: 'center' } }}>
            <Hinted tip="Recorded in the audit log with each list added to."><TextField label="Reason" value={reason} onChange={(e) => setReason(e.target.value)} placeholder="For example: setting up the new database" sx={{ flex: 1 }}
              disabled={!s.canLoad} /></Hinted>
            <ConfirmButton variant="contained" disabled={!s.canLoad || reason.trim().length < 3 || load.isPending}
              title="Load the missing rows?" confirmLabel="Load them"
              message={<>This adds {plural(s.missing, 'missing row')} to {plural(toAdd.length, 'standard list')} on this database, all together or not at all.
                Rows already here are not changed and nothing is deleted. Each list added to is recorded in the audit log with your reason. Added rows cannot be removed from
                this screen afterwards.</>}
              onConfirm={() => load.mutate()}>
              {load.isPending ? 'Loading…' : `Load ${plural(s.missing, 'missing row')}`}
            </ConfirmButton>
          </Stack>
          {load.isError && <Alert severity="error" sx={{ mt: 1.5 }}>{(load.error as Error).message}</Alert>}
        </Box>
      )}

      <Box sx={(t) => ({ ...glass(t), p: 0, overflowX: 'auto' })}>
        <Stack direction="row" sx={{ px: 2, py: 1, alignItems: 'center', justifyContent: 'space-between' }}>
          <Typography sx={{ fontSize: 13, fontWeight: 600 }}>{plural(s.lists.length, 'standard list')}</Typography>
          <FormControlLabel control={<Switch checked={onlyOpen} onChange={(e) => setOnlyOpen(e.target.checked)} />} label="Only lists with something to do" />
        </Stack>
        <Table size="small" aria-label="Standard lists">
          <TableHead>
            <TableRow>
              <TableCell>List</TableCell>
              <TableCell align="right">Here</TableCell>
              <TableCell align="right">Shipped</TableCell>
              <TableCell>Status</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {shown.length === 0 && (
              <TableRow><TableCell colSpan={4}><Typography sx={{ fontSize: 13 }} color="text.secondary">Nothing to do: every list is complete.</Typography></TableCell></TableRow>
            )}
            {groups.map((g) => [
              <TableRow key={`g-${g}`}><TableCell colSpan={4} sx={{ fontWeight: 600, bgcolor: 'action.hover' }}>{g}</TableCell></TableRow>,
              ...shown.filter((l) => l.group === g).map((l) => {
                const st = standing(l)
                const why = l.problem ? [l.problem] : l.leftOutWhy
                return (
                  <TableRow key={l.table}>
                    <TableCell>
                      <Typography sx={{ fontSize: 13 }}>{l.label}</Typography>
                      <Typography sx={{ fontSize: 11 }} color="text.secondary">{l.table}</Typography>
                    </TableCell>
                    <TableCell align="right">{l.here.toLocaleString()}</TableCell>
                    <TableCell align="right">{l.inPackage.toLocaleString()}</TableCell>
                    <TableCell>
                      <Stack spacing={0.5} sx={{ alignItems: 'flex-start' }}>
                        <Chip size="small" color={st.color} variant={st.color === 'success' ? 'outlined' : 'filled'} label={st.text} />
                        {l.missing > 0 && l.leftOut > 0 && <Chip size="small" color="warning" label={`${plural(l.leftOut, 'row')} left out`} />}
                        {why.map((w) => <Typography key={w} sx={{ fontSize: 11, maxWidth: 420 }} color="text.secondary">{w}</Typography>)}
                      </Stack>
                    </TableCell>
                  </TableRow>
                )
              }),
            ])}
          </TableBody>
        </Table>
      </Box>
    </Stack>
  )
}
