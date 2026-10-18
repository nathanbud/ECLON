import {
  Alert, Box, Button, Chip, Dialog, GlobalStyles, DialogActions, DialogContent, DialogContentText, DialogTitle, MenuItem, Skeleton, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography,
} from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useMemo, useRef, useState } from 'react'
import {
  acceptFoHandover, cancelFoHandover, getFoHandoverDrawers, getFoHandoverMine, getFoHandoverSlip, getFoLimits, issueFoHandover, refreshFoBalances, setFoLimit,
  type FoDrawer, type FoHandover, type FoSetup,
} from './api'
import { money } from './FoTransactionForm'
import { CountGrid, countTotal, toNumbers, type Counts } from './CountGrid'
import { glass } from './glass'

const VAULT = 50
const label = (t: string) => (
  <Typography sx={{ fontSize: 12, fontWeight: 700, letterSpacing: '0.06em', textTransform: 'uppercase', mb: 0.75 }} color="text.secondary">{t}</Typography>
)
const statusColor = (s: FoHandover['status']) => (s === 'POSTED' ? 'success' : s === 'PENDING' ? 'warning' : s === 'DISPUTED' ? 'error' : 'default')


/** A hand-off slip that prints on its own: while printing, everything except the slip is hidden. */
function SlipDialog({ id, onClose }: { id: number | null; onClose: () => void }) {
  const slip = useQuery({ queryKey: ['fo-handover-slip', id], queryFn: () => getFoHandoverSlip(id ?? 0), enabled: id !== null })
  const s = slip.data
  const row = (k: string, v: string) => (
    <Box sx={{ display: 'flex', justifyContent: 'space-between', gap: 2, py: 0.25 }}><span>{k}</span><b style={{ textAlign: 'right' }}>{v}</b></Box>
  )
  return (
    <Dialog open={id !== null} onClose={onClose} maxWidth="xs" fullWidth>
      <GlobalStyles styles={{
        '@media print': {
          'body *': { visibility: 'hidden' },
          '#handover-slip, #handover-slip *': { visibility: 'visible' },
          '#handover-slip': { position: 'absolute', left: 0, top: 0, width: '80mm', padding: '4mm', color: '#000', background: '#fff' },
          '.MuiDialog-container, .MuiPaper-root': { boxShadow: 'none', background: '#fff' },
        },
      }} />
      <DialogContent>
        {slip.isLoading && <Skeleton variant="rounded" height={220} />}
        {slip.error && <Alert severity="error">{(slip.error as Error).message}</Alert>}
        {s && (
          <Box id="handover-slip" sx={{ fontSize: 13, fontFamily: 'ui-monospace, Consolas, monospace' }}>
            <Typography sx={{ fontWeight: 700, textAlign: 'center', fontFamily: 'inherit' }}>CASH HAND-OFF</Typography>
            <Typography sx={{ textAlign: 'center', fontSize: 12, fontFamily: 'inherit' }}>{s.type}</Typography>
            <Box sx={{ borderTop: '1px dashed', my: 1 }} />
            {row('Date', `${s.date} ${s.time}`)}
            {row('Document no.', s.docNo ?? 'not posted')}
            {row('Status', s.status === 'POSTED' ? 'Posted' : s.status === 'PENDING' ? 'Waiting to be counted' : s.status === 'DISPUTED' ? 'Count differs' : 'Cancelled')}
            <Box sx={{ borderTop: '1px dashed', my: 1 }} />
            {row('From', s.from)}
            {s.fromWho && row('', s.fromWho)}
            {row('To', s.to)}
            {s.toWho && row('', s.toWho)}
            <Box sx={{ borderTop: '1px dashed', my: 1 }} />
            {s.lines.map((l) => row(`${l.description} x ${l.qty}`, money(l.amount)))}
            <Box sx={{ borderTop: '1px solid', my: 0.5 }} />
            {row('TOTAL', money(s.amount))}
            {s.status === 'DISPUTED' && s.counted !== null && row('Counted', money(s.counted))}
            {s.remarks && row('Remarks', s.remarks)}
            <Box sx={{ borderTop: '1px dashed', my: 1 }} />
            {row('Given by', s.issuedBy)}
            {row('Received by', s.acceptedBy ?? (s.to === 'Vault 99' ? 'Vault' : '________________'))}
            <Box sx={{ height: 28 }} />
            <Box sx={{ display: 'flex', justifyContent: 'space-between', fontSize: 11 }}><span>Giver signature</span><span>Receiver signature</span></Box>
          </Box>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Close</Button>
        <Button variant="contained" disabled={!s} onClick={() => window.print()}>Print</Button>
      </DialogActions>
    </Dialog>
  )
}

/**
 * Cash handover: the head cashier takes cash from the vault, gives it to tellers, takes it back at the end of the day and
 * returns the rest to the vault. A hand-off between two people is pending until the receiver counts it; when the count
 * agrees it is posted to the ledger. Moves to or from the vault post at once.
 */
export default function CashHandoverPanel({ setup, onNotice }: { setup: FoSetup; onNotice: (m: string) => void }) {
  const qc = useQueryClient()
  const mine = useQuery({ queryKey: ['fo-handover-mine'], queryFn: getFoHandoverMine, refetchInterval: 30_000 })
  const ready = mine.data?.ready ?? false
  const drawers = useQuery({ queryKey: ['fo-handover-drawers'], queryFn: getFoHandoverDrawers, enabled: mine.isSuccess, refetchInterval: 60_000 })

  const [fromEvent, setFromEvent] = useState<number | null>(null)
  const [to, setTo] = useState('')
  const [counts, setCounts] = useState<Counts>({})
  const [remarks, setRemarks] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [sure, setSure] = useState(false)
  const [accepting, setAccepting] = useState<FoHandover | null>(null)
  const [accCounts, setAccCounts] = useState<Counts>({})
  const [slipId, setSlipId] = useState<number | null>(null)
  // One key per attempt: sent with the request so a repeated click or a retry cannot post the same hand-off twice.
  const keyRef = useRef<string>(crypto.randomUUID())
  const [needReason, setNeedReason] = useState<string | null>(null)
  const [reason, setReason] = useState('')
  const [limitFor, setLimitFor] = useState<{ event: number; number: number; label: string; value: string } | null>(null)
  const limits = useQuery({ queryKey: ['fo-limits'], queryFn: getFoLimits, enabled: mine.data?.headCashier === true })

  const head = mine.data?.headCashier ?? false
  const myDrawers = useMemo(() => mine.data?.drawers ?? [], [mine.data])
  const from = fromEvent ?? myDrawers[0]?.event ?? null
  const fromOptions: { event: number; text: string }[] = [
    ...myDrawers.map((d) => ({ event: d.event, text: d.label })),
    ...(head ? [{ event: VAULT, text: 'Vault' }] : []),
  ]
  const myCashier = myDrawers.find((d) => d.event === 52)
  const toOptions: FoDrawer[] = useMemo(() => {
    const all = drawers.data ?? []
    if (from === VAULT) return myCashier ? all.filter((d) => d.event === 52 && d.number === myCashier.number) : []
    const own = myDrawers.find((d) => d.event === from)
    return all.filter((d) => !(own && d.event === own.event && d.number === own.number) && (head || d.event !== VAULT))
  }, [drawers.data, from, head, myCashier, myDrawers])
  const toDrawer = toOptions.find((d) => `${d.event}:${d.number}` === to) ?? (toOptions.length === 1 ? toOptions[0] : undefined)

  const amount = countTotal(setup, counts)
  const own = myDrawers.find((d) => d.event === from)
  const free = own ? (own.balance ?? 0) - (own.pendingOut ?? 0) : null
  const toVault = toDrawer?.event === VAULT
  const atOnce = from === VAULT || toVault

  const refresh = () => { void qc.invalidateQueries({ queryKey: ['fo-handover-mine'] }); void qc.invalidateQueries({ queryKey: ['fo-handover-drawers'] }); void qc.invalidateQueries({ queryKey: ['fo-totals'] }); void qc.invalidateQueries({ queryKey: ['fo-summary'] }) }
  const fail = (e: unknown) => setError((e as Error).message)

  const give = useMutation({
    mutationFn: (overrideReason?: string) => issueFoHandover({
      fromEvent: from ?? 0, toEvent: toDrawer?.event ?? 0, toNumber: toDrawer?.number ?? null, amount, counts: toNumbers(counts), remarks,
      requestKey: keyRef.current, overrideReason,
    }),
    onSuccess: (h) => {
      setError(null); setCounts({}); setRemarks(''); setNeedReason(null); setReason(''); keyRef.current = crypto.randomUUID(); refresh()
      onNotice(h.status === 'POSTED' ? `Posted. Control no. ${h.ctlNo}.` : `Sent to ${h.to}. It is posted when they count it.`)
    },
    // Returning cash to the vault before the day is ready asks the head cashier for a reason instead of just failing.
    onError: (e) => { const m = (e as Error).message; if (m.startsWith('Not ready to close:')) setNeedReason(m); else fail(e) },
  })
  const accept = useMutation({
    mutationFn: (h: FoHandover) => acceptFoHandover(h.id, toNumbers(accCounts)),
    onSuccess: (h) => {
      setError(null); setAccepting(null); setAccCounts({}); refresh()
      onNotice(h.status === 'POSTED' ? `Accepted and posted. Control no. ${h.ctlNo}.` : 'The count did not match, so nothing was posted. The giver has been told.')
    },
    onError: fail,
  })
  const cancel = useMutation({ mutationFn: (h: FoHandover) => cancelFoHandover(h.id), onSuccess: () => { setError(null); refresh(); onNotice('Cancelled. Nothing was posted.') }, onError: fail })

  if (mine.isLoading) return <Skeleton variant="rounded" sx={{ flex: 1 }} />
  if (mine.error) return <Alert severity="error">{(mine.error as Error).message}</Alert>

  const canGive = ready && (!!own || from === VAULT)
  const submit = () => { if (atOnce) setSure(true); else give.mutate(undefined) }
  const saveLimit = async () => {
    if (!limitFor) return
    try {
      await setFoLimit({ event: limitFor.event, number: limitFor.number, maxCash: Number(limitFor.value.replace(/,/g, '')) || 0 })
      setLimitFor(null); void qc.invalidateQueries({ queryKey: ['fo-limits'] }); refresh()
    } catch (e) { fail(e) }
  }
  const rebuild = async () => {
    try { const r = await refreshFoBalances(); onNotice(`Balances rebuilt for ${r.drawers} drawers.`); refresh() } catch (e) { fail(e) }
  }
  const defaultOf = (event: number) => limits.data?.find((l) => l.event === event && l.number === 0)?.maxCash ?? 0
  const headDrawer = (drawers.data ?? []).find((d) => d.head)
  const problem = !toDrawer ? 'Choose who to give the cash to.' : amount <= 0 ? 'Count the notes and coins to give.' : free !== null && from !== VAULT && amount > free ? `You only have ${money(free)} free to give.` : null

  return (
    <Box sx={{ display: 'grid', gap: 1.5, gridTemplateColumns: 'minmax(380px, 560px) minmax(0, 1fr)', flex: 1, minHeight: 0 }}>
      <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5, minHeight: 0, overflow: 'auto', pr: 0.25 }}>
        {!ready && <Alert severity="warning">{mine.data?.notReady}</Alert>}
        {error && <Alert severity="error" onClose={() => setError(null)}>{error}</Alert>}
        {myDrawers.length === 0 && ready && !head && <Alert severity="info">Your account has no teller or cashier number, so it has no cash drawer.</Alert>}

        {/* My drawers */}
        {myDrawers.length > 0 && (
          <Box sx={(t) => ({ p: 1.5, ...glass(t) })}>
            {label('My drawer')}
            <Box sx={{ display: 'flex', gap: 3, flexWrap: 'wrap' }}>
              {myDrawers.map((d) => (
                <Box key={d.event}>
                  <Typography sx={{ fontSize: 13 }} color="text.secondary">{d.label}</Typography>
                  <Typography sx={{ fontSize: 22, fontWeight: 700, fontVariantNumeric: 'tabular-nums' }}>{money(d.balance ?? 0)}</Typography>
                  {((d.pendingIn ?? 0) > 0 || (d.pendingOut ?? 0) > 0) && (
                    <Typography sx={{ fontSize: 12 }} color="text.secondary">Waiting: {money(d.pendingIn ?? 0)} to receive, {money(d.pendingOut ?? 0)} given</Typography>
                  )}
                  {d.limit != null && <Typography sx={{ fontSize: 12 }} color="text.secondary">Limit {money(d.limit)}</Typography>}
                </Box>
              ))}
            </Box>
            {/* Over the drawer limit: best practice is to drop the excess to the head cashier before it builds up. */}
            {myDrawers.filter((d) => d.limit != null && (d.balance ?? 0) > (d.limit ?? 0) && !d.head).map((d) => (
              <Alert
                key={d.event} severity="warning" sx={{ mt: 1.25 }}
                action={headDrawer && (
                  <Button color="inherit" size="small" onClick={() => { setFromEvent(d.event); setTo(`${headDrawer.event}:${headDrawer.number}`) }}>Give excess</Button>
                )}
              >
                {d.label} is {money((d.balance ?? 0) - (d.limit ?? 0))} over its limit. Give the excess to the head cashier.
              </Alert>
            ))}
          </Box>
        )}

        {/* Give cash */}
        {canGive && (
          <Box sx={(t) => ({ p: 1.5, ...glass(t) })}>
            {label(from === VAULT ? 'Take cash from the vault' : 'Give cash')}
            <Box sx={{ display: 'flex', gap: 1, mb: 1.25, flexWrap: 'wrap' }}>
              <TextField select size="small" label="From" value={from ?? ''} onChange={(e) => { setFromEvent(Number(e.target.value)); setTo('') }} sx={{ minWidth: 170 }}>
                {fromOptions.map((o) => <MenuItem key={o.event} value={o.event}>{o.text}</MenuItem>)}
              </TextField>
              <TextField select size="small" label="To" value={toDrawer ? `${toDrawer.event}:${toDrawer.number}` : ''} onChange={(e) => setTo(e.target.value)} sx={{ minWidth: 260, flex: 1 }}>
                {toOptions.map((d) => <MenuItem key={`${d.event}:${d.number}`} value={`${d.event}:${d.number}`}>{d.event === VAULT ? 'Vault' : `${d.label} · ${d.who}`}</MenuItem>)}
              </TextField>
            </Box>
            <CountGrid setup={setup} counts={counts} onChange={setCounts} onSubmit={() => { if (!problem && !give.isPending) submit() }} />
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, mt: 1.25, flexWrap: 'wrap' }}>
              <Typography sx={{ fontSize: 13.5 }}>Total <b style={{ fontSize: 17, fontVariantNumeric: 'tabular-nums' }}>{money(amount)}</b></Typography>
              {free !== null && from !== VAULT && <Typography sx={{ fontSize: 12.5 }} color="text.secondary">free to give {money(free)}</Typography>}
              <Box sx={{ flex: 1 }} />
              <TextField size="small" label="Remarks" value={remarks} onChange={(e) => setRemarks(e.target.value)} sx={{ width: 200 }} slotProps={{ htmlInput: { maxLength: 150 } }} />
              <Button variant="contained" disabled={!!problem || give.isPending} onClick={submit}>{give.isPending ? 'Sending…' : atOnce ? 'Post' : 'Send'}</Button>
            </Box>
            {problem && amount > 0 && <Typography sx={{ fontSize: 12.5, mt: 0.5 }} color="error">{problem}</Typography>}
            <Typography sx={{ fontSize: 12, mt: 0.75 }} color="text.secondary">
              {atOnce ? 'Moves to or from the vault are posted to the ledger straight away.' : 'The receiver counts it first. It is posted to the ledger when their count agrees.'}
            </Typography>
          </Box>
        )}

        {/* Head cashier: every drawer */}
        {head && (
          <Box sx={(t) => ({ ...glass(t), overflow: 'auto' })}>
            <Box sx={{ p: 1.5, pb: 0.5, display: 'flex', alignItems: 'center', gap: 1, flexWrap: 'wrap' }}>
              {label('All drawers (end of day check)')}
              <Box sx={{ flex: 1 }} />
              <Button size="small" onClick={() => setLimitFor({ event: 53, number: 0, label: 'every teller drawer without its own limit', value: String(defaultOf(53) || '') })}>Teller limit</Button>
              <Button size="small" color="inherit" onClick={() => { void rebuild() }}>Rebuild balances</Button>
            </Box>
            <Table size="small" aria-label="All drawers">
              <TableHead>
                <TableRow>
                  <TableCell>Drawer</TableCell><TableCell>Who</TableCell><TableCell align="right">Cash</TableCell><TableCell align="right">Limit</TableCell>
                  <TableCell align="right">Waiting</TableCell><TableCell>Counted</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {(drawers.data ?? []).map((d) => {
                  const left = d.event !== VAULT && (d.balance ?? 0) !== 0
                  const over = d.limit != null && (d.balance ?? 0) > d.limit && !d.head
                  return (
                    <TableRow key={`${d.event}:${d.number}`}>
                      <TableCell>{d.label}</TableCell>
                      <TableCell sx={{ maxWidth: 150, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{d.who}</TableCell>
                      <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums', fontWeight: (left && d.event === 53) || over ? 700 : 400, color: over ? 'error.main' : left && d.event === 53 ? 'warning.main' : undefined }}>{money(d.balance ?? 0)}</TableCell>
                      <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>
                        {d.event !== VAULT && (
                          <Button size="small" color="inherit" sx={{ minWidth: 0, fontVariantNumeric: 'tabular-nums' }} aria-label={`Set the limit for ${d.label}`}
                            onClick={() => setLimitFor({ event: d.event, number: d.number, label: d.label, value: d.limit ? String(d.limit) : '' })}>
                            {d.limit != null ? money(d.limit) : 'Set'}
                          </Button>
                        )}
                      </TableCell>
                      <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>{(d.pendingIn ?? 0) + (d.pendingOut ?? 0) > 0 ? money((d.pendingIn ?? 0) + (d.pendingOut ?? 0)) : ''}</TableCell>
                      <TableCell sx={{ whiteSpace: 'nowrap', fontSize: 12.5 }}>
                        {d.closedAt == null ? (d.event === VAULT ? '' : '—')
                          : <span style={{ color: d.variance ? 'inherit' : undefined }}>{d.closedAt}{d.variance ? ` · ${d.variance < 0 ? 'short' : 'over'} ${money(Math.abs(d.variance))}` : ' ✓'}</span>}
                      </TableCell>
                    </TableRow>
                  )
                })}
              </TableBody>
            </Table>
            <Typography sx={{ p: 1.5, fontSize: 12 }} color="text.secondary">
              Before closing: every hand-off settled, every teller who worked has counted their drawer, and the cash handed back (amber means cash is still with that teller, red is over the limit). Then the head cashier returns the balance to the vault.
            </Typography>
          </Box>
        )}
      </Box>

      <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5, minHeight: 0, minWidth: 0, overflow: 'auto' }}>
        {/* Waiting for me */}
        <Box sx={(t) => ({ p: 1.5, ...glass(t) })}>
          {label('Waiting for you to count')}
          {(mine.data?.incoming ?? []).length === 0 && <Typography sx={{ fontSize: 13.5 }} color="text.secondary">Nothing to receive.</Typography>}
          {(mine.data?.incoming ?? []).map((h) => (
            <Box key={h.id} sx={{ display: 'flex', alignItems: 'center', gap: 1.5, py: 0.75, borderTop: '1px solid', borderColor: 'divider' }}>
              <Box sx={{ flex: 1, minWidth: 0 }}>
                <Typography sx={{ fontSize: 14 }}>{h.from} · <b style={{ fontVariantNumeric: 'tabular-nums' }}>{money(h.amount)}</b></Typography>
                <Typography sx={{ fontSize: 12 }} color="text.secondary">{h.issuedAt} by {h.issuedBy}{h.remarks ? ` · ${h.remarks}` : ''}</Typography>
              </Box>
              <Button size="small" variant="contained" onClick={() => { setAccepting(h); setAccCounts({}) }}>Count</Button>
              <Button size="small" color="inherit" disabled={cancel.isPending} onClick={() => cancel.mutate(h)}>Decline</Button>
            </Box>
          ))}
        </Box>

        {/* Given, not yet accepted */}
        {(mine.data?.outgoing ?? []).length > 0 && (
          <Box sx={(t) => ({ p: 1.5, ...glass(t) })}>
            {label('Given, not counted yet')}
            {(mine.data?.outgoing ?? []).map((h) => (
              <Box key={h.id} sx={{ display: 'flex', alignItems: 'center', gap: 1.5, py: 0.75, borderTop: '1px solid', borderColor: 'divider' }}>
                <Box sx={{ flex: 1, minWidth: 0 }}>
                  <Typography sx={{ fontSize: 14 }}>{h.to} · <b style={{ fontVariantNumeric: 'tabular-nums' }}>{money(h.amount)}</b></Typography>
                  <Typography sx={{ fontSize: 12 }} color={h.status === 'DISPUTED' ? 'error' : 'text.secondary'}>{h.status === 'DISPUTED' ? h.note : `Sent ${h.issuedAt}`}</Typography>
                </Box>
                <Chip size="small" color={statusColor(h.status)} variant="outlined" label={h.status === 'DISPUTED' ? 'Count differs' : 'Waiting'} />
                <Button size="small" color="inherit" disabled={cancel.isPending} onClick={() => cancel.mutate(h)}>Take back</Button>
              </Box>
            ))}
          </Box>
        )}

        {/* Today */}
        <Box sx={(t) => ({ ...glass(t), overflow: 'auto', flex: 1, minHeight: 120 })}>
          <Box sx={{ p: 1.5, pb: 0.5 }}>{label('Hand-offs today')}</Box>
          <Table size="small" aria-label="Hand-offs today">
            <TableHead><TableRow><TableCell>Time</TableCell><TableCell>From</TableCell><TableCell>To</TableCell><TableCell align="right">Amount</TableCell><TableCell>Status</TableCell><TableCell align="right">Slip</TableCell></TableRow></TableHead>
            <TableBody>
              {(mine.data?.today ?? []).map((h) => (
                <TableRow key={h.id}>
                  <TableCell sx={{ fontVariantNumeric: 'tabular-nums' }}>{h.issuedAt}</TableCell><TableCell>{h.from}</TableCell><TableCell>{h.to}</TableCell>
                  <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>{money(h.amount)}</TableCell>
                  <TableCell><Chip size="small" variant="outlined" color={statusColor(h.status)} label={h.status === 'POSTED' ? `Posted ${h.ctlNo ?? ''}` : h.status.charAt(0) + h.status.slice(1).toLowerCase()} /></TableCell>
                  <TableCell align="right"><Button size="small" aria-label={`Slip for ${h.from} to ${h.to}`} onClick={() => setSlipId(h.id)}>Slip</Button></TableCell>
                </TableRow>
              ))}
              {(mine.data?.today ?? []).length === 0 && <TableRow><TableCell colSpan={6}><Typography sx={{ p: 2, textAlign: 'center' }} color="text.secondary">No hand-offs yet today.</Typography></TableCell></TableRow>}
            </TableBody>
          </Table>
        </Box>
      </Box>

      <SlipDialog id={slipId} onClose={() => setSlipId(null)} />

      {/* The most cash a drawer should hold */}
      <Dialog open={!!limitFor} onClose={() => setLimitFor(null)}>
        <DialogTitle>Cash limit</DialogTitle>
        <DialogContent>
          <DialogContentText sx={{ mb: 1.5 }}>The most cash for {limitFor?.label}. Tellers over it are asked to give the excess to the head cashier. Leave empty or 0 for no limit.</DialogContentText>
          <TextField
            autoFocus fullWidth size="small" label="Limit" value={limitFor?.value ?? ''} slotProps={{ htmlInput: { inputMode: 'decimal' } }}
            onChange={(e) => setLimitFor((l) => (l ? { ...l, value: e.target.value.replace(/[^0-9.,]/g, '') } : l))}
            onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); void saveLimit() } }}
          />
        </DialogContent>
        <DialogActions><Button onClick={() => setLimitFor(null)}>Cancel</Button><Button variant="contained" onClick={() => { void saveLimit() }}>Save</Button></DialogActions>
      </Dialog>

      {/* Returning cash to the vault before the day is ready needs a reason (written to the audit log) */}
      <Dialog open={!!needReason} onClose={() => setNeedReason(null)} fullWidth maxWidth="xs">
        <DialogTitle>Not ready to close the day</DialogTitle>
        <DialogContent>
          <DialogContentText sx={{ mb: 1.5 }}>{needReason?.replace('Not ready to close:', '').trim()}</DialogContentText>
          <TextField autoFocus fullWidth size="small" label="Reason to go ahead anyway" value={reason} onChange={(e) => setReason(e.target.value)} slotProps={{ htmlInput: { maxLength: 200 } }} />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setNeedReason(null)} autoFocus>Go back</Button>
          <Button variant="contained" color="warning" disabled={reason.trim().length < 5 || give.isPending} onClick={() => give.mutate(reason.trim())}>Post anyway</Button>
        </DialogActions>
      </Dialog>

      {/* Count what was received */}
      <Dialog open={!!accepting} onClose={() => setAccepting(null)} maxWidth="sm" fullWidth>
        <DialogTitle>Count the cash from {accepting?.from}</DialogTitle>
        <DialogContent>
          <DialogContentText sx={{ mb: 1.5 }}>Given: <b>{money(accepting?.amount ?? 0)}</b>. Count the notes and coins you received.</DialogContentText>
          <CountGrid setup={setup} counts={accCounts} onChange={setAccCounts} />
          {(() => {
            const c = countTotal(setup, accCounts)
            const diff = Math.round((c - (accepting?.amount ?? 0)) * 100) / 100
            return (
              <Typography sx={{ mt: 1.5, fontWeight: 700, color: c === 0 ? 'text.disabled' : diff === 0 ? 'success.main' : 'error.main' }} role="status">
                Counted {money(c)} {c === 0 ? '' : diff === 0 ? '· matches ✓' : `· ${diff < 0 ? 'short' : 'over'} ${money(Math.abs(diff))}`}
              </Typography>
            )
          })()}
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setAccepting(null)}>Not now</Button>
          <Button variant="contained" disabled={accept.isPending || countTotal(setup, accCounts) === 0} onClick={() => accepting && accept.mutate(accepting)}>
            {accept.isPending ? 'Posting…' : 'Accept and post'}
          </Button>
        </DialogActions>
      </Dialog>

      {/* Vault moves post at once */}
      <Dialog open={sure} onClose={() => setSure(false)}>
        <DialogTitle>Post to the ledger?</DialogTitle>
        <DialogContent>
          <DialogContentText>
            {from === VAULT ? 'Take' : 'Return'} <b>{money(amount)}</b> {from === VAULT ? 'from the vault to' : 'to'} {toDrawer ? (toDrawer.event === VAULT ? 'the vault' : toDrawer.label) : ''}. This is posted straight away and cannot be edited afterwards.
          </DialogContentText>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setSure(false)} autoFocus>Cancel</Button>
          <Button variant="contained" onClick={() => { setSure(false); give.mutate(undefined) }}>Post</Button>
        </DialogActions>
      </Dialog>
    </Box>
  )
}
