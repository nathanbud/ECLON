import { Alert, Box, Button, Checkbox, Chip, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel, MenuItem, Stack, Tab, Table, TableBody, TableCell, TableHead, TableRow, Tabs, TextField, Typography } from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { FiPlus } from 'react-icons/fi'
import {
  addAssetMaintenance, askAssetDisposal, askAssetTransfer, decideAssetDisposal, decideAssetTransfer, getAsset, listAssetCategories, listAssetDisposals, listAssetMaintenanceDue, listAssetTransfers, listAssets,
  listDepreciationRuns, previewDepreciation, registerAsset, runDepreciation, saveAssetCategory,
  type AssetCategory, type AssetDisposal, type AssetSummary, type AssetTransfer,
} from './api'
import { appBackground, glass } from './glass'
import { ReasonDialog } from './ProcurementPage'
import { useMe } from './session'

const money = (n: number) => n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const num = (s: string) => Number(s.replace(/,/g, ''))
const today = () => new Date().toLocaleDateString('en-CA')
const lastMonthEnd = () => { const d = new Date(); return new Date(d.getFullYear(), d.getMonth(), 0).toLocaleDateString('en-CA') }
const statusColor = (s: string) => (s === 'APPROVED' || s === 'ACTIVE' ? 'success' : s === 'PENDING' ? 'warning' : 'default') as 'success' | 'warning' | 'default'
const label = { fontSize: 12, fontWeight: 700, letterSpacing: '0.06em', textTransform: 'uppercase' } as const

function RegisterDialog({ onClose }: { onClose: () => void }) {
  const qc = useQueryClient()
  const cats = useQuery({ queryKey: ['asset-categories'], queryFn: listAssetCategories })
  const [f, setF] = useState({ tag: '', name: '', categoryId: '', serial: '', location: '', acquired: today(), inService: '', cost: '', salvage: '', life: '', reference: '' })
  const [error, setError] = useState<string | null>(null)
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement>) => setF({ ...f, [k]: e.target.value })
  const cat = (cats.data ?? []).find((c) => String(c.id) === f.categoryId)
  const save = useMutation({
    mutationFn: () => registerAsset({
      tag: f.tag, name: f.name, categoryId: Number(f.categoryId), serialNo: f.serial, location: f.location, acquisitionDate: f.acquired, inServiceDate: f.inService || null, cost: num(f.cost || '0'),
      salvageValue: f.salvage ? num(f.salvage) : null, lifeMonths: f.life ? Number(f.life) : null, reference: f.reference,
    }),
    onSuccess: () => { void qc.invalidateQueries({ queryKey: ['assets'] }); onClose() },
    onError: (e) => setError((e as Error).message),
  })
  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>Register an asset</DialogTitle>
      <DialogContent>
        <Stack spacing={1.5} sx={{ pt: 1 }}>
          {error && <Alert severity="error">{error}</Alert>}
          <Stack direction="row" spacing={1.5}>
            <TextField size="small" label="Tag (sticker no.)" value={f.tag} onChange={set('tag')} sx={{ width: 170 }} />
            <TextField size="small" label="Name" value={f.name} onChange={set('name')} sx={{ flex: 1 }} />
          </Stack>
          <Stack direction="row" spacing={1.5}>
            <TextField select size="small" label="Category" value={f.categoryId} onChange={set('categoryId')} sx={{ flex: 1 }}>
              {(cats.data ?? []).filter((c) => c.active).map((c) => <MenuItem key={c.id} value={String(c.id)}>{c.name}</MenuItem>)}
            </TextField>
            <TextField size="small" label="Serial no." value={f.serial} onChange={set('serial')} sx={{ width: 170 }} />
          </Stack>
          <TextField size="small" label="Where is it?" value={f.location} onChange={set('location')} />
          <Stack direction="row" spacing={1.5}>
            <TextField size="small" type="date" label="Acquired" value={f.acquired} onChange={set('acquired')} slotProps={{ inputLabel: { shrink: true } }} sx={{ flex: 1 }} />
            <TextField size="small" type="date" label="In service (blank = acquired)" value={f.inService} onChange={set('inService')} slotProps={{ inputLabel: { shrink: true } }} sx={{ flex: 1 }} />
          </Stack>
          <Stack direction="row" spacing={1.5}>
            <TextField size="small" label="Cost" value={f.cost} onChange={set('cost')} sx={{ flex: 1 }} />
            <TextField size="small" label={`Salvage value${cat ? ` (default ${cat.salvagePercent}%)` : ''}`} value={f.salvage} onChange={set('salvage')} sx={{ flex: 1 }} />
            <TextField size="small" label={`Life, months${cat ? ` (default ${cat.lifeMonths})` : ''}`} value={f.life} onChange={set('life')} sx={{ flex: 1 }} />
          </Stack>
          <TextField size="small" label="Reference (purchase order, invoice or deed)" value={f.reference} onChange={set('reference')} />
          <Typography sx={{ fontSize: 12.5 }} color="text.secondary">Depreciation starts the month after it goes into service. Cost, dates and life cannot be changed afterwards.</Typography>
        </Stack>
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" disabled={save.isPending || !f.categoryId || !f.tag || !f.name || !f.cost} onClick={() => save.mutate()}>Register</Button></DialogActions>
    </Dialog>
  )
}

