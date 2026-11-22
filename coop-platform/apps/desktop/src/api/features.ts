import { getJson, send } from './http'

// ---- Features (Settings > System > Features): which areas this cooperative uses --------------------------------------------------------------------
export interface FeatureRow { key: string; label: string; note: string; on: boolean }

export const getFeatures = () => getJson<FeatureRow[]>('/api/features')
/** Super administrators: switch areas on or off, with a reason kept in the audit log. */
export const saveFeatures = (changes: Record<string, boolean>, reason: string) => send<FeatureRow[]>('PUT', '/api/admin/features', { changes, reason })
