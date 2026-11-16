import { errorFrom, getJson, http, send } from './http'

const BASE = '/api/front-office/passbooks'

/** A member's savings account and where its passbook stands. */
export interface PassbookAccount { typeCode: number; typeName: string | null; refNo: number; balance: number; serial: string | null; lastLine: number | null; lastDate: string | null; waiting: number }
/** One line as it is printed; seq is null on the balance forwarded line. */
export interface PassbookLine { line: number; seq: number | null; date: string; reference: string; deposit: string; withdrawal: string; balance: string; tc: string; teller: string }
export interface PassbookPage { number: number; newPage: boolean; firstLine: number; lastLine: number; lastSeq: number; lines: PassbookLine[] }
export interface PassbookPosition { serial: string | null; lastLine: number; lastSeq: number; lastDate: string | null; balance: string }
export interface PassbookLogged { at: string; action: 'PRINT' | 'REPRINT' | 'NEW_PASSBOOK' | 'POSITION'; username: string; serial: string | null; fromSeq: number | null; toSeq: number | null; firstLine: number | null; lastLine: number | null; reason: string | null }
export interface PassbookDetail {
  clientId: number; clientName: string | null; typeCode: number; typeName: string | null; refNo: number; balance: number
  position: PassbookPosition | null; waiting: number; pages: PassbookPage[]; warnings: string[]; linesPerPage: number; mayCorrect: boolean; history: PassbookLogged[]
}
/** Where the lines go, in inches from the top left of the open passbook. */
export interface PassbookLayout {
  pageWidth: number; pageHeight: number; linesPerPage: number; firstLine: number; lineHeight: number; foldAfter: number; foldGap: number; fontSize: number
  dateX: number; referenceX: number; depositRight: number; withdrawalRight: number; balanceRight: number; tcX: number; tellerX: number; shiftX: number; shiftY: number
}

const acct = (c: number, t: number, r: number) => `${BASE}/${c}/${t}/${r}`

async function pdf(url: string, init?: RequestInit): Promise<Blob> {
  const res = await http(url, init)
  if (!res.ok) throw new Error(await errorFrom(res))
  return res.blob()
}

export const getPassbookAccounts = (clientId: number) => getJson<PassbookAccount[]>(`${BASE}/accounts?clientId=${clientId}`)
export const getPassbook = (c: number, t: number, r: number) => getJson<PassbookDetail>(acct(c, t, r))
/** The next page to print, as a PDF the passbook's size. Nothing moves until it is confirmed. */
export const getPassbookNextPage = (c: number, t: number, r: number) => pdf(`${acct(c, t, r)}/next-page.pdf`)
export const confirmPassbookPage = (c: number, t: number, r: number, expectedSeq: number, expectedLine: number, lastSeq: number) =>
  send<PassbookDetail>('POST', `${acct(c, t, r)}/confirm`, { expectedSeq, expectedLine, lastSeq })
export const reprintPassbook = (c: number, t: number, r: number, body: { fromSeq: number; toSeq: number; firstLine: number; reason: string }) =>
  pdf(`${acct(c, t, r)}/reprint.pdf`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
export const setPassbookPosition = (c: number, t: number, r: number, body: { serial?: string; newPassbook: boolean; lastLine?: number; lastSeq?: number; reason: string }) =>
  send<PassbookDetail>('POST', `${acct(c, t, r)}/position`, body)
export const getPassbookLayout = () => getJson<PassbookLayout>(`${BASE}/layout`)
export const savePassbookLayout = (layout: PassbookLayout, reason: string) => send<PassbookLayout>('PUT', `${BASE}/layout`, { layout, reason })
export const getPassbookTestPage = (layout?: PassbookLayout) =>
  pdf(`${BASE}/layout/test.pdf`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: layout ? JSON.stringify(layout) : 'null' })
