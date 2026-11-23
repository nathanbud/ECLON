import { getJson, send } from './http'

// ---- Product roles (Settings > Products): which of the cooperative's own account types the system posts to by itself ----------------
export interface ProductRoleChoice { code: number; name: string; disabled: boolean }
/** vouchersPayable: the fund and payable type (class 29) chosen, null when none; choices: this database's fund and payable types */
export interface ProductRoles { vouchersPayable: number | null; choices: ProductRoleChoice[] }
export const getProductRoles = () => getJson<ProductRoles>('/api/admin/product-roles')
export const setProductRoles = (vouchersPayable: number, reason: string) => send<ProductRoles>('PUT', '/api/admin/product-roles', { vouchersPayable, reason })
