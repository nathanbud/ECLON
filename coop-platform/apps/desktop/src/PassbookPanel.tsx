import { Alert, Box, Button, ButtonBase, Chip, Dialog, DialogActions, DialogContent, DialogTitle, Stack, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography } from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { FiBookOpen, FiPrinter, FiRotateCcw, FiSettings } from 'react-icons/fi'
import {
  confirmPassbookPage, getLookups, getPassbook, getPassbookAccounts, getPassbookLayout, getPassbookNextPage, getPassbookTestPage, reprintPassbook, savePassbookLayout,
  setPassbookPosition, type PassbookDetail, type PassbookLayout, type PassbookPage,
} from './api'
import { type Id } from './clients'
import { money } from './FoTransactionForm'
import { glass } from './glass'
import MemberSearchBox from './MemberSearchBox'
import { useMe } from './session'
import { LoadingBlock } from './ui/Loading'
import { printPdf } from './ui/printPdf'
import { notifyError } from './ui/notices'
import { SURFACE_RADIUS } from './theme'
import { AboutNote } from './appleUi'

type Acct = { typeCode: number; refNo: number }
const cell = { fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' } as const
const ACTION_WORDS: Record<string, string> = { PRINT: 'Printed', REPRINT: 'Reprinted', NEW_PASSBOOK: 'New passbook', POSITION: 'Position corrected' }

/** The lines the page will print, each on its line number, the way they land in the passbook. */
function PagePreview({ page }: { page: PassbookPage }) {
  return (
    <Table size="small" aria-label="Lines to print">
      <TableHead>
        <TableRow>
          <TableCell>Line</TableCell><TableCell>Date</TableCell><TableCell>Reference</TableCell><TableCell align="right">Deposit</TableCell>
          <TableCell align="right">Withdrawal</TableCell><TableCell align="right">Balance</TableCell><TableCell>TC</TableCell><TableCell>Teller No.</TableCell>
        </TableRow>
      </TableHead>
      <TableBody>
        {page.lines.map((l) => (
          <TableRow key={l.line}>
            <TableCell sx={cell}>{l.line}</TableCell><TableCell sx={cell}>{l.date}</TableCell><TableCell sx={cell}>{l.reference}</TableCell>
            <TableCell align="right" sx={cell}>{l.deposit}</TableCell><TableCell align="right" sx={cell}>{l.withdrawal}</TableCell>
            <TableCell align="right" sx={{ ...cell, fontWeight: 600 }}>{l.balance}</TableCell><TableCell sx={cell}>{l.tc}</TableCell><TableCell sx={cell}>{l.teller}</TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  )
}

/** Print the next page, then say whether it came out right; only a yes moves the passbook on. */
function NextPage({ d, a, clientId }: { d: PassbookDetail; a: Acct; clientId: number }) {
  const qc = useQueryClient()
  const [printed, setPrinted] = useState(false)
  const [note, setNote] = useState<string | null>(null)
  const page = d.pages[0]
  const print = useMutation({
    mutationFn: () => getPassbookNextPage(clientId, a.typeCode, a.refNo),
    onError: notifyError,
    onSuccess: async (blob) => {
      const how = await printPdf(blob, `passbook-${clientId}-${a.refNo}.pdf`)
      setNote(how === 'saved' ? 'The page was saved as a PDF instead; open it and print it at actual size.' : null)
      setPrinted(true)
    },
  })
  const confirm = useMutation({
    meta: { done: 'The passbook moved on to the next line.' },
    mutationFn: () => confirmPassbookPage(clientId, a.typeCode, a.refNo, d.position!.lastSeq, d.position!.lastLine, page.lastSeq),
    onSuccess: (r) => { qc.setQueryData(['passbook', clientId, a.typeCode, a.refNo], r); void qc.invalidateQueries({ queryKey: ['passbook-accounts', clientId] }); setPrinted(false) },
    onError: notifyError,
  })
  const turn = page.newPage && (d.position?.lastLine ?? 0) > 0
  return (
    <Box>
      <Typography variant="subtitle2" component="p" sx={{ mb: 0.5 }}>
        Page {page.number} of {d.pages.length}: lines {page.firstLine} to {page.lastLine}{page.newPage ? ', on a fresh page' : ', on the page in use'}
      </Typography>
      {turn && <Alert severity="info" sx={{ mb: 1 }}>The page in use is full. Turn to the next page and put the passbook in at its top before printing.</Alert>}
      <Box sx={{ overflowX: 'auto', mb: 1.5 }}><PagePreview page={page} /></Box>
      {!printed ? (
        <Button variant="contained" startIcon={<FiPrinter />} disabled={print.isPending} onClick={() => print.mutate()}>
          {print.isPending ? 'Preparing…' : `Print page (${page.lines.length} ${page.lines.length === 1 ? 'line' : 'lines'})`}
        </Button>
      ) : (
        <Alert severity="warning" icon={false} action={
          <Stack direction="row" spacing={1}>
            <Button color="inherit" onClick={() => setPrinted(false)}>No, print again</Button>
            <Button variant="contained" disabled={confirm.isPending} onClick={() => confirm.mutate()}>Yes, printed right</Button>
          </Stack>}>
          Did all {page.lines.length} lines print on lines {page.firstLine} to {page.lastLine}? The passbook moves on only when you say yes.
        </Alert>
      )}
      {note && <Alert severity="info" sx={{ mt: 1 }}>{note}</Alert>}
    </Box>
  )
}

/** Reprint printed entries (jammed page), record a new passbook, or correct the position. */
function Corrections({ d, a, clientId }: { d: PassbookDetail; a: Acct; clientId: number }) {
  const qc = useQueryClient()
  const [open, setOpen] = useState<'reprint' | 'new' | 'position' | null>(null)
  const [serial, setSerial] = useState('')
  const [reason, setReason] = useState('')
  const [fromSeq, setFromSeq] = useState('')
  const [toSeq, setToSeq] = useState('')
  const [firstLine, setFirstLine] = useState('')
  const [lastLine, setLastLine] = useState('')
  const [busy, setBusy] = useState(false)
  const close = () => { setOpen(null); setReason(''); setSerial('') }
  const start = (k: 'reprint' | 'new' | 'position') => {
    const p = d.position
    setFromSeq(p ? String(Math.max(1, p.lastSeq - 2)) : ''); setToSeq(p ? String(p.lastSeq) : ''); setFirstLine('1')
    setLastLine(p ? String(p.lastLine) : '0'); setOpen(k)
  }
  const n = (s: string) => (s.trim() === '' ? NaN : Number(s))
  const run = async () => {
    setBusy(true)
    try {
      if (open === 'reprint') {
        const blob = await reprintPassbook(clientId, a.typeCode, a.refNo, { fromSeq: n(fromSeq), toSeq: n(toSeq), firstLine: n(firstLine), reason: reason.trim() })
        await printPdf(blob, `passbook-reprint-${clientId}-${a.refNo}.pdf`)
      } else {
        const r = await setPassbookPosition(clientId, a.typeCode, a.refNo, open === 'new'
          ? { serial: serial.trim(), newPassbook: true, reason: reason.trim() }
          : { newPassbook: false, lastLine: n(lastLine), lastSeq: n(toSeq), reason: reason.trim() })
        qc.setQueryData(['passbook', clientId, a.typeCode, a.refNo], r)
        void qc.invalidateQueries({ queryKey: ['passbook-accounts', clientId] })
      }
      void qc.invalidateQueries({ queryKey: ['passbook', clientId, a.typeCode, a.refNo] })
      close()
    } catch (e) {
      notifyError((e as Error).message)
    } finally {
      setBusy(false)
    }
  }
  const title = open === 'reprint' ? 'Reprint printed entries' : open === 'new' ? 'New passbook' : 'Correct the passbook position'
  return (
    <>
      <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap', rowGap: 1 }}>
        {d.position && <Button startIcon={<FiRotateCcw />} onClick={() => start('reprint')}>Reprint</Button>}
        {d.mayCorrect && <Button startIcon={<FiBookOpen />} onClick={() => start('new')}>New passbook</Button>}
        {d.mayCorrect && d.position && <Button onClick={() => start('position')}>Correct position</Button>}
      </Stack>
      <Dialog open={open != null} onClose={close} maxWidth="xs" fullWidth>
        <DialogTitle>{title}</DialogTitle>
        <DialogContent>
          <Stack spacing={1.5} sx={{ pt: 1 }}>
            {open === 'reprint' && <Typography color="text.secondary">Prints entries already in the passbook again, from the line you choose. The passbook does not move on.</Typography>}
            {open === 'new' && <Typography color="text.secondary">For a full or lost passbook. Printing starts at line 1 of the new one with the balance carried forward, then the entries not yet printed.</Typography>}
            {open === 'position' && <Typography color="text.secondary">Set where the paper really stands: the last line printed on the page in use and the last entry printed (entry numbers are the account's ledger sequence).</Typography>}
            {open === 'new' && <TextField label="New passbook number" value={serial} onChange={(e) => setSerial(e.target.value)} autoFocus slotProps={{ htmlInput: { maxLength: 20 } }} />}
            {open === 'reprint' && (
              <Stack direction="row" spacing={1}>
                <TextField label="From entry" value={fromSeq} onChange={(e) => setFromSeq(e.target.value)} />
                <TextField label="To entry" value={toSeq} onChange={(e) => setToSeq(e.target.value)} />
                <TextField label="On line" value={firstLine} onChange={(e) => setFirstLine(e.target.value)} />
              </Stack>
            )}
            {open === 'position' && (
              <Stack direction="row" spacing={1}>
                <TextField label="Last line printed" value={lastLine} onChange={(e) => setLastLine(e.target.value)} helperText={`0 to ${d.linesPerPage}`} />
                <TextField label="Last entry printed" value={toSeq} onChange={(e) => setToSeq(e.target.value)} />
              </Stack>
            )}
            <TextField label="Reason" value={reason} onChange={(e) => setReason(e.target.value)} helperText="At least 5 letters. Kept in the passbook's history and the audit log." />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={close}>Back</Button>
          <Button variant="contained" disabled={busy || reason.trim().length < 5 || (open === 'new' && !serial.trim())} onClick={() => void run()}>
            {open === 'reprint' ? 'Print' : 'Save'}
          </Button>
        </DialogActions>
      </Dialog>
    </>
  )
}

const LAYOUT_FIELDS: [keyof PassbookLayout, string][] = [
  ['pageWidth', 'Page width (in)'], ['pageHeight', 'Page height (in)'], ['linesPerPage', 'Lines per page'], ['firstLine', 'Line 1 from the top (in)'],
  ['lineHeight', 'Line height (in)'], ['foldAfter', 'Fold after line'], ['foldGap', 'Fold gap (in)'], ['fontSize', 'Type size (pt)'],
  ['dateX', 'Date: left edge'], ['referenceX', 'Reference: left edge'], ['depositRight', 'Deposit: right edge'], ['withdrawalRight', 'Withdrawal: right edge'],
  ['balanceRight', 'Balance: right edge'], ['tcX', 'TC: left edge'], ['tellerX', 'Teller No.: left edge'], ['shiftX', 'Move everything right (in)'], ['shiftY', 'Move everything down (in)'],
]

/** Where the lines go on the passbook, with a test page to line it up on the real printer. Administrators save it; anyone may print the test page. */
function LayoutDialog({ onClose, admin }: { onClose: () => void; admin: boolean }) {
  const q = useQuery({ queryKey: ['passbook-layout'], queryFn: getPassbookLayout })
  const qc = useQueryClient()
  const [edit, setEdit] = useState<Record<string, string> | null>(null)
  const [reason, setReason] = useState('')
  const values = edit ?? (q.data ? Object.fromEntries(LAYOUT_FIELDS.map(([k]) => [k, String(q.data![k])])) : null)
  const layout = values ? Object.fromEntries(LAYOUT_FIELDS.map(([k]) => [k, Number(values[k])])) as unknown as PassbookLayout : null
  const save = useMutation({
    meta: { done: 'Passbook layout saved.' },
    mutationFn: () => savePassbookLayout(layout!, reason.trim()),
    onSuccess: (r) => { qc.setQueryData(['passbook-layout'], r); setEdit(null); setReason(''); onClose() },
    onError: (e) => notifyError((e as Error).message),
  })
  const test = async () => {
    try { await printPdf(await getPassbookTestPage(layout ?? undefined), 'passbook-test-page.pdf') } catch (e) { notifyError((e as Error).message) }
  }
  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>Passbook layout</DialogTitle>
      <DialogContent>
        <LoadingBlock q={q} what="the layout" />
        {values && (
          <>
            <AboutNote sx={{ mb: 1.5 }}>
              In inches from the top left corner of the open passbook. Print the test page on a spare passbook: every line and column edge is marked. Move the numbers until the marks sit on the printed lines.
            </AboutNote>
            <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr 1fr', sm: '1fr 1fr 1fr' }, gap: 1.25 }}>
              {LAYOUT_FIELDS.map(([k, label]) => (
                <TextField key={k} label={label} value={values[k]} disabled={!admin} inputMode="decimal"
                  onChange={(e) => setEdit({ ...values, [k]: e.target.value })} />
              ))}
            </Box>
            {admin && edit && <TextField label="Reason for the change" value={reason} onChange={(e) => setReason(e.target.value)} fullWidth sx={{ mt: 1.5 }} />}
            {!admin && <Typography variant="body2" sx={{ mt: 1 }} color="text.secondary">Only an administrator can change the layout.</Typography>}
          </>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Close</Button>
        <Button startIcon={<FiPrinter />} disabled={!layout} onClick={() => void test()}>Print test page</Button>
        {admin && <Button variant="contained" disabled={!edit || reason.trim().length < 5 || save.isPending} onClick={() => save.mutate()}>Save layout</Button>}
      </DialogActions>
    </Dialog>
  )
}

/**
 * Front Office > Passbook: print a member's savings passbook. The old system's record of where each passbook stopped is kept, so printing carries on
 * from the next line. A page is printed, the teller says whether it came out right, and only then does the passbook move on.
 */
export default function PassbookPanel() {
  const me = useMe()
  const lk = useQuery({ queryKey: ['lookups'], queryFn: getLookups, staleTime: 3_600_000 })
  const [id, setId] = useState<Id | null>(null)
  const [acct, setAcct] = useState<Acct | null>(null)
  const [layoutOpen, setLayoutOpen] = useState(false)
  const accounts = useQuery({ queryKey: ['passbook-accounts', id?.clientId], queryFn: () => getPassbookAccounts(id!.clientId), enabled: !!id })
  const chosen = acct ?? (accounts.data?.length === 1 ? accounts.data[0] : null)
  const detail = useQuery({
    queryKey: ['passbook', id?.clientId, chosen?.typeCode, chosen?.refNo],
    queryFn: () => getPassbook(id!.clientId, chosen!.typeCode, chosen!.refNo), enabled: !!id && !!chosen,
  })
  const d = detail.data
  return (
    <Box sx={{ flex: 1, minHeight: 0, overflow: 'auto', display: 'flex', flexDirection: 'column', gap: 1.25 }}>
      <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
        <Box sx={{ flex: 1 }}><MemberSearchBox lk={lk.data} onPick={(x) => { setId(x); setAcct(null) }} width={{ xs: '100%', md: '60%', lg: '50%' }} /></Box>
        <Button startIcon={<FiSettings />} onClick={() => setLayoutOpen(true)}>Layout</Button>
      </Stack>
      {!id && <Typography sx={{ p: 4, textAlign: 'center' }} color="text.secondary">Find the member whose passbook to print.</Typography>}
      {id && (
        <Box sx={{ display: 'grid', gap: 1.25, gridTemplateColumns: { xs: '1fr', md: '280px minmax(0, 1fr)' }, alignItems: 'start' }}>
          <Box sx={(t) => ({ ...glass(t), p: 1 })} role="list" aria-label="Savings accounts">
            <LoadingBlock q={accounts} what="the savings accounts" />
            {accounts.data?.length === 0 && <Typography sx={{ p: 1.5 }} color="text.secondary">This member has no savings account.</Typography>}
            {accounts.data?.map((x) => {
              const on = chosen?.typeCode === x.typeCode && chosen?.refNo === x.refNo
              return (
                <ButtonBase key={`${x.typeCode}-${x.refNo}`} role="listitem" onClick={() => setAcct(x)}
                  sx={{ display: 'block', width: '100%', textAlign: 'left', p: 1.25, borderRadius: `${SURFACE_RADIUS - 2}px`, bgcolor: on ? 'action.selected' : undefined, '&:hover': { bgcolor: 'action.hover' } }}>
                  <Typography variant="subtitle2" component="p">{x.typeName ?? `Type ${x.typeCode}`} {x.refNo}</Typography>
                  <Typography variant="body2" color="text.secondary">Balance {money(x.balance)}</Typography>
                  <Stack direction="row" spacing={0.75} sx={{ mt: 0.5 }}>
                    <Chip size="small" variant="outlined" label={x.serial ? `Passbook ${x.serial}` : 'No passbook'} />
                    {x.waiting > 0 && <Chip size="small" color="warning" label={`${x.waiting} to print`} />}
                  </Stack>
                </ButtonBase>
              )
            })}
          </Box>
          <Box sx={(t) => ({ ...glass(t), p: 2, minWidth: 0 })}>
            {!chosen && <Typography color="text.secondary">Choose a savings account.</Typography>}
            {chosen && <LoadingBlock q={detail} what="the passbook" />}
            {d && chosen && (
              <Stack spacing={1.5}>
                <Box>
                  <Typography variant="h5" component="p">{d.clientName} · {d.typeName} {d.refNo}</Typography>
                  <Typography color="text.secondary">
                    Balance {money(d.balance)}
                    {d.position && <> · passbook {d.position.serial ?? '—'} · last printed line {d.position.lastLine} of {d.linesPerPage}{d.position.lastDate ? `, ${d.position.lastDate}` : ''}, balance {d.position.balance}</>}
                  </Typography>
                </Box>
                {d.warnings.map((w) => <Alert key={w} severity="warning">{w}</Alert>)}
                {d.position && d.pages.length === 0 && <Alert severity="success">The passbook is up to date. Nothing is waiting to be printed.</Alert>}
                {d.position && d.pages.length > 0 && <NextPage key={`${d.position.lastSeq}-${d.position.lastLine}`} d={d} a={chosen} clientId={id.clientId} />}
                <Corrections key={`${chosen.typeCode}-${chosen.refNo}`} d={d} a={chosen} clientId={id.clientId} />
                {d.history.length > 0 && (
                  <Box>
                    <Typography variant="body2" sx={{ fontWeight: 600, letterSpacing: '0.05em', textTransform: 'uppercase', mb: 0.5 }} color="text.secondary">History</Typography>
                    <Table size="small" aria-label="Passbook history">
                      <TableBody>
                        {d.history.map((h, i) => (
                          <TableRow key={i}>
                            <TableCell sx={cell}>{h.at}</TableCell>
                            <TableCell>{ACTION_WORDS[h.action] ?? h.action}{h.firstLine != null && h.lastLine != null ? `, lines ${h.firstLine}–${h.lastLine}` : h.lastLine != null ? `, line ${h.lastLine}` : ''}{h.serial ? ` (passbook ${h.serial})` : ''}</TableCell>
                            <TableCell>{h.username}</TableCell>
                            <TableCell sx={{ color: 'text.secondary' }}>{h.reason}</TableCell>
                          </TableRow>
                        ))}
                      </TableBody>
                    </Table>
                  </Box>
                )}
              </Stack>
            )}
          </Box>
        </Box>
      )}
      {layoutOpen && <LayoutDialog admin={me.admin} onClose={() => setLayoutOpen(false)} />}
    </Box>
  )
}
