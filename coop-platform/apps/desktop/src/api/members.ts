import { getJson, http, send } from './http'

export interface Member {
  id: { branchCode: number; clientId: number }
  firstName: string | null
  lastName: string | null
  accountName: string | null
  dateOpened: string | null
  accountStatus: number | null
  cellNumber: string | null
  city: string | null
  closedDate: string | null
  updatedAt: string | null
  middleName: string | null
  suffix: string | null
  clientType: number | null
  dateOfBirth: string | null
  gender: number | null
  civilStatus: number | null
  email: string | null
  street: string | null
  barangay: string | null
  province: string | null
  referenceId: string | null
  accountType: number | null
  alertLevel: number | null
  department: number | null
  profession: string | null
  religion: number | null
  mailingAddress: number | null
  dosriTag: number | null
  includeHealthCare: number | null
  monthlyIncome: string | null
  zipCode: string | null
  residenceSince: number | null
  residenceType: number | null
  provStreet: string | null
  provBarangay: string | null
  provCity: string | null
  provProvince: string | null
}

export interface Page<T> {
  content: T[]
  totalElements: number
  number: number
  size: number
}

export async function searchMembers(q: string, page: number, size: number): Promise<Page<Member>> {
  const params = new URLSearchParams({ q, page: String(page), size: String(size) })
  const res = await http(`/api/members?${params}`)
  if (!res.ok) throw new Error(`Request failed (${res.status})`)
  return res.json()
}

export async function getMember(branchCode: number, clientId: number): Promise<Member> {
  const res = await http(`/api/members/${branchCode}/${clientId}`)
  if (!res.ok) throw new Error(`Request failed (${res.status})`)
  return res.json()
}

/** Descriptions for the numeric codes on a member, keyed by lookup name then code. */
export type Lookups = Record<string, Record<string, string>>

export async function getLookups(): Promise<Lookups> {
  const res = await http('/api/lookups')
  if (!res.ok) throw new Error(`Request failed (${res.status})`)
  return res.json()
}

/** Fetches a member's photo or signature as an object URL, or null when none is on file. */
export async function getMemberImage(kind: 'photo' | 'signature', branchCode: number, clientId: number): Promise<string | null> {
  const res = await http(`/api/members/${branchCode}/${clientId}/${kind}`)
  if (res.status === 404) return null
  if (!res.ok) throw new Error(`Request failed (${res.status})`)
  return URL.createObjectURL(await res.blob())
}

/** One labelled value on a profile tab. `kind` says how to format it. */
export interface DetailField {
  label: string
  value: string | null
  kind: 'text' | 'date' | 'money' | 'year'
  /** For coded fields: the lookup list to choose from and the stored code. */
  lookup?: string | null
  code?: string | null
  /** The column this field writes to, and whether it may be edited. */
  key: string
  editable: boolean
}
export interface DetailSection { title: string; fields: DetailField[] }
/** Sections for each profile tab, keyed by tab name (business, education, contacts, spouse, groups, closed, pledges, others). */
export type MemberDetails = Record<string, DetailSection[]>

export const getMemberDetails = (branchCode: number, clientId: number) =>
  getJson<MemberDetails>(`/api/members/${branchCode}/${clientId}/details`)

export const getMemberTemplate = () => getJson<MemberDetails>('/api/member-requests/template')

export interface HistoryEntry { at: string; user: string | null; activity: string | null; info: string | null; from: string | null; to: string | null }
export const HISTORY_PAGE = 25
export const getMemberHistory = (branchCode: number, clientId: number, page: number) =>
  getJson<HistoryEntry[]>(`/api/members/${branchCode}/${clientId}/history?page=${page}`)

export interface BillingEntry { reference: string | null; date: string | null; amount: string | null; type: string | null; status: string | null; policy: string | null }
export const getMemberBilling = (branchCode: number, clientId: number) =>
  getJson<BillingEntry[]>(`/api/members/${branchCode}/${clientId}/billing`)

/** What this person may do: edit (propose changes) and approve (decide them, Membership Officers only). */
export const getCapabilities = () => getJson<{ edit: boolean; approve: boolean; images: boolean }>('/api/capabilities')

// ---- member changes (maker-checker) --------------------------------------------------------------------------------

export interface ChangeItem {
  column: string
  label: string
  from: string | null
  to: string | null
  fromText: string | null
  toText: string | null
}

