import { expect, test, type Page } from '@playwright/test'

// These tests sign in as a real person, so they need E2E_USER and E2E_PASSWORD. Use the head cashier to see every part of the
// Cash Handover tab. They only read: nothing is posted or changed.
const user = process.env.E2E_USER
const password = process.env.E2E_PASSWORD
test.skip(!user || !password, 'Set E2E_USER and E2E_PASSWORD to run the signed-in tests')

async function signIn(page: Page) {
  await page.goto('/')
  await page.getByLabel('Username').fill(user!)
  await page.getByLabel('Password').fill(password!)
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page.getByRole('button', { name: 'Front Office' })).toBeVisible()
}

async function openFrontOffice(page: Page) {
  await page.getByRole('button', { name: 'Front Office' }).click()
  await expect(page.getByRole('tablist', { name: 'Transaction kinds' })).toBeVisible()
}

async function openHandover(page: Page) {
  await openFrontOffice(page)
  await page.getByRole('tab', { name: /Cash Handover/ }).click()
  await expect(page.getByText('My drawer')).toBeVisible()
}

test.describe('front office', () => {
  test('opens from the dashboard with the expected tabs', async ({ page }) => {
    await signIn(page)
    await openFrontOffice(page)
    for (const name of ['Summary', 'Receipt', 'Withdrawal', 'Cash Handover', 'Bank Trans']) {
      await expect(page.getByRole('tab', { name: new RegExp('^' + name) })).toBeVisible()
    }
    // The old separate Transfer From / Transfer To tabs are now one.
    await expect(page.getByRole('tab', { name: 'Transfer From' })).toHaveCount(0)
  })

  test('Ctrl+number switches tabs', async ({ page }) => {
    await signIn(page)
    await openFrontOffice(page)
    const labels = await page.getByRole('tab').allInnerTexts()
    const index = labels.findIndex((l) => l.includes('Cash Handover'))
    expect(index).toBeGreaterThanOrEqual(0)
    expect(index).toBeLessThan(9)
    await page.keyboard.press(`Control+${index + 1}`)
    await expect(page.getByRole('tab', { name: /Cash Handover/ })).toHaveAttribute('aria-selected', 'true')
    await page.keyboard.press('Control+1')
    await expect(page.getByRole('tab', { name: /Summary/ })).toHaveAttribute('aria-selected', 'true')
  })

  test('summary shows the totals and a blind drawer count', async ({ page }) => {
    await signIn(page)
    await openFrontOffice(page)
    await page.getByRole('tab', { name: /Summary/ }).click()
    await expect(page.getByRole('heading', { name: /TELLER.S \/ CASHIER.S TOTALS/ })).toBeVisible()
    await expect(page.getByText('Count the drawer')).toBeVisible()
    // Blind: the ledger's figure is not on screen before the count is submitted.
    await expect(page.getByText('Should be')).toHaveCount(0)
    await expect(page.getByText(/The expected amount is shown after you submit/)).toBeVisible()
    await expect(page.getByRole('button', { name: /Submit count|Count again/ })).toBeDisabled() // nothing counted yet
  })

  test('cash handover shows the drawer, every drawer for the head cashier, and today\'s hand-offs', async ({ page }) => {
    await signIn(page)
    await openHandover(page)
    await expect(page.getByText('Give cash').or(page.getByText('Take cash from the vault'))).toBeVisible()
    // The first load of all drawers can take a while on a cold start.
    await expect(page.getByText('All drawers (end of day check)')).toBeVisible()
    await expect(page.getByRole('table', { name: 'All drawers' }).getByText('Vault 99')).toBeVisible({ timeout: 45_000 })
    await expect(page.getByRole('table', { name: 'Hand-offs today' })).toBeVisible()
  })

  test('the count grid moves with Enter and totals the notes and coins', async ({ page }) => {
    await signIn(page)
    await openHandover(page)
    const thousand = page.getByLabel('Bill 1,000.00 count').first()
    await thousand.fill('2')
    await thousand.press('Enter')
    await expect(page.getByLabel('Bill 500.00 count').first()).toBeFocused()
    await page.getByLabel('Bill 500.00 count').first().fill('1')
    await expect(page.getByText(/Total\s*2,500\.00|2,500\.00/).first()).toBeVisible()
  })

  test('a hand-off slip opens with its details', async ({ page }) => {
    await signIn(page)
    await openHandover(page)
    const slip = page.getByRole('table', { name: 'Hand-offs today' }).getByRole('button', { name: /^Slip for/ }).first()
    test.skip(!(await slip.isVisible({ timeout: 20_000 }).catch(() => false)), 'No hand-offs today to open a slip for')
    await slip.click()
    await expect(page.getByText('CASH HAND-OFF')).toBeVisible()
    await expect(page.getByText('Document no.')).toBeVisible()
    await expect(page.getByRole('button', { name: 'Print' })).toBeEnabled()
    await page.getByRole('button', { name: 'Close' }).click()
    await expect(page.getByText('CASH HAND-OFF')).toHaveCount(0)
  })

  test('the drawer limit dialog opens and cancels without saving', async ({ page }) => {
    await signIn(page)
    await openHandover(page)
    const teller = page.getByRole('button', { name: 'Teller limit' })
    test.skip(!(await teller.isVisible({ timeout: 45_000 }).catch(() => false)), 'Only the head cashier sets limits')
    await teller.click()
    await expect(page.getByRole('dialog', { name: 'Cash limit' })).toBeVisible()
    await page.getByRole('button', { name: 'Cancel' }).click()
    await expect(page.getByRole('dialog', { name: 'Cash limit' })).toHaveCount(0)
  })

  test('the templates menu opens', async ({ page }) => {
    await signIn(page)
    await openFrontOffice(page)
    await page.getByRole('tab', { name: /^Receipt/ }).click()
    await page.getByRole('button', { name: 'Templates' }).click()
    await expect(page.getByRole('menuitem', { name: /Save this draft as a template/ })).toBeVisible()
    await page.keyboard.press('Escape')
  })
})

test.describe('idle lock', () => {
  test('locks after ten idle minutes and unlocks only with the right password', async ({ page }) => {
    await page.clock.install()
    await signIn(page)
    await page.clock.fastForward('11:00')
    const lock = page.getByRole('dialog', { name: 'Screen locked' })
    await expect(lock).toBeVisible()
    // Nothing behind it can be reached while locked.
    await expect(page.getByRole('button', { name: 'Front Office' })).toBeHidden()

    await lock.getByLabel('Password').fill('wrong-password-for-the-test')
    await lock.getByRole('button', { name: 'Unlock' }).click()
    await expect(lock.getByRole('alert')).toBeVisible()
    await expect(lock).toBeVisible()

    await lock.getByLabel('Password').fill(password!)
    await lock.getByRole('button', { name: 'Unlock' }).click()
    await expect(lock).toBeHidden()
    await expect(page.getByRole('button', { name: 'Front Office' })).toBeVisible()
  })
})
