import { Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle, MenuItem, Stack, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography } from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useRef, useState } from 'react'
import { FiPlus } from 'react-icons/fi'
import {
  downloadDocument, getDocument, getDocumentAccess, getDocumentSetup, grantDocument, listDocuments, removeDocument, revokeDocument, uploadDocument,
  type DocInfo,
} from './api'
import { ReasonDialog } from './ui/dialogs'
import { labelOf } from './ui/labels'
import { notifyError } from './ui/notices'
import { LoadingRow } from './ui/Loading'

const size = (n: number) => (n < 1024 ? `${n} B` : n < 1024 * 1024 ? `${(n / 1024).toFixed(0)} KB` : `${(n / 1024 / 1024).toFixed(1)} MB`)
const classColor = (c: string) => (c === 'RESTRICTED' ? 'error' : c === 'CONFIDENTIAL' ? 'warning' : c === 'INTERNAL' ? 'info' : 'default') as 'error' | 'warning' | 'info' | 'default'
const label = { fontSize: 12, fontWeight: 700, letterSpacing: '0.06em', textTransform: 'uppercase' } as const

function UploadDialog({ entityType, entityId, onClose }: { entityType?: string; entityId?: string; onClose: () => void }) {
  const qc = useQueryClient()
  const setup = useQuery({ queryKey: ['doc-setup'], queryFn: getDocumentSetup })
  const input = useRef<HTMLInputElement>(null)
  const [file, setFile] = useState<File | null>(null)
  const [f, setF] = useState({ entityType: entityType ?? 'MEMBER', entityId: entityId ?? '', title: '', description: '', classification: '', retentionDate: '' })
  const fixed = !!entityType
  const min = setup.data?.minRetentionYears[f.entityType] ?? 5
  const save = useMutation({
    mutationFn: () => uploadDocument(file!, { entityType: f.entityType, entityId: f.entityId, title: f.title, description: f.description, classification: f.classification, retentionDate: f.retentionDate }),
    onSuccess: () => { void qc.invalidateQueries({ queryKey: ['documents'] }); onClose() },
    onError: (e) => notifyError((e as Error).message),
  })
  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>Attach a document</DialogTitle>
      <DialogContent>
        <Stack spacing={1.5} sx={{ pt: 1 }}>
          <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center' }}>
            <Button variant="outlined" onClick={() => input.current?.click()}>Choose file</Button>
            <input ref={input} type="file" hidden accept={(setup.data?.extensions ?? []).map((e) => `.${e}`).join(',')} onChange={(e) => { const x = e.target.files?.[0] ?? null; setFile(x); if (x && !f.title) setF({ ...f, title: x.name.replace(/\.[^.]+$/, '') }) }} />
            <Typography color={file ? 'text.primary' : 'text.secondary'}>{file ? `${file.name} · ${size(file.size)}` : `PDF, PNG, JPEG, Word, Excel, CSV or text, up to ${setup.data?.maxMegabytes ?? 10} MB`}</Typography>
          </Stack>
          {!fixed && (
            <Stack direction="row" spacing={1.5}>
              <TextField select size="small" label="Belongs to" value={f.entityType} onChange={(e) => setF({ ...f, entityType: e.target.value })} sx={{ width: 200 }}>
                {(setup.data?.entityTypes ?? []).map((t) => <MenuItem key={t} value={t}>{labelOf(t)}</MenuItem>)}
              </TextField>
              <TextField size="small" label="Record number" value={f.entityId} onChange={(e) => setF({ ...f, entityId: e.target.value })} sx={{ flex: 1 }} />
            </Stack>
          )}
          <TextField size="small" label="Title" value={f.title} onChange={(e) => setF({ ...f, title: e.target.value })} />
          <TextField size="small" label="Description (optional)" value={f.description} onChange={(e) => setF({ ...f, description: e.target.value })} />
          <Stack direction="row" spacing={1.5}>
            <TextField select size="small" label="Who may read it" value={f.classification} onChange={(e) => setF({ ...f, classification: e.target.value })} sx={{ flex: 1 }}
              helperText={f.classification === '' ? 'Default: confidential for members and loans, internal for the rest' : undefined}>
              <MenuItem value="">Default for this record</MenuItem>
              <MenuItem value="PUBLIC">Public: anyone signed in</MenuItem>
              <MenuItem value="INTERNAL">Internal: staff who look after the record</MenuItem>
              <MenuItem value="CONFIDENTIAL">Confidential: clerks, administrators and those you share it with</MenuItem>
              <MenuItem value="RESTRICTED">Restricted: administrators and those you share it with</MenuItem>
            </TextField>
            <TextField size="small" type="date" label={`Keep until (at least ${min} years)`} value={f.retentionDate} onChange={(e) => setF({ ...f, retentionDate: e.target.value })} slotProps={{ inputLabel: { shrink: true } }} sx={{ width: 220 }} />
          </Stack>
        </Stack>
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" disabled={save.isPending || !file || !f.title.trim() || !f.entityId.trim()} onClick={() => save.mutate()}>Upload</Button></DialogActions>
    </Dialog>
  )
}

