import { getJson } from './http'

/**
 * One unexpected failure, as kept for system administrators (server ErrorJournal): what the person was told, and the technical details they were
 * not shown: the kind of failure, its cause and fix with table and column names, the exception, and the trace.
 */
export interface SystemError {
  at: string; reference: string; user: string | null; method: string; path: string; status: number; code: string
  userMessage: string; problem: string; fix: string; exception: string; detail: string; trace: string
}

/** The newest first, at most 500. Administrators only. */
export const getSystemErrors = () => getJson<SystemError[]>('/api/admin/errors')