export interface Change {
  id: number
  branchCode: number
  clientId: number
  clientName: string | null
  requestedBy: string
  requestedByName: string
  requestedAt: string
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN'
  items: ChangeItem[]
  reason: string | null
  decidedBy: string | null
  decidedAt: string | null
  decisionNote: string | null
}

/**
 * Proposes changed fields, keyed by column name. Nothing changes until a Membership Officer approves.
 * `expectedUpdatedAt` is the record's stamp when it was opened, so the server can refuse a request made on old data.
 */
export const submitChange = (branchCode: number, clientId: number, body: { changes: Record<string, string>; expectedUpdatedAt: string | null }) =>
  send<Change>('POST', `/api/members/${branchCode}/${clientId}/changes`, body)

/** The change waiting for approval on this client, or null. */
export async function getPendingChange(branchCode: number, clientId: number): Promise<Change | null> {
  const res = await http(`/api/members/${branchCode}/${clientId}/changes/pending`)
  if (res.status === 204) return null
  if (!res.ok) throw new Error(`Request failed (${res.status})`)
  return res.json()
}

export const listChanges = (status = 'PENDING') => getJson<Change[]>(`/api/changes?status=${status}`)
export const getPendingCount = () => getJson<{ pending: number }>('/api/changes/count')
export const approveChange = (id: number, note?: string) => send<Change>('POST', `/api/changes/${id}/approve`, { note })
export const rejectChange = (id: number, note: string) => send<Change>('POST', `/api/changes/${id}/reject`, { note })
export const withdrawChange = (id: number) => send<Change>('POST', `/api/changes/${id}/withdraw`)

// ---- locked sign-ins ---------------------------------------------------------------------------------------------------
export interface LockedUser { username: string; secondsLeft: number }
export const listLockedUsers = () => getJson<LockedUser[]>('/api/admin/locked-users')
export const unlockUser = (username: string) => send<LockedUser>('POST', `/api/admin/users/${encodeURIComponent(username)}/unlock`)

// ---- member lifecycle: register and close (maker-checker) ------------------------------------------------------------

export interface MemberRequest {
  id: number; branchCode: number; kind: 'REGISTER' | 'CLOSE' | 'REACTIVATE'; clientId: number | null; summary: string; status: string
  requestedBy: string; requestedAt: string; decidedBy: string | null; decidedAt: string | null; decisionNote: string | null; payload: Record<string, string>
}
export const listMemberRequests = (status = 'PENDING') => getJson<MemberRequest[]>(`/api/member-requests?status=${status}`)
export interface StagedFinger { finger: number; template: string; format: string; quality: number; device: string }
export interface StagedItems { photo: string | null; consent: boolean; fingerprints: StagedFinger[] }
export const checkFingerprintDuplicate = (body: { template: string; format: string; staged: StagedFinger[] }) =>
  send<{ duplicate: boolean; message: string | null }>('POST', '/api/member-requests/biometric-check', body)
export const registerMember = (body: { branchCode: number; clientType: number; fields: Record<string, string>; staged?: StagedItems }) => send<MemberRequest>('POST', '/api/member-requests/registrations', body)
export const requestClosure = (body: { branchCode: number; clientId: number; reason: number; dateDeceased: string | null; note: string | null }) => send<MemberRequest>('POST', '/api/member-requests/closures', body)
export const approveMemberRequest = (id: number) => send<MemberRequest>('POST', `/api/member-requests/${id}/approve`, {})
export const rejectMemberRequest = (id: number, note: string) => send<MemberRequest>('POST', `/api/member-requests/${id}/reject`, { note })
export const withdrawMemberRequest = (id: number) => send<MemberRequest>('POST', `/api/member-requests/${id}/withdraw`)
export const requestReactivation = (body: { branchCode: number; clientId: number; clientType: number; note: string }) => send<MemberRequest>('POST', '/api/member-requests/reactivations', body)
export const getMembershipPolicy = () => getJson<{ returningPaysShareCapital: boolean }>('/api/membership-policy')
export const setMembershipPolicy = (returningPaysShareCapital: boolean, reason: string) => send<{ returningPaysShareCapital: boolean }>('PUT', '/api/admin/membership-policy', { returningPaysShareCapital, reason })

