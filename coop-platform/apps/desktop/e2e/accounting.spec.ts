import { expect, test, type Page } from '@playwright/test'

// Signs in as an administrator or accountant (E2E_USER / E2E_PASSWORD). Read-only: nothing is created, approved or posted.
const user = process.env.E2E_USER
const password = process.env.E2E_PASSWORD
test.skip(!user || !password, 'Set E2E_USER and E2E_PASSWORD to run the signed-in tests')

async function openAccounting(page: Page) {
  await page.goto('/')
  await page.getByLabel('Username').fill(user!)
  await page.getByLabel('Password').fill(password!)
  await page.getByRole('button', { name: 'Sign in' }).click()
  await page.getByRole('button', { name: 'Reports' }).click()
  await expect(page.getByRole('tab', { name: 'Trial balance' })).toBeVisible()
}

test.describe('accounting', () => {
  test('shows every tab to an administrator', async ({ page }) => {
    await openAccounting(page)
    for (const name of ['Statements', 'Journals', 'Periods']) await expect(page.getByRole('tab', { name })).toBeVisible()
  })

  test('statement of operations lists revenues and expenses', async ({ page }) => {
    await openAccounting(page)
    await page.getByRole('tab', { name: 'Statements' }).click()
    await page.getByRole('combobox', { name: 'Statement' }).click()
    await page.getByRole('option', { name: 'Statement of Operations' }).click()
    await expect(page.getByRole('listbox')).toBeHidden()
    await page.getByLabel('From', { exact: true }).fill('2025-06-01')
    await page.getByLabel('To', { exact: true }).fill('2025-06-30')
    await expect(page.getByRole('cell', { name: 'REVENUES', exact: true })).toBeVisible()
    await expect(page.getByRole('cell', { name: 'Net surplus' })).toBeVisible()
  })

  test('banks list loads, shows entries and opens the reconciliation sheet', async ({ page }) => {
    await openAccounting(page)
    await page.getByRole('tab', { name: 'Banks' }).click()
    await expect(page.getByRole('table', { name: 'Bank accounts' })).toBeVisible()
    await page.getByRole('button', { name: 'Entries' }).first().click()
    await expect(page.getByRole('table', { name: 'Bank entries' })).toBeVisible()
    await page.getByRole('button', { name: 'Close' }).click()
    await page.getByRole('button', { name: 'Reconcile' }).first().click()
    await expect(page.getByRole('table', { name: 'Reconciliation totals' })).toBeVisible()
    await expect(page.getByText('Difference')).toBeVisible()
    await page.getByRole('button', { name: 'Close' }).click()
  })

  test('journals and periods lists load', async ({ page }) => {
    await openAccounting(page)
    await page.getByRole('tab', { name: 'Journals' }).click()
    await expect(page.getByRole('table', { name: 'Journals' })).toBeVisible()
    await page.getByRole('tab', { name: 'Periods' }).click()
    await expect(page.getByRole('table', { name: 'Accounting periods' })).toBeVisible()
  })
})