function DetailDialog({ id, onClose }: { id: number; onClose: () => void }) {
  const qc = useQueryClient()
  const d = useQuery({ queryKey: ['documents', 'one', id], queryFn: () => getDocument(id) })
  const manage = d.data?.document.canManage ?? false
  const access = useQuery({ queryKey: ['documents', 'access', id], queryFn: () => getDocumentAccess(id), enabled: manage })
  const input = useRef<HTMLInputElement>(null)
  const [who, setWho] = useState('')
  const [removing, setRemoving] = useState(false)
  const refresh = () => { void qc.invalidateQueries({ queryKey: ['documents'] }) }
  const fail = (e: unknown) => notifyError((e as Error).message)
  const grant = useMutation({ mutationFn: () => grantDocument(id, who.trim()), onSuccess: () => { setWho(''); refresh() }, onError: fail })
  const revoke = useMutation({ mutationFn: (u: string) => revokeDocument(id, u), onSuccess: () => { refresh() }, onError: fail })
  const version = useMutation({ mutationFn: (f: File) => uploadDocument(f, {}, id), onSuccess: () => { refresh(); onClose() }, onError: fail })
  const get = (v: DocInfo) => downloadDocument(v.id, v.fileName).catch(fail)
  const doc = d.data?.document
  return (
    <Dialog open onClose={onClose} maxWidth="md" fullWidth>
      <DialogTitle>{doc ? `${doc.docNo} · ${doc.title}` : 'Document'} {doc && <Chip size="small" variant="outlined" color={classColor(doc.classification)} label={doc.classification.toLowerCase()} sx={{ ml: 1 }} />}</DialogTitle>
      <DialogContent>
        {doc && d.data && (
          <Stack spacing={2}>
            <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(4, auto)', gap: 0.5, columnGap: 3, fontSize: 13 }}>
              <span>Belongs to</span><b>{doc.entityType.replace('_', ' ').toLowerCase()} {doc.entityId}</b><span>File</span><b>{doc.fileName}</b>
              <span>Uploaded by</span><b>{doc.createdBy}</b><span>On</span><b>{doc.createdAt.replace('T', ' ').slice(0, 16)}</b>
              <span>Kept until</span><b>{doc.retentionDate}</b><span>Size</span><b>{size(doc.sizeBytes)}</b>
              <span>Status</span><b>{labelOf(doc.status)}</b><span>Version</span><b>{doc.version}</b>
            </Box>
            <Typography variant="body2" sx={{ wordBreak: 'break-all' }} color="text.secondary">SHA-256 {doc.sha256}</Typography>
            {doc.description && <Typography>{doc.description}</Typography>}
            <Stack direction="row" spacing={1}>
              <Button variant="contained" onClick={() => void get(doc)}>Download</Button>
              {manage && doc.status === 'ACTIVE' && <Button variant="outlined" onClick={() => input.current?.click()} disabled={version.isPending}>Upload a new version</Button>}
              {manage && doc.status === 'ACTIVE' && <Button variant="outlined" color="warning" onClick={() => setRemoving(true)}>Remove</Button>}
              <input ref={input} type="file" hidden onChange={(e) => { const f = e.target.files?.[0]; if (f) version.mutate(f); e.target.value = '' }} />
            </Stack>
            <Box>
              <Typography sx={label} color="text.secondary">Versions</Typography>
              <Table size="small"><TableBody>
                {d.data.versions.map((v) => <TableRow key={v.id}><TableCell>v{v.version}</TableCell><TableCell>{v.fileName}</TableCell><TableCell>{size(v.sizeBytes)}</TableCell><TableCell>{v.createdBy}, {v.createdAt.slice(0, 10)}</TableCell><TableCell>{v.status.toLowerCase()}</TableCell>
                  <TableCell align="right">{v.id !== doc.id && <Button size="small" onClick={() => void get(v)}>Download</Button>}</TableCell></TableRow>)}
              </TableBody></Table>
            </Box>
            {manage && (
              <Box>
                <Typography sx={label} color="text.secondary">Also allowed to read it</Typography>
                <Stack direction="row" spacing={1} sx={{ my: 1, flexWrap: 'wrap', rowGap: 1 }}>
                  {d.data.grants.map((u) => <Chip key={u} label={u} onDelete={() => revoke.mutate(u)} />)}
                  {d.data.grants.length === 0 && <Typography color="text.secondary">Nobody beyond what its classification gives.</Typography>}
                </Stack>
                <Stack direction="row" spacing={1.5}>
                  <TextField size="small" label="User name" value={who} onChange={(e) => setWho(e.target.value)} sx={{ width: 220 }} />
                  <Button variant="outlined" disabled={grant.isPending || !who.trim()} onClick={() => grant.mutate()}>Allow</Button>
                </Stack>
              </Box>
            )}
            {manage && (
              <Box>
                <Typography sx={label} color="text.secondary">Who has used it</Typography>
                <Table size="small"><TableBody>
                  {(access.data ?? []).map((a, i) => <TableRow key={i}><TableCell>{a.at.replace('T', ' ').slice(0, 16)}</TableCell><TableCell>{a.username}</TableCell><TableCell>{a.action.replace('document.', '')}</TableCell><TableCell>{a.detail}</TableCell></TableRow>)}
                </TableBody></Table>
              </Box>
            )}
          </Stack>
        )}
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Close</Button></DialogActions>
      {removing && doc && <ReasonDialog title={`Remove ${doc.docNo}`} label="Reason" onRun={(r) => removeDocument(id, r).then(() => { refresh(); onClose() })} onClose={() => setRemoving(false)} />}
    </Dialog>
  )
}

