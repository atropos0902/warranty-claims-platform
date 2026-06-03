import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';

// Needs the local stack with the NORTH group on the platform.

async function signIn(page: Page, who: string) {
  await page.goto('/claims');
  await page.getByRole('button', { name: who }).click();
  await expect(page.getByRole('heading', { name: 'Claims', exact: true })).toBeVisible();
}

async function file(page: Page, notes: string, repair = 'Wiring harness, repair') {
  await page.getByRole('link', { name: 'File a claim' }).click();
  await page.getByLabel('Dealer').selectOption({ label: 'Prairie Tractor' });
  await page.getByLabel('Model').selectOption('T40');
  await page.getByLabel('Serial number').fill('SN-E2E');
  await page.getByLabel('Purchase date').fill('2017-01-31');
  await page.getByLabel('Failure date').fill('2017-06-30');
  await page.getByLabel('Meter hours').fill('120.5');
  await page.getByLabel('Repair', { exact: true }).selectOption({ label: repair });
  await page.getByLabel('Labor hours').fill('6');
  await page.getByLabel('Part number, line 1').fill('WH-22');
  await page.getByLabel('Unit cost, line 1').fill('100');
  await page.getByLabel('Repair notes').fill(notes);
  await page.getByRole('button', { name: 'File claim' }).click();
  await expect(page.getByRole('heading', { name: /^Claim P/ })).toBeVisible();
  return page.url();
}

async function accessible(page: Page) {
  const found = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze();
  expect(found.violations.map((v) => `${v.id}: ${v.nodes[0]?.html}`)).toEqual([]);
}

test('a dealer files a claim and sees how it was priced', async ({ page }) => {
  await signIn(page, 'Dealer, northern group');
  await accessible(page);
  await page.getByRole('link', { name: 'File a claim' }).click();
  await accessible(page);
  await page.goto('/claims');
  await file(page, 'Harness repaired behind the cab');
  await expect(page.getByText('5.75 of 6 hours paid, cap 5.75')).toBeVisible();
  await expect(page.getByRole('row', { name: /Labor/ })).toContainText('$678.50');
  await accessible(page);
});

test('two people edit the same claim and neither version is lost', async ({ browser }) => {
  const a = await (await browser.newContext()).newPage();
  const b = await (await browser.newContext()).newPage();
  await signIn(a, 'Dealer, northern group');
  const url = await file(a, 'first notes');
  await signIn(b, 'Dealer, northern group');
  await b.goto(url);

  await a.getByRole('button', { name: 'Edit claim' }).click();
  await b.getByRole('button', { name: 'Edit claim' }).click();
  await a.getByLabel('Labor hours').fill('2');
  await a.getByRole('button', { name: 'Save' }).click();
  await expect(a.getByText('2 of 2 hours paid')).toBeVisible();

  await b.getByLabel('Labor hours').fill('4');
  await b.getByRole('button', { name: 'Save' }).click();
  const conflict = b.getByRole('region', { name: 'Someone else changed this claim' });
  await expect(conflict.getByRole('row', { name: 'Labor hours 4 2' })).toBeVisible();
  await accessible(b);

  // A reload in the middle keeps the unfinished version.
  await b.reload();
  await b.getByRole('button', { name: 'Edit claim' }).click();
  await expect(b.getByLabel('Labor hours')).toHaveValue('4');
  await b.getByRole('button', { name: 'Save' }).click();
  await b.getByRole('button', { name: 'Save my version' }).click();
  await expect(b.getByText('4 of 4 hours paid')).toBeVisible();
});

test('an adjuster drafts, reads the clause and decides with the keyboard alone', async ({ browser }) => {
  const dealer = await (await browser.newContext()).newPage();
  await signIn(dealer, 'Dealer, northern group');
  const url = await file(dealer, 'Rodents chewed the wiring harness. Damage from mice and other animals, photographs of the harness kept.');

  const page = await (await browser.newContext()).newPage();
  await signIn(page, 'Adjuster');
  await page.goto(url);
  await expect(page.getByRole('heading', { name: /^Claim P/ })).toBeFocused();
  const tabTo = async (name: string) => {
    for (let i = 0; i < 12; i++) {
      await page.keyboard.press('Tab');
      if (await page.evaluate((n) => (document.activeElement?.textContent ?? '').trim() === n, name)) return;
    }
    throw new Error(`Tab never reached ${name}`);
  };
  await tabTo('Draft a decision');
  await page.keyboard.press('Enter');
  await expect(page.locator('blockquote')).toHaveText(/rodents or other animals is not covered|photographs of the harness/);
  await expect(page.getByText(/Clause 5\.9/)).toBeVisible();
  await accessible(page);
  await page.keyboard.press('Tab');
  await page.keyboard.type('Rodent damage');
  await tabTo('Deny');
  await page.keyboard.press('Enter');
  await expect(page.getByRole('heading', { name: /Denied/ })).toBeVisible();
  await expect(page.getByText('Rodent damage')).toBeVisible();
});

test('nothing is drafted when no clause fits', async ({ browser }) => {
  const dealer = await (await browser.newContext()).newPage();
  await signIn(dealer, 'Dealer, northern group');
  const url = await file(dealer, 'Operator seat squeaks');
  const page = await (await browser.newContext()).newPage();
  await signIn(page, 'Adjuster');
  await page.goto(url);
  await page.getByRole('button', { name: 'Draft a decision' }).click();
  await expect(page.getByText('No supporting clause found')).toBeVisible();
  await expect(page.locator('blockquote')).toHaveCount(0);
});

test('a group on the old system is told so', async ({ page }) => {
  await signIn(page, 'Dealer, southern group');
  await page.getByRole('link', { name: 'File a claim' }).click();
  await page.getByLabel('Dealer').selectOption({ label: 'Gulf Farm Machinery' });
  await page.getByLabel('Model').selectOption('T90');
  await page.getByLabel('Serial number').fill('SN-S');
  await page.getByLabel('Purchase date').fill('2017-01-31');
  await page.getByLabel('Failure date').fill('2017-06-30');
  await page.getByLabel('Repair', { exact: true }).selectOption({ label: 'Fuel injector, replace' });
  await page.getByLabel('Labor hours').fill('1');
  await page.getByRole('button', { name: 'File claim' }).click();
  await expect(page.getByRole('alert')).toContainText('still files claims in the old system');
});
