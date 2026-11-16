import { getJson } from './http'

/** The posted transaction behind a ledger row. `sc`, `st`, `se` and `sn` are the subsidiary ledger class, type, event and number, empty on a line that is only on the general ledger. */
export interface TransactionHeader {
  year: number; module: number; code: number; ctlNo: number; type: string | null; typeShort: string | null; controlNo: string; docNo: string | null; date: string | null; time: string | null
  batchNo: string | null; department: string | null; client: string | null; clientNo: string | null; andOr: string | null; sourceReference: string | null; explanation: string | null; status: string
}
export interface TransactionLine {
  seq: number; posted: boolean; sc: number | null; st: number | null; se: number | null; sn: number | null; glCode: string | null; title: string | null
  debit: number; credit: number; clientNo: string | null; clientName: string | null
}
export interface TransactionCheque {
  seq: number; type: string | null; number: string | null; bank: string | null; date: string | null; amount: number; payee: string | null; cleared: boolean; clearedOn: string | null
}
export interface TransactionDetail { header: TransactionHeader; lines: TransactionLine[]; cheques: TransactionCheque[]; debit: number; credit: number; difference: number }

export const getTransaction = (branchCode: number, clientId: number, year: number, code: number, ctlNo: number) =>
  getJson<TransactionDetail>(`/api/members/${branchCode}/${clientId}/transactions/${year}/${code}/${ctlNo}`)
