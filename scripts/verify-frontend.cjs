// Run only against the isolated preview, with Playwright available via NODE_PATH.
const {chromium} = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');

(async () => {
  const browser = await chromium.launch({headless: true, channel: 'chrome'});
  const errors = [];
  const page = await browser.newPage({viewport: {width: 1440, height: 900}});
  page.on('pageerror', error => errors.push(error.message));
  const username = `e2e-${Date.now()}`;
  const title = `E2E ${Date.now()} <script>plain text</script>`;
  async function login(target, name, password) {
    await target.goto('http://127.0.0.1:18081');
    await target.locator('#login-form [name=username]').fill(name);
    await target.locator('#login-form [name=password]').fill(password);
    await target.locator('#login-form button').click();
    await target.locator('#workspace').waitFor({state: 'visible'});
  }
  async function save(target) {
    await target.locator('#save').click();
    await target.locator('#editor').waitFor({state: 'hidden'});
  }
  async function logout(target) {
    await target.locator('#logout').click();
    await target.locator('#login-view').waitFor({state: 'visible'});
  }
  try {
    await login(page, 'alice', 'test-alice-password');
    await page.locator('#create').click();
    await page.locator('#fields [name=title]').fill(title);
    await page.locator('#fields [name=description]').fill('Browser workflow fixture');
    await save(page);
    await page.getByRole('button', {name: title, exact: true}).waitFor();
    await logout(page);
    await login(page, 'agent1', 'test-agent1-password');
    await page.getByRole('button', {name: title, exact: true}).click();
    await page.locator('#editor').waitFor({state: 'visible'});
    assert.equal(await page.locator('#save').innerText(), '接单');
    await save(page);
    await page.waitForFunction(t => [...document.querySelectorAll('tbody tr')].some(r => r.textContent.includes(t) && r.textContent.includes('处理中')), title);
    await page.getByRole('button', {name: title, exact: true}).click();
    await page.locator('#editor').waitFor({state: 'visible'});
    assert.equal(await page.locator('#save').innerText(), '完成工单');
    await save(page);
    await page.waitForFunction(t => [...document.querySelectorAll('tbody tr')].some(r => r.textContent.includes(t) && r.textContent.includes('已完成')), title);
    await logout(page);
    await login(page, 'admin', 'test-admin-password');
    await page.locator('#create').click();
    await page.locator('#fields [name=username]').fill(username);
    await page.locator('#fields [name=password]').fill('e2e-initial-password');
    await save(page);
    const row = page.locator('tr').filter({hasText: username});
    await row.waitFor();
    const user = await browser.newPage();
    user.on('pageerror', error => errors.push(error.message));
    await login(user, username, 'e2e-initial-password');
    await row.getByRole('button', {name: '禁用', exact: true}).click();
    await save(page);
    await row.getByRole('button', {name: '启用', exact: true}).click();
    await save(page);
    await user.locator('#refresh').click();
    await user.locator('#login-view').waitFor({state: 'visible'});
    await login(user, username, 'e2e-initial-password');
    await row.getByRole('button', {name: '重置密码'}).click();
    await page.locator('#fields [name=password]').fill('e2e-replacement-password');
    await save(page);
    await page.waitForFunction(() => document.querySelector('#fields').children.length === 0);
    await user.locator('#refresh').click();
    await user.locator('#login-view').waitFor({state: 'visible'});
    await login(user, username, 'e2e-replacement-password');
    await page.locator('#size').selectOption('20');
    await page.waitForFunction(() => document.querySelectorAll('tbody tr').length === 20);
    fs.mkdirSync('target/frontend-acceptance', {recursive: true});
    await page.screenshot({path: 'target/frontend-acceptance/desktop.png'});
    await page.setViewportSize({width: 390, height: 844});
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
    await page.screenshot({path: 'target/frontend-acceptance/mobile.png'});
    assert.deepEqual(errors, []);
    fs.writeFileSync('target/frontend-acceptance/result.json', JSON.stringify({status: 'passed', username, title, errors}, null, 2));
    console.log('Frontend E2E passed: ticket workflow, escaped text, admin CRUD, session revocation, pagination, desktop/mobile.');
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
