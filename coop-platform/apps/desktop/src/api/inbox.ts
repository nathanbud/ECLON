import { getJson } from './http'

/** One thing waiting on the signed-in person. `view` and `tab` name the screen of the app that decides it. */
export interface InboxItem { kind: string; label: string; summary: string; by: string | null; at: string | null; view: string; tab: string | null }
export interface Inbox { items: InboxItem[]; total: number; byKind: Record<string, number> }

export const getInbox = () => getJson<Inbox>('/api/inbox')
export const getInboxCount = () => getJson<{ count: number }>('/api/inbox/count')
