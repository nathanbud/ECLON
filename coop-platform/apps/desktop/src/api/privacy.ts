import { getJson, http, send, errorFrom } from './http'

// ---- data privacy (administrators and data protection officers) ---------------------------------------------------

export type RegisterKey = 'sharing' | 'dpia' | 'retention' | 'classification' | 'incidents' | 'notices'
export type Row = Record<string, string | number | null>

export const listRegister = (key: RegisterKey) => getJson<Row[]>(`/api/privacy/registers/${key}`)
export const addToRegister = (key: RegisterKey, values: Record<string, string>) => send<Row>('POST', `/api/privacy/registers/${key}`, values)
export const changeRegister = (key: RegisterKey, id: number, values: Record<string, string>) => send<Row>('PUT', `/api/privacy/registers/${key}/${id}`, values)
export const activateNotice = (id: number) => send<Row>('POST', `/api/privacy/registers/notices/${id}/activate`)

export interface ReviewRow { what: string; count: number; note: string }
export const getPrivacyReview = () => getJson<ReviewRow[]>('/api/privacy/retention/review')
export const listExports = () => getJson<Row[]>('/api/privacy/exports')

export const listPrivacyRequests = () => getJson<Row[]>('/api/privacy/requests')
export interface NewPrivacyRequest { requestType: string; branchCode?: number; clientId?: number; requesterName: string; contact?: string; receivedOn?: string; summary: string }
export const addPrivacyRequest = (r: NewPrivacyRequest) => send<Row>('POST', '/api/privacy/requests', r)
export type RequestStep = 'verify-identity' | 'fulfil' | 'refuse' | 'withdraw'
export const stepPrivacyRequest = (id: number, step: RequestStep, note: string) => send<Row>('POST', `/api/privacy/requests/${id}/${step}`, { note })
export const startPrivacyRequest = (id: number) => send<Row>('POST', `/api/privacy/requests/${id}/start`)
export const erasePrivacyBiometrics = (id: number) => send<Row>('POST', `/api/privacy/requests/${id}/erase-biometrics`)

/** Fetches the member data package with the sign-in and hands it to the browser as a download. */
export async function downloadMemberPackage(id: number, ref: string): Promise<void> {
  const res = await http(`/api/privacy/requests/${id}/package`)
  if (!res.ok) throw new Error(await errorFrom(res))
  const url = URL.createObjectURL(await res.blob())
  const a = document.createElement('a')
  a.href = url
  a.download = `${ref}-member-data.json`
  a.click()
  URL.revokeObjectURL(url)
}

export interface PrivacyNoticeState {
  active: { id: number; version: string; title: string; body: string } | null
  latest: { version: string; how: string; by: string; at: string } | null
  upToDate: boolean
}
export const getMemberPrivacy = (b: number, c: number) => getJson<PrivacyNoticeState>(`/api/members/${b}/${c}/privacy`)
export const giveMemberNotice = (b: number, c: number, how: string) => send<PrivacyNoticeState>('POST', `/api/members/${b}/${c}/privacy`, { how })
/** Records that a report was downloaded, for the data protection officer's export log. */
export const logExport = (type: 'REPORT' | 'AUDIT', subject: string, rows: number | null) =>
  send<void>('POST', '/api/exports/log', { type, subject, rows }).catch(() => undefined)
