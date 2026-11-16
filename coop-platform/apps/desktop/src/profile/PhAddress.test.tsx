import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppThemeProvider } from '../theme'
import { EditCtx } from './editing'
import { PhAddressFields, StreetField } from './PhAddress'

const suggest = vi.fn(async (_b: string, _c: string, _p: string) => ['MAGSAYSAY AVE', 'WAGNER ROAD'])
vi.mock('../api', () => ({ streetSuggestions: (b: string, c: string, p: string) => suggest(b, c, p) }))

// provinces, cities (name, province) and barangays (name, city), as the generated file has them
const data = {
  p: ['Abra', 'Benguet'],
  c: [['Bangued', 0], ['City of Baguio', 1], ['La Trinidad', 1]],
  b: [['Balili', 2], ['Pacdal', 1], ['Poblacion', 0], ['Poblacion', 2], ['Session Road', 1]],
}

function Harness({ stored }: { stored: { province?: string; city?: string; barangay?: string } }) {
  const [draft, setDraft] = useState<Record<string, string>>({})
  return (
    <QueryClientProvider client={new QueryClient()}>
      <AppThemeProvider>
        <EditCtx.Provider value={{ editing: true, draft, set: (c, v) => setDraft((d) => ({ ...d, [c]: v })) }}>
          <PhAddressFields cols={{ province: 'P', city: 'C', barangay: 'B' }} stored={stored}>{({ province, city, barangay }) => <>{province}{city}{barangay}</>}</PhAddressFields>
          <output data-testid="draft">{JSON.stringify(draft)}</output>
        </EditCtx.Provider>
      </AppThemeProvider>
    </QueryClientProvider>
  )
}

describe('Philippine address fields', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, status: 200, json: async () => data }) as Response))
  })
  afterEach(() => vi.unstubAllGlobals())

  const draft = () => JSON.parse(screen.getByTestId('draft').textContent!)

  it('narrows province, then city or municipality, then barangay, and saves official names in capitals', async () => {
    const user = userEvent.setup()
    render(<Harness stored={{}} />)
    expect(screen.getByLabelText('City or Municipality')).toBeDisabled()
    expect(screen.getByLabelText('Barangay')).toBeDisabled()
    await user.click(screen.getByLabelText('Province'))
    await user.click(await screen.findByRole('option', { name: 'Benguet' }))
    await user.click(screen.getByLabelText('City or Municipality'))
    expect((await screen.findAllByRole('option')).map((o) => o.textContent)).toEqual(['City of Baguio', 'La Trinidad'])
    await user.click(screen.getByRole('option', { name: 'City of Baguio' }))
    await user.click(screen.getByLabelText('Barangay'))
    expect((await screen.findAllByRole('option')).map((o) => o.textContent)).toEqual(['Pacdal', 'Session Road'])
    await user.click(screen.getByRole('option', { name: 'Pacdal' }))
    expect(draft()).toEqual({ P: 'BENGUET', C: 'CITY OF BAGUIO', B: 'PACDAL' })
  })

  it('lists Benguet first, then the other provinces in their usual order', async () => {
    const user = userEvent.setup()
    render(<Harness stored={{}} />)
    await user.click(screen.getByLabelText('Province'))
    expect((await screen.findAllByRole('option')).map((o) => o.textContent)).toEqual(['Benguet', 'Abra'])
  })

  it('changing the province clears a city and barangay that are not in it', async () => {
    const user = userEvent.setup()
    render(<Harness stored={{ province: 'BENGUET', city: 'LA TRINIDAD', barangay: 'BALILI' }} />)
    await user.click(screen.getByLabelText('Province'))
    await user.click(await screen.findByRole('option', { name: 'Abra' }))
    expect(draft()).toEqual({ P: 'ABRA', C: '', B: '' })
  })

  it('changing the city clears a barangay that is not in it', async () => {
    const user = userEvent.setup()
    render(<Harness stored={{ province: 'BENGUET', city: 'LA TRINIDAD', barangay: 'BALILI' }} />)
    await user.click(await screen.findByDisplayValue('La Trinidad'))
    await user.click(await screen.findByRole('option', { name: 'City of Baguio' }))
    expect(draft()).toEqual({ C: 'CITY OF BAGUIO', B: '' })
  })

  it('keeps a value on file that is not in the list, and says so', async () => {
    render(<Harness stored={{ province: 'MT. PROVINCE', city: 'BONTOC' }} />)
    expect(await screen.findByDisplayValue('MT. PROVINCE')).toBeInTheDocument()
    expect((await screen.findAllByText(/Not in the official list/)).length).toBeGreaterThan(0)
  })

  it('matches values on file in capitals to the list', async () => {
    render(<Harness stored={{ province: 'BENGUET', city: 'LA TRINIDAD', barangay: 'BALILI' }} />)
    expect(await screen.findByDisplayValue('Balili')).toBeInTheDocument()
    expect(screen.getByDisplayValue('La Trinidad')).toBeInTheDocument()
    expect(screen.getByDisplayValue('Benguet')).toBeInTheDocument()
    expect(screen.queryByText(/Not in the official list/)).not.toBeInTheDocument()
  })

  function StreetHarness({ barangay }: { barangay: string }) {
    const [draft, setDraft] = useState<Record<string, string>>({})
    return (
      <QueryClientProvider client={new QueryClient()}>
        <AppThemeProvider>
          <EditCtx.Provider value={{ editing: true, draft, set: (c, v) => setDraft((d) => ({ ...d, [c]: v })) }}>
            <StreetField label="Street Name" col="S" stored="" place={{ cols: { province: 'P', city: 'C', barangay: 'B' }, stored: { province: 'BENGUET', city: 'LA TRINIDAD', barangay } }} />
            <output data-testid="draft">{JSON.stringify(draft)}</output>
          </EditCtx.Provider>
        </AppThemeProvider>
      </QueryClientProvider>
    )
  }

  it('offers the streets already used in the barangay, and still takes any street typed', async () => {
    const user = userEvent.setup()
    suggest.mockClear()
    render(<StreetHarness barangay="BALILI" />)
    await user.click(screen.getByLabelText('Street Name'))
    await user.click(await screen.findByRole('option', { name: 'WAGNER ROAD' }))
    expect(suggest).toHaveBeenCalledWith('BALILI', 'LA TRINIDAD', 'BENGUET')
    expect(draft()).toEqual({ S: 'WAGNER ROAD' })
    await user.clear(screen.getByLabelText('Street Name'))
    await user.type(screen.getByLabelText('Street Name'), 'New Lane')
    expect(draft()).toEqual({ S: 'New Lane' })
  })

  it('asks for nothing until a barangay is chosen', async () => {
    suggest.mockClear()
    render(<StreetHarness barangay="" />)
    await userEvent.setup().click(screen.getByLabelText('Street Name'))
    expect(suggest).not.toHaveBeenCalled()
  })
})