export async function uploadMemberPhoto(branchCode: number, clientId: number, photo: Blob): Promise<void> {
  const body = new FormData()
  body.append('file', photo, 'photo.jpg')
  const res = await http(`/api/members/${branchCode}/${clientId}/photo`, { method: 'POST', body })
  if (!res.ok) {
    let message = `Request failed (${res.status})`
    try { const j = await res.json(); if (j.message) message = j.message } catch { /* keep the status text */ }
    throw new Error(message)
  }
}

// ---- fingerprints --------------------------------------------------------------------------------------------------

export interface FingerprintSummary {
  consent: { version: string; givenAt: string; recordedBy: string } | null
  fingers: { finger: number; name: string; quality: number; device: string | null; capturedAt: string; capturedBy: string }[]
  minQuality: number; threshold: number; recommended: number; simulated: boolean; consentVersion: string
}
const fpUrl = (b: number, c: number, tail = '') => `/api/members/${b}/${c}/${tail}`
export const getFingerprints = (b: number, c: number) => getJson<FingerprintSummary>(fpUrl(b, c, 'fingerprints'))
export const giveBiometricConsent = (b: number, c: number) => send<FingerprintSummary>('POST', fpUrl(b, c, 'biometric-consent'))
export const withdrawBiometricConsent = (b: number, c: number, reason: string) => send<FingerprintSummary>('POST', fpUrl(b, c, 'biometric-consent/withdraw'), { reason })
export const enrolFingerprint = (b: number, c: number, body: { finger: number; template: string; format: string; quality: number; device: string }) => send<FingerprintSummary>('POST', fpUrl(b, c, 'fingerprints'), body)
export const verifyFingerprint = (b: number, c: number, body: { template: string; format: string }) =>
  send<{ matched: boolean; finger: number | null; fingerName: string | null; score: number }>('POST', fpUrl(b, c, 'fingerprints/verify'), body)
export const revokeFingerprint = (b: number, c: number, finger: number, reason: string) => send<FingerprintSummary>('POST', fpUrl(b, c, `fingerprints/${finger}/revoke`), { reason })

// ---- importing a list of members (Advanced mode) ----------------------------------------------------------------------

export interface ImportRow { line: number; name: string; ok: boolean; problems: string[] }
export interface ImportPreview { fileName: string; total: number; ok: number; errors: number; ignoredColumns: string[]; rows: ImportRow[] }
export interface ImportBatch {
  id: number; branchCode: number; fileName: string; totalRows: number; okRows: number; uploadedBy: string; uploadedAt: string; status: string
  decidedBy: string | null; decidedAt: string | null; decisionNote: string | null; openedCount: number | null; failedCount: number | null
}

async function sendFile<T>(url: string, file: File): Promise<T> {
  const body = new FormData()
  body.append('file', file, file.name)
  const res = await http(url, { method: 'POST', body })
  if (!res.ok) {
    let message = `Request failed (${res.status})`
    try { const j = await res.json(); if (j.message) message = j.message } catch { /* keep the status text */ }
    throw new Error(message)
  }
  return res.json() as Promise<T>
}
export const previewMemberImport = (file: File) => sendFile<ImportPreview>('/api/member-imports/preview', file)
export const uploadMemberImport = (file: File, skipErrors: boolean) => sendFile<ImportBatch>(`/api/member-imports?skipErrors=${skipErrors}`, file)
export const listMemberImports = (status = 'PENDING') => getJson<ImportBatch[]>(`/api/member-imports?status=${status}`)
export const approveMemberImport = (id: number) => send<ImportBatch>('POST', `/api/member-imports/${id}/approve`, {})
export const rejectMemberImport = (id: number, note: string) => send<ImportBatch>('POST', `/api/member-imports/${id}/reject`, { note })
export const withdrawMemberImport = (id: number) => send<ImportBatch>('POST', `/api/member-imports/${id}/withdraw`, {})
export async function downloadMemberImportTemplate(kind: 'xlsx' | 'csv' = 'xlsx'): Promise<void> {
  const res = await http(kind === 'xlsx' ? '/api/member-imports/template' : '/api/member-imports/template.csv')
  if (!res.ok) throw new Error(`Request failed (${res.status})`)
  const url = URL.createObjectURL(await res.blob())
  const a = document.createElement('a')
  a.href = url
  a.download = `members-template.${kind}`
  a.click()
  URL.revokeObjectURL(url)
}
