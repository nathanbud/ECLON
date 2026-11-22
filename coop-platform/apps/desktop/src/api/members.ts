import { getJson, http, send, errorFrom } from './http'

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
  provZipCode: string | null
  /** House, unit, block and lot number, and subdivision, village or building name: present and provincial. */
  houseNo: string | null
  subdivision: string | null
  provHouseNo: string | null
  provSubdivision: string | null
}

export interface Page<T> {
  content: T[]
  totalElements: number
  /** False when more results follow. A name search does not count every match, so the total is only a lower bound. */
  last?: boolean
  number: number
  size: number
}

/** A name search lists active members only; `includeInactive` adds dormant and closed ones. A member ID or reference is found whatever the status. */
export async function searchMembers(q: string, page: number, size: number, includeInactive = false): Promise<Page<Member>> {
  const params = new URLSearchParams({ q, page: String(page), size: String(size) })
  if (includeInactive) params.set('includeInactive', 'true')
  const res = await http(`/api/members?${params}`)
  if (!res.ok) throw new Error(await errorFrom(res))
  return res.json()
}

/** Street names already used on member records in a barangay (most used first), for the street box of an address. There is no official list of streets. */
export async function streetSuggestions(barangay: string, city: string, province: string): Promise<string[]> {
  const params = new URLSearchParams({ barangay, city, province })
  const res = await http(`/api/members/address-streets?${params}`)
  if (!res.ok) throw new Error(await errorFrom(res))
  return res.json()
}

export async function getMember(branchCode: number, clientId: number): Promise<Member> {
  const res = await http(`/api/members/${branchCode}/${clientId}`)
  if (!res.ok) throw new Error(await errorFrom(res))
  return res.json()
}

/** Descriptions for the numeric codes on a member, keyed by lookup name then code. */
export type Lookups = Record<string, Record<string, string>>

export async function getLookups(): Promise<Lookups> {
  const res = await http('/api/lookups')
  if (!res.ok) throw new Error(await errorFrom(res))
  return res.json()
}

/** Fetches a member's photo or signature as an object URL, or null when none is on file. */
export async function getMemberImage(kind: 'photo' | 'signature', branchCode: number, clientId: number): Promise<string | null> {
  const res = await http(`/api/members/${branchCode}/${clientId}/${kind}`)
  if (res.status === 404) return null
  if (!res.ok) throw new Error(await errorFrom(res))
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
export const getCapabilities = () => getJson<{ edit: boolean; approve: boolean; images: boolean; direct: boolean }>('/api/capabilities')

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
  if (!res.ok) throw new Error(await errorFrom(res))
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
  id: number; branchCode: number; kind: 'REGISTER' | 'CLOSE' | 'REACTIVATE' | 'BENEF'; clientId: number | null; summary: string; status: string
  requestedBy: string; requestedAt: string; decidedBy: string | null; decidedAt: string | null; decisionNote: string | null; payload: Record<string, string>
}
export const listMemberRequests = (status = 'PENDING') => getJson<MemberRequest[]>(`/api/member-requests?status=${status}`)
export interface StagedFinger { finger: number; template: string; format: string; quality: number; device: string }
export interface StagedItems { photo: string | null; consent: boolean; fingerprints: StagedFinger[]; signature?: string | null }
export const checkFingerprintDuplicate = (body: { template: string; format: string; staged: StagedFinger[] }) =>
  send<{ duplicate: boolean; message: string | null }>('POST', '/api/member-requests/biometric-check', body)
/** A new-member registration saved as a draft: the typed fields only, private to the person who saved it. */
export interface MemberDraftSummary { id: number; title: string; clientType: number; updatedAt: string }
export interface MemberDraft extends MemberDraftSummary { branchCode: number; fields: Record<string, string> }
export const listMemberDrafts = () => getJson<MemberDraftSummary[]>('/api/member-drafts')
export const getMemberDraft = (id: number) => getJson<MemberDraft>(`/api/member-drafts/${id}`)
/** Saves a new draft (no id) or changes an existing one. */
export const saveMemberDraft = (id: number | null, body: { branchCode: number; clientType: number; fields: Record<string, string> }) =>
  id == null ? send<MemberDraftSummary>('POST', '/api/member-drafts', body) : send<MemberDraftSummary>('PUT', `/api/member-drafts/${id}`, body)
