const assert = require('node:assert/strict')
const fs = require('node:fs/promises')
const path = require('node:path')
const { chromium } = require(require.resolve('playwright', {
  paths: [path.resolve(__dirname, '../../backend/target/browser-tools'), path.resolve(__dirname, '..')]
}))

async function main() {
  const output = path.resolve(process.argv[2] || '')
  assert(process.argv[2])
  const fixture = JSON.parse(await fs.readFile(path.join(output, 'browser-fixture.json'), 'utf8'))
  const backend = new URL(fixture.apiBase)
  assert.equal(backend.hostname, '127.0.0.1')
  assert.notEqual(backend.port, '8080')
  const { createServer } = await import('../node_modules/vite/dist/node/index.js')
  const { default: vue } = await import('../node_modules/@vitejs/plugin-vue/dist/index.mjs')
  const server = await createServer({ root: path.resolve(__dirname, '..'), configFile: false, plugins: [vue()],
    server: { host: '127.0.0.1', port: 0, proxy: { '/api': { target: backend.origin, changeOrigin: true } } } })
  let browser, failure
  const checks = [], errors = [], submitted = []
  try {
    await server.listen()
    const origin = `http://127.0.0.1:${server.httpServer.address().port}`
    browser = await chromium.launch({ executablePath: process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true })
    const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, serviceWorkers: 'block' })
    const page = await context.newPage()
    page.setDefaultTimeout(20000)
    page.on('pageerror', error => errors.push(error.message))
    page.on('request', request => {
      if (request.method() === 'POST' && new URL(request.url()).pathname === '/api/ai/rag/chat') submitted.push(request.postDataJSON())
    })
    await page.goto(origin + '/login')
    await page.getByRole('textbox').first().fill(fixture.username)
    await page.locator('input[type=password]').fill(fixture.password)
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await page.waitForURL('**/chat')
    const token = await page.evaluate(() => localStorage.getItem('ai_tutor_token'))
    const created = await context.request.post(fixture.apiBase + '/conversations', {
      headers: { Authorization: `Bearer ${token}` }, data: { title: 'Python网页接入验证', mode: 'rag', documentIds: [fixture.documentId] }
    })
    const data = await created.json(); assert.equal(data.code, 200)
    const id = data.data.conversationId
    await page.goto(`${origin}/library?conversationId=${id}`)
    await page.getByRole('button', { name: '在主聊天继续', exact: true }).click()
    await page.waitForURL('**/chat**')
    await page.locator('.composer textarea').fill('导数是什么？')
    await page.getByRole('button', { name: '发送', exact: true }).click()
    await page.locator('.message.assistant').filter({ hasText: '导数表示函数的局部变化率。' }).waitFor()
    assert.equal(submitted.length, 1)
    assert.equal(submitted[0].conversationId, id)
    checks.push('主聊天发送教材问题，经Java与Python返回回答')
    await page.locator('.message-sources summary').click()
    assert((await page.locator('.message-sources').innerText()).includes('导数表示函数的局部变化率。'))
    await page.screenshot({ path: path.join(output, 'python-agent-chat-desktop.png'), fullPage: true })
    checks.push('展示Java原文引用')
    await page.reload()
    await page.locator('.message.assistant').filter({ hasText: '导数表示函数的局部变化率。' }).waitFor()
    assert.equal(await page.locator('.message.user').count(), 1)
    assert.equal(await page.locator('.message.assistant').count(), 1)
    assert.equal(submitted.length, 1)
    checks.push('刷新恢复一问一答，不重新调用Agent')
    await page.setViewportSize({ width: 390, height: 844 })
    await page.locator('.message-sources summary').click()
    await page.screenshot({ path: path.join(output, 'python-agent-chat-mobile.png'), fullPage: true })
    checks.push('移动视口恢复回答和引用')
    assert.deepEqual(errors, [])
  } catch (error) { failure = { name: error.name, message: error.message }; throw error }
  finally {
    await browser?.close()
    await server.close()
    await fs.writeFile(path.join(output, 'browser-result.json'), JSON.stringify({ passed: !failure, checks, errors, failure }, null, 2))
  }
}
main().catch(error => { console.error(error); process.exitCode = 1 })