function DisposeDialog({ asset, onClose }: { asset: AssetSummary; onClose: () => void }) {
  const qc = useQueryClient()
  const [f, setF] = useState({ date: today(), kind: 'SALE', proceeds: '0', proceedsAccount: '', gainAccount: '', reason: '' })
  const [error, setError] = useState<string | null>(null)
  const save = useMutation({
    mutationFn: () => askAssetDisposal(asset.id, { disposalDate: f.date, kind: f.kind, proceeds: num(f.proceeds || '0'), proceedsAccount: f.proceedsAccount || undefined, gainAccount: f.gainAccount || undefined, reason: f.reason }),
    onSuccess: () => { void qc.invalidateQueries({ queryKey: ['asset-disposals'] }); onClose() },
    onError: (e) => setError((e as Error).message),
  })
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement>) => setF({ ...f, [k]: e.target.value })
  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>Dispose of {asset.assetNo} · {asset.name}</DialogTitle>
      <DialogContent>
        <Stack spacing={1.5} sx={{ pt: 1 }}>
          {error && <Alert severity="error">{error}</Alert>}
          <Typography sx={{ fontSize: 14 }}>Net book value now <b>{money(asset.netBookValue)}</b>. Depreciation must be up to date to the month before the disposal date.</Typography>
          <Stack direction="row" spacing={1.5}>
            <TextField size="small" type="date" label="Disposal date" value={f.date} onChange={set('date')} slotProps={{ inputLabel: { shrink: true } }} sx={{ flex: 1 }} />
            <TextField select size="small" label="Kind" value={f.kind} onChange={set('kind')} sx={{ flex: 1 }}>
              <MenuItem value="SALE">Sale</MenuItem><MenuItem value="SCRAP">Scrapped</MenuItem><MenuItem value="DONATION">Donated</MenuItem><MenuItem value="LOSS">Lost or stolen</MenuItem>
            </TextField>
          </Stack>
          {f.kind === 'SALE' && (
            <Stack direction="row" spacing={1.5}>
              <TextField size="small" label="Proceeds" value={f.proceeds} onChange={set('proceeds')} sx={{ flex: 1 }} />
              <TextField size="small" label="Account that takes the proceeds" value={f.proceedsAccount} onChange={set('proceedsAccount')} sx={{ flex: 1 }} />
              <TextField size="small" label="Gain account (if a gain)" value={f.gainAccount} onChange={set('gainAccount')} sx={{ flex: 1 }} />
            </Stack>
          )}
          <TextField size="small" label="Reason" value={f.reason} onChange={set('reason')} />
          <Typography sx={{ fontSize: 12.5 }} color="text.secondary">A different accountant approves it; that leaves a draft journal (a loss goes to 6004108) for the usual review and posting.</Typography>
        </Stack>
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" disabled={save.isPending || !f.reason.trim()} onClick={() => save.mutate()}>Ask for approval</Button></DialogActions>
    </Dialog>
  )
}

