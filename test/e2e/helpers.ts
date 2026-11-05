import { expect, type Browser, type Page } from '@playwright/test'

// Phones and a household, shared by the end-to-end specs.

export async function phone(browser: Browser, name: string, kind: 'adult' | 'child'): Promise<Page> {
  const context = await browser.newContext()
  const page = await context.newPage()
  page.on('pageerror', (e) => console.log(`[${name}] page error:`, e.message))
  await page.goto('/')
  await page.getByText(kind === 'child' ? "A child's phone" : "Mine: I'm a parent, carer or relative", { exact: true }).click()
  await page.getByLabel('Name shown to your family').fill(name)
  await page.getByText('Just trying it out?').click()
  await page.getByRole('button', { name: 'Make a key on this phone' }).click()
  await expect(page.locator('header.bar')).toContainText(name)
  return page
}

export async function pair(guardian: Page, child: Page, childName: string, tier?: string) {
  await guardian.getByLabel("Child's name").fill(childName)
  if (tier) await guardian.getByText(tier, { exact: true }).click()
  await guardian.getByRole('button', { name: "Pair with a child's phone" }).click()
  const code = await guardian.getByTestId('pairing-code').inputValue()
  expect(code).toMatch(/^kinclude-pair:/)
  await child.getByLabel("Pairing code from your parent or carer's phone").fill(code)
  await child.getByRole('button', { name: 'Pair', exact: true }).click()
  await expect(guardian.getByTestId('child').filter({ hasText: childName })).toBeVisible()
}

export async function household(browser: Browser, startTier: string) {
  const mum = await phone(browser, 'Mum', 'adult')
  const dad = await phone(browser, 'Dad', 'adult')
  const sam = await phone(browser, 'Sam', 'child')
  await pair(mum, sam, 'Sam', startTier)
  await pair(dad, sam, 'Sam')
  const mumKey = (await mum.getByTestId('my-key').textContent())!.trim()
  await dad.getByLabel('Their key').fill(mumKey)
  await dad.getByLabel('Name', { exact: true }).fill('Mum')
  await dad.getByRole('button', { name: 'Add', exact: true }).click()
  await mum.getByLabel('New room name').fill('Home')
  await mum.getByRole('button', { name: 'Make room' }).click()
  await mum.getByRole('button', { name: 'Invite someone' }).click()
  await mum.getByRole('button', { name: 'Add Sam' }).click()
  await mum.getByRole('button', { name: 'Back' }).click()
  for (const g of [mum, dad]) await g.getByTestId('invite').getByRole('button', { name: 'Accept' }).click()
  await sam.getByTestId('invite').getByRole('button', { name: 'Accept' }).click()
  for (const p of [mum, dad, sam]) await p.getByTestId('room-tile').filter({ hasText: 'Home' }).click()
  await expect(sam.getByLabel('Message')).toBeVisible()
  return { mum, dad, sam }
}
