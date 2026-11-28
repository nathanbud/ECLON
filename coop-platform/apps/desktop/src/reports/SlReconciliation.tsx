import { Alert, Box, Button, Chip, CircularProgress, Collapse, IconButton, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography } from '@mui/material'
import { useMutation } from '@tanstack/react-query'
import { Fragment, useState } from 'react'
import { FiAlertTriangle, FiCheckCircle, FiChevronDown, FiChevronRight, FiDownload, FiPrinter, FiRefreshCw } from 'react-icons/fi'
import { getSlReconciliation, type ReconGroup, type SlReconciliation as Result } from '../api'
import { useMe } from '../session'
import { BranchSelect } from '../ui/BranchSelect'
import { money, tabular } from '../ui/format'
import { ScreenTitle } from '../appleUi'

const today = () => new Date().toLocaleDateString('en-CA')

const csvCell = (s: string) => (/[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s)

/** One line per group, then its types and accounts indented, as plain numbers so a spreadsheet can add them up. */
function toCsv(r: Result): string {
  const lines = [['Group', 'Account or type', 'Members (SL)', 'Ledger (GL)', 'Difference', 'Reconciled'].join(',')]
  for (const g of r.groups) {
    lines.push([csvCell(g.title), '', g.members.toFixed(2), g.ledger.toFixed(2), g.difference.toFixed(2), g.reconciled ? 'yes' : 'no'].join(','))
    for (const t of g.types) lines.push(['', csvCell(`Type ${t.slc}/${t.slt} ${t.name}`), t.total.toFixed(2), '', '', ''].join(','))
    for (const a of g.accounts) lines.push(['', csvCell(`Account ${a.account} ${a.name}`), '', a.balance.toFixed(2), '', ''].join(','))
  }
  return lines.join('\r\n')
}

function download(name: string, text: string) {
  try {
    const url = URL.createObjectURL(new Blob(['﻿' + text], { type: 'text/csv;charset=utf-8' }))
    const a = document.createElement('a')
    a.href = url
    a.download = name
    a.click()
    URL.revokeObjectURL(url)
  } catch {
    // the browser refused the download; the table is still on screen
  }
}

function GroupRows({ g }: { g: ReconGroup }) {
  const [open, setOpen] = useState(!g.reconciled)
  const sub = { pl: 6, fontSize: 12, color: 'text.secondary', borderBottom: 'none', py: 0.25 } as const
  return (
    <Fragment>
      <TableRow hover sx={{ '& > td': { borderBottom: open ? 'none' : undefined } }}>
        <TableCell sx={{ width: 36, p: 0, pl: 0.5 }}>
          <IconButton size="small" aria-label={open ? `Hide the details of ${g.title}` : `Show the details of ${g.title}`} aria-expanded={open} onClick={() => setOpen((o) => !o)}>
            {open ? <FiChevronDown /> : <FiChevronRight />}
          </IconButton>
        </TableCell>
        <TableCell>
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
            <Box component="span" aria-hidden sx={{ display: 'flex', color: g.reconciled ? 'success.main' : 'error.main' }}>{g.reconciled ? <FiCheckCircle /> : <FiAlertTriangle />}</Box>
            <Typography sx={{ fontWeight: g.reconciled ? 400 : 600 }}>{g.title}</Typography>
          </Box>
        </TableCell>
        <TableCell align="right" sx={tabular}>{money(g.members)}</TableCell>
        <TableCell align="right" sx={tabular}>{money(g.ledger)}</TableCell>
        <TableCell align="right" sx={{ ...tabular, fontWeight: g.reconciled ? 400 : 700, color: g.reconciled ? 'success.main' : 'error.main' }}>{money(g.difference)}</TableCell>
      </TableRow>
      <TableRow>
        <TableCell colSpan={5} sx={{ p: 0, borderBottom: open ? undefined : 'none' }}>
          <Collapse in={open} unmountOnExit>
            <Table size="small" aria-label={`Details of ${g.title}`}>
              <TableBody>
                {g.types.map((t) => (
                  <TableRow key={`t${t.slc}/${t.slt}`}>
                    <TableCell sx={sub}>Members&apos; accounts · type {t.slc}/{t.slt} {t.name}</TableCell>
                    <TableCell align="right" sx={{ ...sub, ...tabular, width: 160 }}>{money(t.total)}</TableCell>
                    <TableCell sx={{ ...sub, width: 160 }} />
                    <TableCell sx={{ ...sub, width: 160 }} />
                  </TableRow>
                ))}
                {g.accounts.map((a) => (
                  <TableRow key={`a${a.account}`}>
                    <TableCell sx={sub}>Control account · {a.account} {a.name}</TableCell>
                    <TableCell sx={{ ...sub, width: 160 }} />
                    <TableCell align="right" sx={{ ...sub, ...tabular, width: 160 }}>{money(a.balance)}</TableCell>
                    <TableCell sx={{ ...sub, width: 160 }} />
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </Collapse>
        </TableCell>
      </TableRow>
    </Fragment>
  )
}

/**
 * SL reconciliation (Reports > Accounting): whether each control account in the chart equals the members' accounts posted through it. A group is a
 * member account type with every account its principal posts to (a loan type's current, past due, restructured and in litigation accounts together),
 * joined with the other types sharing an account. Differences come first; each group opens to its types and accounts. Read only. It adds up the whole
 * member ledger, so a run takes a few minutes (over four on the main database); one from the last ten minutes is shown again unless Run again is pressed.
 */
export default function SlReconciliation() {
  const me = useMe()
  const [branch, setBranch] = useState(me.branchCode ?? 1)
  const [asOf, setAsOf] = useState(today())
  const run = useMutation({ mutationFn: (fresh: boolean) => getSlReconciliation(branch, asOf, fresh) })
  const r = run.data
  return (
    <Box>
      <ScreenTitle title="SL reconciliation" sx={{ mb: 2 }}>
        Each control account in the chart must equal the total of the members&apos; accounts that post to it (savings, share capital, loans, time deposits,
        payables and funds). A difference means an entry reached one ledger and not the other, such as a journal posted straight to a control account.
      </ScreenTitle>
      <Box className="no-print" sx={{ display: 'flex', gap: 1.5, alignItems: 'center', flexWrap: 'wrap', mb: 2 }}>
        <BranchSelect value={branch} onChange={setBranch} />
        <TextField size="small" type="date" label="As of" value={asOf} onChange={(e) => setAsOf(e.target.value)} slotProps={{ inputLabel: { shrink: true }, htmlInput: { max: today() } }} />
        <Button variant="contained" onClick={() => run.mutate(false)} disabled={run.isPending || !asOf}>{run.isPending ? 'Adding up…' : 'Run'}</Button>
        {r && <Button startIcon={<FiRefreshCw />} onClick={() => run.mutate(true)} disabled={run.isPending}>Run again</Button>}
        {r && <Button startIcon={<FiDownload />} onClick={() => download(`sl-reconciliation-${r.asOf}.csv`, toCsv(r))}>CSV</Button>}
        {r && <Button startIcon={<FiPrinter />} onClick={() => window.print()}>Print</Button>}
      </Box>
      {run.isPending && (
        <Box role="status" sx={{ display: 'flex', alignItems: 'center', gap: 1.25, py: 3 }}>
          <CircularProgress size={18} />
          <Typography color="text.secondary">Adding up every member&apos;s ledger and the control accounts. This takes a few minutes; you can leave this screen open and carry on elsewhere.</Typography>
        </Box>
      )}
      {run.isError && <Alert severity="error" role="alert">{(run.error as Error).message}</Alert>}
      {r && !run.isPending && (
        <Box id="report-result">
          {r.groups.length === 0 ? (
            <Typography color="text.secondary">No member account type of this branch posts to the chart, so there is nothing to reconcile.</Typography>
          ) : (
            <>
              <Box sx={{ display: 'flex', gap: 1, alignItems: 'center', flexWrap: 'wrap', mb: 1.5 }}>
                {r.unreconciled === 0
                  ? <Chip color="success" icon={<FiCheckCircle />} label={r.groups.length === 1 ? 'The group reconciles' : `All ${r.groups.length} groups reconcile`} />
                  : <Chip color="error" icon={<FiAlertTriangle />} label={`${r.unreconciled} of ${r.groups.length} group${r.groups.length === 1 ? '' : 's'} ${r.unreconciled === 1 ? 'does' : 'do'} not reconcile`} />}
                {r.unreconciled > 0 && <Typography sx={{ ...tabular }}>Differences add up to {money(r.totalDifference)}</Typography>}
              </Box>
              <Table size="small" aria-label="SL reconciliation">
                <TableHead>
                  <TableRow>
                    <TableCell />
                    <TableCell sx={{ fontWeight: 700 }}>Group</TableCell>
                    <TableCell align="right" sx={{ fontWeight: 700, width: 160 }}>Members (SL)</TableCell>
                    <TableCell align="right" sx={{ fontWeight: 700, width: 160 }}>Ledger (GL)</TableCell>
                    <TableCell align="right" sx={{ fontWeight: 700, width: 160 }}>Difference</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>{r.groups.map((g) => <GroupRows key={g.title + g.accounts.map((a) => a.account).join()} g={g} />)}</TableBody>
              </Table>
              <Typography variant="body2" sx={{ mt: 1.5 }} color="text.secondary">
                As of {r.asOf}. Liabilities and equity (savings, share capital, payables) are shown as positive credit balances. Difference = ledger less members.
                Run by {r.ranBy} on {r.ranAt.replace('T', ' ')}{r.kept ? ' (shown again from a run in the last ten minutes)' : ''}.
              </Typography>
            </>
          )}
        </Box>
      )}
    </Box>
  )
}