function DetailDialog({ id, custodian, onClose }: { id: number; custodian: boolean; onClose: () => void }) {
  const qc = useQueryClient()
  const d = useQuery({ queryKey: ['asset', id], queryFn: () => getAsset(id) })
  const [moving, setMoving] = useState(false)
  const [work, setWork] = useState(false)
  const [disposing, setDisposing] = useState(false)
  const [to, setTo] = useState('')
  const [why, setWhy] = useState('')
  const [m, setM] = useState({ description: '', cost: '', vendor: '', nextDue: '' })
  const [error, setError] = useState<string | null>(null)
  const refresh = () => { void qc.invalidateQueries({ queryKey: ['asset', id] }); void qc.invalidateQueries({ queryKey: ['assets'] }); void qc.invalidateQueries({ queryKey: ['asset-transfers'] }) }
  const move = useMutation({ mutationFn: () => askAssetTransfer(id, to, why), onSuccess: () => { setMoving(false); setTo(''); setWhy(''); setError(null); refresh() }, onError: (e) => setError((e as Error).message) })
  const maintain = useMutation({
    mutationFn: () => addAssetMaintenance(id, { description: m.description, cost: m.cost ? num(m.cost) : undefined, vendor: m.vendor || undefined, nextDue: m.nextDue || null }),
    onSuccess: () => { setWork(false); setM({ description: '', cost: '', vendor: '', nextDue: '' }); setError(null); refresh() }, onError: (e) => setError((e as Error).message),
  })
  const a = d.data?.summary
  return (
    <Dialog open onClose={onClose} maxWidth="md" fullWidth>
      <DialogTitle>{a ? `${a.assetNo} · ${a.name}` : 'Asset'} {a && <Chip size="small" variant="outlined" color={statusColor(a.status)} label={a.status.toLowerCase()} sx={{ ml: 1 }} />}</DialogTitle>
      <DialogContent>
        {error && <Alert severity="error" sx={{ mb: 1 }} onClose={() => setError(null)}>{error}</Alert>}
        {a && d.data && (
          <Stack spacing={2}>
            <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(4, auto)', gap: 0.5, columnGap: 3, fontSize: 14 }}>
              <span>Tag</span><b>{a.tag}</b><span>Category</span><b>{a.category}</b>
              <span>Where</span><b>{a.location}</b><span>Serial</span><b>{d.data.serialNo ?? ''}</b>
              <span>Acquired</span><b>{a.acquisitionDate}</b><span>In service</span><b>{a.inServiceDate}</b>
              <span>Cost</span><b>{money(a.cost)}</b><span>Salvage</span><b>{money(a.salvageValue)}</b>
              <span>Life</span><b>{a.lifeMonths} months</b><span>Reference</span><b>{d.data.reference ?? ''}</b>
              <span>Depreciation booked</span><b>{money(a.booked)}</b><span>Of it posted</span><b>{money(a.posted)}</b>
              <span>Net book value</span><b>{money(a.netBookValue)}</b><span>{a.disposedOn ? 'Disposed' : ''}</span><b>{a.disposedOn ?? ''}</b>
            </Box>
            {custodian && a.status === 'ACTIVE' && (
              <Stack direction="row" spacing={1}>
                <Button variant="outlined" onClick={() => setMoving(!moving)}>Transfer</Button>
                <Button variant="outlined" onClick={() => setWork(!work)}>Record maintenance</Button>
                <Button variant="outlined" color="warning" onClick={() => setDisposing(true)}>Dispose</Button>
              </Stack>
            )}
            {moving && (
              <Stack direction="row" spacing={1.5}>
                <TextField size="small" label="To where" value={to} onChange={(e) => setTo(e.target.value)} sx={{ width: 220 }} />
                <TextField size="small" label="Reason" value={why} onChange={(e) => setWhy(e.target.value)} sx={{ flex: 1 }} />
                <Button variant="contained" disabled={move.isPending || !to || !why} onClick={() => move.mutate()}>Ask</Button>
              </Stack>
            )}
            {work && (
              <Stack direction="row" spacing={1.5}>
                <TextField size="small" label="What was done" value={m.description} onChange={(e) => setM({ ...m, description: e.target.value })} sx={{ flex: 1 }} />
                <TextField size="small" label="Cost" value={m.cost} onChange={(e) => setM({ ...m, cost: e.target.value })} sx={{ width: 100 }} />
                <TextField size="small" label="By" value={m.vendor} onChange={(e) => setM({ ...m, vendor: e.target.value })} sx={{ width: 140 }} />
                <TextField size="small" type="date" label="Next due" value={m.nextDue} onChange={(e) => setM({ ...m, nextDue: e.target.value })} slotProps={{ inputLabel: { shrink: true } }} sx={{ width: 150 }} />
                <Button variant="contained" disabled={maintain.isPending || !m.description} onClick={() => maintain.mutate()}>Save</Button>
              </Stack>
            )}
            <Box>
              <Typography sx={label} color="text.secondary">Depreciation</Typography>
              <Table size="small"><TableBody>
                {d.data.depreciation.map((r) => <TableRow key={r.periodEnd}><TableCell>{r.periodEnd}</TableCell><TableCell align="right">{money(r.amount)}</TableCell><TableCell>{r.journal ?? ''}</TableCell><TableCell>{r.journalStatus?.toLowerCase() ?? ''}</TableCell></TableRow>)}
                {d.data.depreciation.length === 0 && <TableRow><TableCell><Typography color="text.secondary">None yet.</Typography></TableCell></TableRow>}
              </TableBody></Table>
            </Box>
            <Box>
              <Typography sx={label} color="text.secondary">Transfers</Typography>
              <Table size="small"><TableBody>
                {d.data.transfers.map((t) => <TableRow key={t.id}><TableCell>{t.transferNo}</TableCell><TableCell>{t.from} → {t.to}</TableCell><TableCell>{t.reason}</TableCell><TableCell><Chip size="small" variant="outlined" color={statusColor(t.status)} label={t.status.toLowerCase()} /></TableCell></TableRow>)}
                {d.data.transfers.length === 0 && <TableRow><TableCell><Typography color="text.secondary">None.</Typography></TableCell></TableRow>}
              </TableBody></Table>
            </Box>
            <Box>
              <Typography sx={label} color="text.secondary">Maintenance</Typography>
              <Table size="small"><TableBody>
                {d.data.maintenance.map((r) => <TableRow key={r.id}><TableCell>{r.date}</TableCell><TableCell>{r.description}</TableCell><TableCell align="right">{r.cost > 0 ? money(r.cost) : ''}</TableCell><TableCell>{r.vendor ?? ''}</TableCell><TableCell>{r.nextDue ? `next ${r.nextDue}` : ''}</TableCell></TableRow>)}
                {d.data.maintenance.length === 0 && <TableRow><TableCell><Typography color="text.secondary">None.</Typography></TableCell></TableRow>}
              </TableBody></Table>
            </Box>
          </Stack>
        )}
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Close</Button></DialogActions>
      {disposing && a && <DisposeDialog asset={a} onClose={() => { setDisposing(false); refresh() }} />}
    </Dialog>
  )
}

