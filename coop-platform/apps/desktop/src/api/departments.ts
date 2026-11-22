import { getJson, send } from './http'

// ---- Departments (Settings > Organization > Departments): the cooperative's departments, which members, users and the books point to ----------------
export interface DepartmentRow {
  id: number
  name: string
  /** the department this one is under (consolidation), or null */
  parent: number | null
  parentName: string | null
  tin: string | null
  address: string | null
  /** switched off: kept for the records that use it, no longer chosen */
  disabled: boolean
  members: number
  users: number
  /** departments under this one */
  children: number
  /** what it is: MAIN_OFFICE, BRANCH, SATELLITE_OFFICE, CONSOLIDATION or OTHER */
  kind: string
  kindName: string
  /** false while the kind is only suggested from the name */
  kindChosen: boolean
}
export interface Departments {
  /** false until the database lets the application change its departments (PostgreSQL V036, MySQL V073) */
  editable: boolean
  departments: DepartmentRow[]
  nextNumber: number
  /** the department a new member is given when none is chosen (2) */
  defaultForMembers: number
  /** text lengths the database keeps: DeptDesc, DeptTIN, DeptAddress */
  lengths: Record<string, number>
  /** false until the database can store department kinds (PostgreSQL V038, MySQL V075) */
  kindsEditable: boolean
  /** the kinds, in the order offered, with their words */
  kinds: Record<string, string>
}
export interface DepartmentIn { id?: number; name: string; parent: number | null; tin: string; address: string; reason: string }

export const getDepartments = () => getJson<Departments>('/api/admin/departments')
export const addDepartment = (d: DepartmentIn) => send<Departments>('POST', '/api/admin/departments', d)
export const changeDepartment = (id: number, d: DepartmentIn) => send<Departments>('PUT', `/api/admin/departments/${id}`, d)
export const switchDepartment = (id: number, off: boolean, reason: string) => send<Departments>('POST', `/api/admin/departments/${id}/switch`, { off, reason })
export const setDepartmentKind = (id: number, kind: string, reason: string) => send<Departments>('PUT', `/api/admin/departments/${id}/kind`, { kind, reason })
export const deleteDepartment = (id: number, reason: string) => send<Departments>('DELETE', `/api/admin/departments/${id}`, { reason })
