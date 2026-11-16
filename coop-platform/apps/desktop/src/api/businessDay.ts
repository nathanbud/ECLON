import { getJson, send } from './http'

/** One business day of the branch, as Front Office > Business Day lists it. */
export interface BusinessDayRow {
  date: string; words: string; status: 'OPEN' | 'CLOSED'; openedBy: string; openedAt: string
  closedBy: string | null; closedAt: string | null; overrideReason: string | null; failedChecks: string | null
}
/** One End Day check. mustPass: End Day needs a reason while it fails; the others are for review. */
export interface DayCheck { key: string; label: string; ok: boolean; detail: string | null; mustPass: boolean }
export interface BusinessDayState {
  branch: number; inUse: boolean; available: boolean; calendarDate: string
  open: BusinessDayRow | null; lastClosed: BusinessDayRow | null; suggested: string | null; legacyDate: string | null
  mayStart: boolean; mayEnd: boolean; mayReopen: boolean; checks: DayCheck[]; recent: BusinessDayRow[]
}
/** For everyone: whether postings follow the business day, and the open date if any. */
export interface BusinessDayStatus { inUse: boolean; calendarDate: string; openDate: string | null; openWords: string | null }
export interface StartCheck { problem: string | null; warnings: string[] }

export const getBusinessDayStatus = () => getJson<BusinessDayStatus>('/api/business-day/status')
export const getBusinessDay = () => getJson<BusinessDayState>('/api/business-day')
export const checkStartDay = (date: string) => getJson<StartCheck>(`/api/business-day/start-check?date=${encodeURIComponent(date)}`)
export const startDay = (date: string, confirmed: boolean) => send<BusinessDayState>('POST', '/api/business-day/start', { date, confirmed })
export const endDay = (reason: string | null) => send<BusinessDayState>('POST', '/api/business-day/end', { reason })
export const reopenDay = (reason: string) => send<BusinessDayState>('POST', '/api/business-day/reopen', { reason })
