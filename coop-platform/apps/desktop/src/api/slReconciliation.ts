import { getJson } from './http'

/** One member account type in a group: its members' total, shown as the group reads it (a credit group's balances positive). */
export interface ReconType { slc: number; slt: number; name: string; total: number }
/** One control account in a group, with its general-ledger balance read the same way. */
export interface ReconAccount { account: string; name: string; balance: number }
/** A group: types and the control accounts they post to, compared. difference = ledger - members; reconciled when it is zero. */
export interface ReconGroup {
  title: string; credit: boolean; members: number; ledger: number; difference: number; reconciled: boolean
  types: ReconType[]; accounts: ReconAccount[]
}
export interface SlReconciliation {
  branch: number; asOf: string; groups: ReconGroup[]; totalDifference: number; unreconciled: number
  ranAt: string; ranBy: string
  /** true when this is the result of a run in the last ten minutes, given again */
  kept: boolean
}

/** Adds up the whole member ledger, so it takes about a minute; a run in the last ten minutes is given again unless `fresh`. */
export const getSlReconciliation = (branchCode: number, asOf: string, fresh: boolean) =>
  getJson<SlReconciliation>(`/api/reports/sl-reconciliation?branchCode=${branchCode}&asOf=${encodeURIComponent(asOf)}&fresh=${fresh}`)