function Register({ custodian }: { custodian: boolean }) {
  const [q, setQ] = useState('')
  const [status, setStatus] = useState('')
  const list = useQuery({ queryKey: ['assets', q, status], queryFn: () => listAssets(q || undefined, status || undefined) })
  const due = useQuery({ queryKey: ['asset-due'], queryFn: () => listAssetMaintenanceDue(30) })
  const [creating, setCreating] = useState(false)
  const [open, setOpen] = useState<number | null>(null)
  const nbv = (list.data ?? []).filter((a) => a.status === 'ACTIVE').reduce((s, a) => s + a.netBookValue, 0)
  return (
    <Box>
      {(due.data ?? []).length > 0 && <Alert severity="warning" sx={{ mb: 1.5 }}>Maintenance due: {(due.data ?? []).map((x) => `${x.assetNo} ${x.name} (${x.nextDue})`).join(', ')}</Alert>}
      <Stack direction="row" spacing={1.5} sx={{ mb: 1.5, alignItems: 'center' }}>
        <TextField size="small" label="Search" value={q} onChange={(e) => setQ(e.target.value)} sx={{ width: 240 }} />
        <TextField select size="small" label="Status" value={status} onChange={(e) => setStatus(e.target.value)} sx={{ width: 140 }}>
          <MenuItem value="">All</MenuItem><MenuItem value="ACTIVE">Active</MenuItem><MenuItem value="DISPOSED">Disposed</MenuItem>
        </TextField>
        {custodian && <Button variant="contained" startIcon={<FiPlus />} onClick={() => setCreating(true)}>Register</Button>}
        <Typography sx={{ ml: 'auto' }}>Net book value of active assets <b>{money(nbv)}</b></Typography>
      </Stack>
      <Table size="small">
        <TableHead><TableRow><TableCell>No.</TableCell><TableCell>Tag</TableCell><TableCell>Name</TableCell><TableCell>Category</TableCell><TableCell>Where</TableCell><TableCell align="right">Cost</TableCell><TableCell align="right">Net book value</TableCell><TableCell>Status</TableCell></TableRow></TableHead>
        <TableBody>
          {(list.data ?? []).map((a) => (
            <TableRow key={a.id} hover sx={{ cursor: 'pointer' }} onClick={() => setOpen(a.id)}>
              <TableCell>{a.assetNo}</TableCell><TableCell>{a.tag}</TableCell><TableCell>{a.name}</TableCell><TableCell>{a.category}</TableCell><TableCell>{a.location}</TableCell>
              <TableCell align="right">{money(a.cost)}</TableCell><TableCell align="right">{money(a.netBookValue)}</TableCell>
              <TableCell><Chip size="small" variant="outlined" color={statusColor(a.status)} label={a.status.toLowerCase()} /></TableCell>
            </TableRow>
          ))}
          {list.data?.length === 0 && <TableRow><TableCell colSpan={8}><Typography color="text.secondary">No assets registered yet.</Typography></TableCell></TableRow>}
        </TableBody>
      </Table>
      {creating && <RegisterDialog onClose={() => setCreating(false)} />}
      {open !== null && <DetailDialog id={open} custodian={custodian} onClose={() => setOpen(null)} />}
    </Box>
  )
}