export const deleteMemberDraft = (id: number) => send<void>('DELETE', `/api/member-drafts/${id}`)
export const registerMember = (body: { branchCode: number; clientType: number; fields: Record<string, string>; staged?: StagedItems }) => send<MemberRequest>('POST', '/api/member-requests/registrations', body)
export const requestClosure = (body: { branchCode: number; clientId: number; reason: number; dateDeceased: string | null; note: string | null }) => send<MemberRequest>('POST', '/api/member-requests/closures', body)
export const approveMemberRequest = (id: number) => send<MemberRequest>('POST', `/api/member-requests/${id}/approve`, {})
export const rejectMemberRequest = (id: number, note: string) => send<MemberRequest>('POST', `/api/member-requests/${id}/reject`, { note })
export const withdrawMemberRequest = (id: number) => send<MemberRequest>('POST', `/api/member-requests/${id}/withdraw`)
export const requestReactivation = (body: { branchCode: number; clientId: number; clientType: number; note: string }) => send<MemberRequest>('POST', '/api/member-requests/reactivations', body)
/** officerRegistersDirectly: a Membership Officer's own registration is approved at once (only a super administrator switches it). */
export interface MembershipPolicy { returningPaysShareCapital: boolean; kycBlocksWithdrawals: boolean; officerRegistersDirectly: boolean }
export const getMembershipPolicy = () => getJson<MembershipPolicy>('/api/membership-policy')
/** Changes the switches given; the others stay as they are. */
export const changeMembershipPolicy = (change: Partial<MembershipPolicy>, reason: string) => send<MembershipPolicy>('PUT', '/api/admin/membership-policy', { ...change, reason })

async function uploadMemberImage(kind: 'photo' | 'signature', branchCode: number, clientId: number, picture: Blob): Promise<void> {
  const body = new FormData()
  body.append('file', picture, kind === 'photo' ? 'photo.jpg' : 'signature.jpg')
  const res = await http(`/api/members/${branchCode}/${clientId}/${kind}`, { method: 'POST', body })
  if (!res.ok) throw new Error(await errorFrom(res))
}

export const uploadMemberPhoto = (branchCode: number, clientId: number, photo: Blob) => uploadMemberImage('photo', branchCode, clientId, photo)
/** Adds a new signature (a JPEG or PNG up to 1 MB); the newest one is the one shown. */
export const uploadMemberSignature = (branchCode: number, clientId: number, signature: Blob) => uploadMemberImage('signature', branchCode, clientId, signature)

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

// ---- Know Your Customer ---------------------------------------------------------------------------------------------

export type KycStatus = 'NONE' | 'INCOMPLETE' | 'EXPIRED' | 'REVIEW_DUE' | 'COMPLETE'
export interface Kyc {
  idType: string | null; idNumber: string | null; idIssuer: string | null; idExpiry: string | null; sourceOfFunds: string | null; pep: boolean; pepNote: string | null
  risk: 'LOW' | 'MEDIUM' | 'HIGH' | null; nextReview: string | null; remarks: string | null; hasScan: boolean; updatedBy: string | null; updatedAt: string | null
  status: KycStatus; idTypes: string[]
}
export type KycInput = Pick<Kyc, 'idType' | 'idNumber' | 'idIssuer' | 'idExpiry' | 'sourceOfFunds' | 'pep' | 'pepNote' | 'risk' | 'nextReview' | 'remarks'>
const kycUrl = (b: number, c: number) => `/api/members/${b}/${c}/kyc`
export const getKyc = (b: number, c: number) => getJson<Kyc>(kycUrl(b, c))

/** One member on the KYC review list. */
export interface KycReviewRow { branchCode: number; clientId: number; name: string; status: KycStatus; idType: string | null; idExpiry: string | null; risk: string | null; nextReview: string | null; updatedAt: string | null }
export interface KycReview { counts: Record<KycStatus, number>; status: KycStatus; page: number; size: number; total: number; rows: KycReviewRow[] }
/** Active members whose KYC has this status, a page at a time, with the count of each status. */
export const getKycReview = (status: KycStatus, page: number, size = 50) => getJson<KycReview>(`/api/kyc/review?status=${status}&page=${page}&size=${size}`)
export const saveKyc = (b: number, c: number, body: KycInput) => send<Kyc>('PUT', kycUrl(b, c), body)
export async function getKycScan(b: number, c: number): Promise<string | null> {
  const res = await http(`${kycUrl(b, c)}/id-scan`)
  if (res.status === 404) return null
  if (!res.ok) throw new Error(await errorFrom(res))
  return URL.createObjectURL(await res.blob())
}
export async function uploadKycScan(b: number, c: number, file: Blob): Promise<void> {
  const body = new FormData()
  body.append('file', file, 'id-scan.jpg')
  const res = await http(`${kycUrl(b, c)}/id-scan`, { method: 'POST', body })
  if (!res.ok) throw new Error(await errorFrom(res))
}

async function sendFile<T>(url: string, file: File): Promise<T> {
  const body = new FormData()
  body.append('file', file, file.name)
  const res = await http(url, { method: 'POST', body })
  if (!res.ok) throw new Error(await errorFrom(res))
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
  if (!res.ok) throw new Error(await errorFrom(res))
  const url = URL.createObjectURL(await res.blob())
  const a = document.createElement('a')
  a.href = url
  a.download = `members-template.${kind}`
  a.click()
  URL.revokeObjectURL(url)
}
