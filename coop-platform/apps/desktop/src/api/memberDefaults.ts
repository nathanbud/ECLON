import { getJson, send } from './http'

// ---- Member defaults (Settings > Membership > Member defaults): what a new member is given in this cooperative when the registration does not say ------
export interface Choice { code: number; name: string; disabled: boolean }
/** What applies now; null where this cooperative has not chosen one. */
export interface MemberDefaultsApplied {
  department: number | null; departmentName: string | null
  office: number | null; officeName: string | null
  /** the member types that may be registered here */
  types: Choice[]
}
export interface MemberDefaultsSetup {
  /** only a super administrator changes them */
  editable: boolean
  applies: MemberDefaultsApplied
  departments: Choice[]
  offices: Choice[]
  memberTypes: Choice[]
}

export const getMemberDefaults = () => getJson<MemberDefaultsApplied>('/api/member-defaults')
export const getMemberDefaultsSetup = () => getJson<MemberDefaultsSetup>('/api/admin/member-defaults')
export const saveMemberDefaults = (department: number | null, office: number | null, types: number[], reason: string) =>
  send<MemberDefaultsSetup>('PUT', '/api/admin/member-defaults', { department, office, types, reason })