function Transfers({ accountant, user }: { accountant: boolean; user: string }) {
  const qc = useQueryClient()
  const list = useQuery({ queryKey: ['asset-transfers'], queryFn: () => listAssetTransfers() })
  const [rejecting, setRejecting] = useState<AssetTransfer | null>(null)
  const [error, setError] = useState<string | null>(null)
  const refresh = () => { void qc.invalidateQueries({ queryKey: ['asset-transfers'] }); void qc.invalidateQueries({ queryKey: ['assets'] }) }
  const approve = useMutation({ mutationFn: (t: AssetTransfer) => decideAssetTransfer(t.id, 'approve'), onSuccess: () => { setError(null); refresh() }, onError: (e) => setError((e as Error).message) })
  return (
    <Box>
      {error && <Alert severity="error" sx={{ mb: 1 }} onClose={() => setError(null)}>{error}</Alert>}
      <Table size="small">
        <TableHead><TableRow><TableCell>No.</TableCell><TableCell>From → to</TableCell><TableCell>Reason</TableCell><TableCell>By</TableCell><TableCell>Status</TableCell><TableCell /></TableRow></TableHead>
        <TableBody>
          {(list.data ?? []).map((t) => (
            <TableRow key={t.id} hover>
              <TableCell>{t.transferNo}</TableCell><TableCell>{t.from} → {t.to}</TableCell><TableCell>{t.reason}{t.decisionNote && <Typography component="div" sx={{ fontSize: 12 }} color="text.secondary">{t.decisionNote}</Typography>}</TableCell><TableCell>{t.requestedBy}</TableCell>
              <TableCell><Chip size="small" variant="outlined" color={statusColor(t.status)} label={t.status.toLowerCase()} /></TableCell>
              <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                {accountant && t.status === 'PENDING' && t.requestedBy !== user && (<><Button size="small" disabled={approve.isPending} onClick={() => approve.mutate(t)}>Approve</Button><Button size="small" color="error" onClick={() => setRejecting(t)}>Reject</Button></>)}
              </TableCell>
            </TableRow>
          ))}
          {list.data?.length === 0 && <TableRow><TableCell colSpan={6}><Typography color="text.secondary">No transfers yet.</Typography></TableCell></TableRow>}
        </TableBody>
      </Table>
      {rejecting && <ReasonDialog title={`Reject ${rejecting.transferNo}`} label="Reason" onRun={(n) => decideAssetTransfer(rejecting.id, 'reject', n).then(refresh)} onClose={() => setRejecting(null)} />}
    </Box>
  )
}