/**
 * The documents kept for one record (a member, an asset ...), or, without a record, a search over every document the person may read. A document the person may not read is
 * simply not listed. Downloads go through the sign-in and are written to the audit log.
 */
export default function DocumentsPanel({ entityType, entityId, canUpload = true }: { entityType?: string; entityId?: string; canUpload?: boolean }) {
  const [q, setQ] = useState('')
  const [history, setHistory] = useState(false)
  const list = useQuery({ queryKey: ['documents', 'list', entityType, entityId, q, history], queryFn: () => listDocuments({ entityType, entityId, q, history }) })
  const [creating, setCreating] = useState(false)
  const [open, setOpen] = useState<number | null>(null)
  return (
    <Box>
      <Stack direction="row" spacing={1.5} sx={{ mb: 1.5, alignItems: 'center' }}>
        {!entityType && <TextField size="small" label="Search title, file or record number" value={q} onChange={(e) => setQ(e.target.value)} sx={{ width: 300 }} />}
        {canUpload && <Button variant="contained" startIcon={<FiPlus />} onClick={() => setCreating(true)}>Attach a document</Button>}
        <Button size="small" onClick={() => setHistory(!history)}>{history ? 'Current versions only' : 'Show old versions and removed'}</Button>
      </Stack>
      <Table size="small">
        <TableHead><TableRow>{!entityType && <TableCell>Belongs to</TableCell>}<TableCell>No.</TableCell><TableCell>Title</TableCell><TableCell>File</TableCell><TableCell>Read by</TableCell><TableCell>Version</TableCell><TableCell>Uploaded</TableCell><TableCell>Kept until</TableCell><TableCell /></TableRow></TableHead>
        <TableBody>
          <LoadingRow q={list} what="the documents" />
          {(list.data ?? []).map((d) => (
            <TableRow key={d.id} hover>
              {!entityType && <TableCell>{d.entityType.replace('_', ' ').toLowerCase()} {d.entityId}</TableCell>}
              <TableCell>{d.docNo}</TableCell><TableCell>{d.title}{d.status !== 'ACTIVE' && <Chip size="small" variant="outlined" label={labelOf(d.status)} sx={{ ml: 0.75 }} />}</TableCell><TableCell>{d.fileName} · {size(d.sizeBytes)}</TableCell>
              <TableCell><Chip size="small" variant="outlined" color={classColor(d.classification)} label={d.classification.toLowerCase()} /></TableCell>
              <TableCell>v{d.version}</TableCell><TableCell>{d.createdBy}, {d.createdAt.slice(0, 10)}</TableCell><TableCell>{d.retentionDate}</TableCell>
              <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                <Button size="small" onClick={() => void downloadDocument(d.id, d.fileName).catch((e: Error) => notifyError(e.message))}>Download</Button>
                <Button size="small" onClick={() => setOpen(d.id)}>Details</Button>
              </TableCell>
            </TableRow>
          ))}
          {list.data?.length === 0 && <TableRow><TableCell colSpan={9}><Typography color="text.secondary">No documents you can read{entityType ? ' for this record' : ''}.</Typography></TableCell></TableRow>}
        </TableBody>
      </Table>
      {creating && <UploadDialog entityType={entityType} entityId={entityId} onClose={() => setCreating(false)} />}
      {open !== null && <DetailDialog id={open} onClose={() => setOpen(null)} />}
    </Box>
  )
}
