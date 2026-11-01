import { getJson, send } from './http'
import { type MemberRequest } from './members'

export interface Beneficiary {
  id: number; name: string | null; relationCode: number | null; relation: string | null; dateOfBirth: string | null; occupation: string | null
  typeCode: number; type: string | null; added: string | null
}
/** Asks to add, change or remove a beneficiary. `benefId` names the row to change or remove; `note` is the reason for a removal. A Membership Officer other than the sender decides it. */
export interface BeneficiaryRequest {
  branchCode: number; clientId: number; action: 'ADD' | 'EDIT' | 'REMOVE'; benefId: number | null
  name: string | null; relation: number | null; dateOfBirth: string | null; occupation: string | null; type: number | null; note: string | null
}
export const requestBeneficiary = (body: BeneficiaryRequest) => send<MemberRequest>('POST', '/api/member-requests/beneficiaries', body)
export const getBeneficiaries = (branchCode: number, clientId: number) => getJson<Beneficiary[]>(`/api/members/${branchCode}/${clientId}/beneficiaries`)