function Disposals({ accountant, user }: { accountant: boolean; user: string }) {
  const qc = useQueryClient()
  const list = useQuery({ queryKey: ['asset-disposals'], queryFn: listAssetDisposals })
  const [rejecting, setRejecting] = useState<AssetDisposal | null>(null)
  const [error, setError] = useState<string | null>(null)
  const refresh = () => { void qc.invalidateQueries({ queryKey: ['asset-disposals'] }); void qc.invalidateQueries({ queryKey: ['assets'] }) }
  const approve = useMutation({ mutationFn: (x: AssetDisposal) => decideAssetDisposal(x.id, 'approve'), onSuccess: () => { setError(null); refresh() }, onError: (e) => setError((e as Error).message) })
  return (
    <Box>
      {error && <Alert severity="error" sx={{ mb: 1 }} onClose={() => setError(null)}>{error}</Alert>}
      <Table size="small">
        <TableHead><TableRow><TableCell>No.</TableCell><TableCell>Asset</TableCell><TableCell>Date</TableCell><TableCell>Kind</TableCell><TableCell align="right">Book value</TableCell><TableCell align="right">Proceeds</TableCell><TableCell align="right">Gain / loss</TableCell><TableCell>Status</TableCell><TableCell /></TableRow></TableHead>
        <TableBody>
          {(list.data ?? []).map((x) => (
            <TableRow key={x.id} hover>
              <TableCell>{x.disposalNo}</TableCell><TableCell>{x.assetNo} · {x.asset}</TableCell><TableCell>{x.disposalDate}</TableCell><TableCell>{x.kind.toLowerCase()}</TableCell>
              <TableCell align="right">{money(x.bookValue)}</TableCell><TableCell align="right">{money(x.proceeds)}</TableCell><TableCell align="right" sx={{ color: x.gainLoss < 0 ? 'error.main' : undefined }}>{money(x.gainLoss)}</TableCell>
              <TableCell><Chip size="small" variant="outlined" color={statusColor(x.status)} label={x.status.toLowerCase()} />{x.journal && <Typography component="div" sx={{ fontSize: 12 }} color="text.secondary">journal {x.journal}</Typography>}{x.decisionNote && <Typography component="div" sx={{ fontSize: 12 }} color="text.secondary">{x.decisionNote}</Typography>}</TableCell>
              <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                {accountant && x.status === 'PENDING' && x.requestedBy !== user && (<><Button size="small" disabled={approve.isPending} onClick={() => approve.mutate(x)}>Approve</Button><Button size="small" color="error" onClick={() => setRejecting(x)}>Reject</Button></>)}
              </TableCell>
            </TableRow>
          ))}
          {list.data?.length === 0 && <TableRow><TableCell colSpan={9}><Typography color="text.secondary">No disposals yet. Ask for one from the asset's own window.</Typography></TableCell></TableRow>}
        </TableBody>
      </Table>
      {rejecting && <ReasonDialog title={`Reject ${rejecting.disposalNo}`} label="Reason" onRun={(n) => decideAssetDisposal(rejecting.id, 'reject', n).then(refresh)} onClose={() => setRejecting(null)} />}
    </Box>
  )
}

function Depreciation() {
  const qc = useQueryClient()
  const [through, setThrough] = useState(lastMonthEnd())
  const [asked, setAsked] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const preview = useQuery({ queryKey: ['dep-preview', asked], queryFn: () => previewDepreciation(asked!), enabled: !!asked, retry: false, staleTime: Infinity })
  const history = useQuery({ queryKey: ['dep-runs'], queryFn: listDepreciationRuns })
  const run = useMutation({
    mutationFn: () => runDepreciation(asked!),
    onSuccess: (r) => { setError(null); setNotice(`Left ${r.length} draft journal(s): ${r.map((x) => x.reference).join(', ')}. Review and post them on Reports > Accounting > Journals.`); void qc.invalidateQueries({ queryKey: ['dep-preview'] }); void qc.invalidateQueries({ queryKey: ['dep-runs'] }); void qc.invalidateQueries({ queryKey: ['assets'] }) },
    onError: (e) => { setNotice(null); setError((e as Error).message) },
  })
  const p = preview.data
  return (
    <Box>
      <Typography sx={{ fontSize: 13, mb: 1.5 }} color="text.secondary">
        Straight-line, monthly, from the month after an asset goes into service. Choose a month end, look at the figures, then leave the draft journals (debit the depreciation expense, credit the accumulated depreciation of each category).
        Another person reviews, approves and posts them. A month is never depreciated twice for an asset.
      </Typography>
      <Stack direction="row" spacing={1.5} sx={{ mb: 1.5 }}>
        <TextField size="small" type="date" label="Depreciate up to" value={through} onChange={(e) => { setThrough(e.target.value); setAsked(null) }} slotProps={{ inputLabel: { shrink: true } }} helperText="Last day of a month" />
        <Button variant="outlined" sx={{ mt: 0.25 }} onClick={() => { setError(null); setNotice(null); setAsked(through) }}>Preview</Button>
      </Stack>
      {error && <Alert severity="error" sx={{ mb: 1 }} onClose={() => setError(null)}>{error}</Alert>}
      {notice && <Alert severity="success" sx={{ mb: 1 }} onClose={() => setNotice(null)}>{notice}</Alert>}
      {preview.error && <Alert severity="warning" sx={{ mb: 1 }}>{(preview.error as Error).message}</Alert>}
      {p && (
        <Box sx={{ mb: 3 }}>
          {p.periods.length === 0 ? <Typography color="text.secondary">Nothing is due up to {p.through}.</Typography> : (
            <>
              <Table size="small" sx={{ mb: 1 }}>
                <TableHead><TableRow><TableCell>Month end</TableCell><TableCell>Journal</TableCell><TableCell align="right">Assets</TableCell><TableCell align="right">Depreciation</TableCell></TableRow></TableHead>
                <TableBody>{p.periods.map((x) => <TableRow key={x.periodEnd}><TableCell>{x.periodEnd}</TableCell><TableCell>{x.reference}{x.referenceUsed && <Chip size="small" color="error" variant="outlined" label="reference already used" sx={{ ml: 1 }} />}</TableCell><TableCell align="right">{x.assets}</TableCell><TableCell align="right">{money(x.total)}</TableCell></TableRow>)}</TableBody>
              </Table>
              <Button variant="contained" disabled={run.isPending || p.periods.some((x) => x.referenceUsed)} onClick={() => run.mutate()}>Leave {p.periods.length} draft journal(s) · {money(p.total)}</Button>
            </>
          )}
        </Box>
      )}
      <Typography sx={label} color="text.secondary">Months depreciated so far</Typography>
      <Table size="small">
        <TableHead><TableRow><TableCell>Month end</TableCell><TableCell align="right">Assets</TableCell><TableCell align="right">Depreciation</TableCell><TableCell>Journal</TableCell><TableCell>Status</TableCell></TableRow></TableHead>
        <TableBody>
          {(history.data ?? []).map((h) => <TableRow key={h.periodEnd}><TableCell>{h.periodEnd}</TableCell><TableCell align="right">{h.assets}</TableCell><TableCell align="right">{money(h.total)}</TableCell><TableCell>{h.journal ?? ''}</TableCell><TableCell>{h.journalStatus?.toLowerCase() ?? ''}</TableCell></TableRow>)}
          {history.data?.length === 0 && <TableRow><TableCell colSpan={5}><Typography color="text.secondary">Nothing depreciated yet.</Typography></TableCell></TableRow>}
        </TableBody>
      </Table>
    </Box>
  )
}

function CategoryDialog({ category, onClose }: { category?: AssetCategory; onClose: () => void }) {
  const qc = useQueryClient()
  const [f, setF] = useState({ name: category?.name ?? '', asset: category?.assetAccount ?? '', accum: category?.accumAccount ?? '', expense: category?.expenseAccount ?? '50328', life: String(category?.lifeMonths ?? 60), salvage: String(category?.salvagePercent ?? 0), active: category?.active ?? true })
  const [error, setError] = useState<string | null>(null)
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement>) => setF({ ...f, [k]: e.target.value })
  const save = useMutation({
    mutationFn: () => saveAssetCategory(category?.id ?? null, { name: f.name, assetAccount: f.asset, accumAccount: f.accum, expenseAccount: f.expense, lifeMonths: Number(f.life), salvagePercent: num(f.salvage || '0'), active: f.active }),
    onSuccess: () => { void qc.invalidateQueries({ queryKey: ['asset-categories'] }); onClose() },
    onError: (e) => setError((e as Error).message),
  })
  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>{category ? `Edit ${category.name}` : 'New asset category'}</DialogTitle>
      <DialogContent>
        <Stack spacing={1.5} sx={{ pt: 1 }}>
          {error && <Alert severity="error">{error}</Alert>}
          <TextField size="small" label="Name" value={f.name} onChange={set('name')} />
          <Stack direction="row" spacing={1.5}>
            <TextField size="small" label="Asset account" value={f.asset} onChange={set('asset')} sx={{ flex: 1 }} helperText="e.g. 1160111" />
            <TextField size="small" label="Accumulated depreciation account" value={f.accum} onChange={set('accum')} sx={{ flex: 1 }} helperText="e.g. 1160112" />
            <TextField size="small" label="Depreciation expense account" value={f.expense} onChange={set('expense')} sx={{ flex: 1 }} helperText="50328 in the old chart" />
          </Stack>
          <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center' }}>
            <TextField size="small" label="Default life, months" value={f.life} onChange={set('life')} sx={{ width: 170 }} />
            <TextField size="small" label="Default salvage %" value={f.salvage} onChange={set('salvage')} sx={{ width: 150 }} />
            {category && <FormControlLabel control={<Checkbox checked={f.active} onChange={(_, v) => setF({ ...f, active: v })} />} label="Active" />}
          </Stack>
        </Stack>
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" disabled={save.isPending} onClick={() => save.mutate()}>Save</Button></DialogActions>
    </Dialog>
  )
}

