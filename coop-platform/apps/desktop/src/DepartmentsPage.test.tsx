import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { DepartmentIn, Departments } from './api/departments'
import DepartmentsPage, { cannotDelete } from './DepartmentsPage'
import { AppThemeProvider } from './theme'

const add = vi.fn()
const toggle = vi.fn()
let data: Departments
vi.mock('./api/departments', async (orig) => ({
  ...(await orig<typeof import('./api/departments')>()),
  getDepartments: async () => data,
  addDepartment: (d: DepartmentIn) => add(d),
  switchDepartment: (id: number, off: boolean, reason: string) => toggle(id, off, reason),
}))

const row = (id: number, name: string, extra: Partial<Departments['departments'][0]> = {}) =>
  ({ id, name, parent: null, parentName: null, tin: null, address: null, disabled: false, members: 0, users: 0, children: 0, ...extra })

const coop: Departments = {
  editable: true, nextNumber: 17, defaultForMembers: 2, lengths: { DeptDesc: 30, DeptTIN: 200, DeptAddress: 200 },
  departments: [
    row(1, 'CONSOLIDATED ALL', { children: 1 }),
    row(2, 'MAIN OFFICE', { parent: 1, parentName: 'CONSOLIDATED ALL', members: 30000, users: 30, tin: '004-034-232-000' }),
    row(16, 'BRENTWOOD'),
  ],
}

function show() {
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><AppThemeProvider><DepartmentsPage /></AppThemeProvider></QueryClientProvider>)
}

describe('Departments', () => {
  beforeEach(() => {
    data = coop
    add.mockReset().mockImplementation(async () => data)
    toggle.mockReset().mockImplementation(async () => data)
  })

  it('lists them with the parent and who belongs, and blocks deleting a used one or department 2', async () => {
    show()
    const main = (await screen.findByText('MAIN OFFICE')).closest('tr')!
    expect(main).toHaveTextContent('CONSOLIDATED ALL')
    expect(main).toHaveTextContent('30,000')
    expect(main).toHaveTextContent('TIN 004-034-232-000')
    expect(screen.getByRole('button', { name: 'Delete MAIN OFFICE' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Delete CONSOLIDATED ALL' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Delete BRENTWOOD' })).toBeEnabled()
    expect(cannotDelete(coop.departments[0], 2)).toMatch(/1 department under it/)
  })

  it('switches one off with a reason', async () => {
    show()
    await userEvent.click(await screen.findByRole('button', { name: 'Switch off BRENTWOOD' }))
    const dialog = screen.getByRole('dialog')
    await userEvent.type(within(dialog).getByLabelText(/Reason/), 'Office closed')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Switch off' }))
    expect(toggle).toHaveBeenCalledWith(16, true, 'Office closed')
  })

  it('on a database without department 2, says why and starts the first one as 2, MAIN OFFICE', async () => {
    data = { ...coop, nextNumber: 1, departments: [] }
    show()
    expect(await screen.findByText(/new members cannot be registered/)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Add department' }))
    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByLabelText(/Department number/)).toHaveValue('2')
    expect(within(dialog).getByLabelText(/Department name/)).toHaveValue('MAIN OFFICE')
    await userEvent.type(within(dialog).getByLabelText(/Reason/), 'First department')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Add department' }))
    expect(add).toHaveBeenCalledWith({ id: 2, name: 'MAIN OFFICE', parent: null, tin: '', address: '', reason: 'First department' })
  })
})
