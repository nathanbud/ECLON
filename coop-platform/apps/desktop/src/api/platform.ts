import { getJson, http, send } from './http'
import { apiUrl } from './server'

// ---- reports (guide phase 20) ---------------------------------------------------------------------------------------------
export interface ReportParam { name: string; label: string; type: 'date' | 'number' | 'text'; defaultValue: string }
export interface ReportInfo { id: string; group: string; title: string; description: string; params: ReportParam[] }
export interface ReportColumn { key: string; label: string; type: 'text' | 'money' | 'number' | 'percent' | 'date' }
export interface ReportTable { columns: ReportColumn[]; rows: (string | number | null)[][]; totals: (string | number | null)[] | null; note: string | null }
export interface ReportResult { info: ReportInfo; table: ReportTable; ranAt: string; ranBy: string }
export const listReports = () => getJson<ReportInfo[]>('/api/reports')
export const runReport = (id: string, params: Record<string, string>) => {
  const q = Object.entries(params).filter(([, v]) => v !== '').map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`).join('&')
  return getJson<ReportResult>(`/api/reports/${id}${q ? `?${q}` : ''}`)
}

// ---- documents (guide phase 16) --------------------------------------------------------------------------------------------
export interface DocInfo {
  id: number; docNo: string; entityType: string; entityId: string; title: string; description: string | null; fileName: string; mimeType: string; sizeBytes: number; sha256: string
  classification: 'PUBLIC' | 'INTERNAL' | 'CONFIDENTIAL' | 'RESTRICTED'; version: number; supersedesId: number | null; status: 'ACTIVE' | 'SUPERSEDED' | 'REMOVED'; retentionDate: string; createdBy: string; createdAt: string; canManage: boolean
}
export interface DocDetail { document: DocInfo; versions: DocInfo[]; grants: string[] }
export interface DocAccessRow { at: string; username: string; action: string; detail: string | null }
export interface DocSetup { entityTypes: string[]; classifications: string[]; minRetentionYears: Record<string, number>; extensions: string[]; maxMegabytes: number }
export const getDocumentSetup = () => getJson<DocSetup>('/api/documents/setup')
export const listDocuments = (p: { entityType?: string; entityId?: string; q?: string; history?: boolean }) => {
  const q = Object.entries(p).filter(([, v]) => v !== undefined && v !== '' && v !== false).map(([k, v]) => `${k}=${encodeURIComponent(String(v))}`).join('&')
  return getJson<DocInfo[]>(`/api/documents${q ? `?${q}` : ''}`)
}
export const getDocument = (id: number) => getJson<DocDetail>(`/api/documents/${id}`)
async function failure(res: Response): Promise<never> {
  let message = `Request failed (${res.status})`
  try { const j = await res.json(); if (j.message) message = j.message } catch { /* keep the generic message */ }
  throw new Error(message)
}
/** Attaches a file to a record (fields), or, with {@code versionOf}, replaces that document with a new version. */
export async function uploadDocument(file: File, fields: Record<string, string>, versionOf?: number): Promise<DocInfo> {
  const fd = new FormData()
  if (!versionOf) for (const [k, v] of Object.entries(fields)) if (v !== '') fd.append(k, v)
  fd.append('file', file)
  const res = await http(versionOf ? `/api/documents/${versionOf}/versions` : '/api/documents', { method: 'POST', body: fd })
  if (!res.ok) return failure(res)
  return res.json()
}
/** Fetches the file with the sign-in and hands it to the browser as a download. */
export async function downloadDocument(id: number, fileName: string): Promise<void> {
  const res = await http(`/api/documents/${id}/content`)
  if (!res.ok) return failure(res)
  const url = URL.createObjectURL(await res.blob())
  const a = document.createElement('a')
  a.href = url
  a.download = fileName
  a.click()
  URL.revokeObjectURL(url)
}
export const removeDocument = (id: number, reason: string) => send<DocInfo>('POST', `/api/documents/${id}/remove`, { reason })
export const grantDocument = (id: number, username: string) => send<string[]>('POST', `/api/documents/${id}/grants`, { username })
export async function revokeDocument(id: number, username: string): Promise<string[]> {
  const res = await http(`/api/documents/${id}/grants/${encodeURIComponent(username)}`, { method: 'DELETE' })
  if (!res.ok) return failure(res)
  return res.json()
}
export const getDocumentAccess = (id: number) => getJson<DocAccessRow[]>(`/api/documents/${id}/access`)

// ---- workflow engine (guide phase 17) -------------------------------------------------------------------------------------------
export interface WorkflowStep { no: number; name: string; role: string; minAmount: number }
export interface WorkflowDefinition { entityType: string; label: string; active: boolean; steps: WorkflowStep[] }
export interface WorkflowOptions { roles: string[]; maxSteps: number }
export interface WorkflowWaiting { id: number; entityType: string; label: string; entityId: string; description: string; amount: number; requestedBy: string; step: string; role: string; since: string }
export interface WorkflowAction { stepNo: number; step: string; actor: string; decision: string; note: string | null; at: string }
export interface WorkflowInstance { id: number; entityType: string; entityId: string; amount: number; requestedBy: string; status: string; waitingFor: string | null; waitingRole: string | null; actions: WorkflowAction[] }
export const getWorkflowDefinitions = () => getJson<WorkflowDefinition[]>('/api/workflow/definitions')
export const getWorkflowOptions = () => getJson<WorkflowOptions>('/api/workflow/options')
export const saveWorkflowDefinition = (entityType: string, active: boolean, steps: { name: string; role: string; minAmount: number }[]) =>
  send<WorkflowDefinition>('PUT', `/api/workflow/definitions/${entityType}`, { active, steps: steps.map((s, i) => ({ no: i + 1, ...s })) })
export const getWorkflowWaiting = () => getJson<WorkflowWaiting[]>('/api/workflow/waiting')
export const getWorkflowInstance = (entityType: string, entityId: string) => getJson<WorkflowInstance>(`/api/workflow/${entityType}/${entityId}`)
export const decideWorkflow = (entityType: string, entityId: string, kind: 'approve' | 'reject', note?: string) => send<WorkflowInstance>('POST', `/api/workflow/${entityType}/${entityId}/${kind}`, { note })

// ---- sign-in screen branding (read by everyone, changed by a SUPERADMIN) -------------------------------------------------

export interface Branding { coopName: string | null; tagline: string | null; hasLogo: boolean; version: string }

/** Read before anyone has signed in, so no sign-in is sent. */
export async function getBranding(): Promise<Branding> {
  const res = await fetch(apiUrl('/api/public/branding'), { cache: 'no-store' })
  if (!res.ok) throw new Error(`Request failed (${res.status})`)
  return res.json()
}
export const setBrandingText = (coopName: string, tagline: string) => send<Branding>('PUT', '/api/admin/branding', { coopName, tagline })
export const removeBrandingLogo = () => send<Branding>('DELETE', '/api/admin/branding/logo')
export async function uploadBrandingLogo(file: File): Promise<Branding> {
  const body = new FormData()
  body.append('file', file, file.name)
  const res = await http('/api/admin/branding/logo', { method: 'POST', body })
  if (!res.ok) {
    let message = `Request failed (${res.status})`
    try { const j = await res.json(); if (j.message) message = j.message } catch { /* keep the status text */ }
    throw new Error(message)
  }
  return res.json()
}