function Categories({ accountant }: { accountant: boolean }) {
  const list = useQuery({ queryKey: ['asset-categories'], queryFn: listAssetCategories })
  const [editing, setEditing] = useState<AssetCategory | 'new' | null>(null)
  return (
    <Box>
      {accountant && <Button variant="contained" startIcon={<FiPlus />} sx={{ mb: 1.5 }} onClick={() => setEditing('new')}>New category</Button>}
      <Table size="small">
        <TableHead><TableRow><TableCell>Name</TableCell><TableCell>Asset</TableCell><TableCell>Accumulated</TableCell><TableCell>Expense</TableCell><TableCell align="right">Life</TableCell><TableCell align="right">Salvage</TableCell><TableCell>Status</TableCell><TableCell /></TableRow></TableHead>
        <TableBody>
          {(list.data ?? []).map((c) => (
            <TableRow key={c.id} hover>
              <TableCell>{c.name}</TableCell><TableCell>{c.assetAccount}</TableCell><TableCell>{c.accumAccount}</TableCell><TableCell>{c.expenseAccount}</TableCell>
              <TableCell align="right">{c.lifeMonths} mo</TableCell><TableCell align="right">{c.salvagePercent}%</TableCell>
              <TableCell><Chip size="small" variant="outlined" color={c.active ? 'success' : 'default'} label={c.active ? 'active' : 'off'} /></TableCell>
              <TableCell align="right">{accountant && <Button size="small" onClick={() => setEditing(c)}>Edit</Button>}</TableCell>
            </TableRow>
          ))}
          {list.data?.length === 0 && <TableRow><TableCell colSpan={8}><Typography color="text.secondary">No categories yet. An accountant adds the first, naming the ledger accounts it uses.</Typography></TableCell></TableRow>}
        </TableBody>
      </Table>
      {editing && <CategoryDialog category={editing === 'new' ? undefined : editing} onClose={() => setEditing(null)} />}
    </Box>
  )
}

/** Fixed assets: the register, transfers, disposals, the monthly depreciation run (which leaves draft journals) and the categories with their ledger accounts. */
export default function AssetsPage() {
  const me = useMe()
  const role = (r: string) => me.roles.some((x) => x.replace('ROLE_', '') === r)
  const accountant = me.admin || role('ACCOUNTANT')
  const custodian = accountant || role('ASSET_CUSTODIAN')
  const tabs = ['Register', 'Transfers', 'Disposals', ...(accountant ? ['Depreciation'] : []), 'Categories']
  const [tab, setTab] = useState(0)
  const name = tabs[tab]
  return (
    <Box component="main" sx={(t) => ({ minHeight: 'calc(100dvh - 44px)', p: 2, background: appBackground(t) })}>
      <Box sx={(t) => ({ maxWidth: 1250, mx: 'auto', p: 2, ...glass(t) })}>
        <Typography component="h1" sx={{ fontSize: 20, fontWeight: 700, letterSpacing: '-0.02em', mb: 1 }}>Fixed assets</Typography>
        <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 2 }}>{tabs.map((t) => <Tab key={t} label={t} />)}</Tabs>
        {name === 'Register' && <Register custodian={custodian} />}
        {name === 'Transfers' && <Transfers accountant={accountant} user={me.username} />}
        {name === 'Disposals' && <Disposals accountant={accountant} user={me.username} />}
        {name === 'Depreciation' && <Depreciation />}
        {name === 'Categories' && <Categories accountant={accountant} />}
      </Box>
    </Box>
  )
}
