const assert = require('node:assert/strict')
const fs = require('node:fs/promises')
const path = require('node:path')
const { chromium } = require(require.resolve('playwright', {
  paths: [path.resolve(__dirname, '../../backend/target/browser-tools'), path.resolve(__dirname, '..')]
}))

// Requires verify-stage0.mjs --teacher-model stub --hold-for-browser.
async function main() {
  const output = path.resolve(process.argv[2] || '')
  assert(process.argv[2], 'Provide the isolated verification output directory')
  const fixture = JSON.parse(await fs.readFile(path.join(output, 'browser-fixture.json'), 'utf8'))
  const apiURL = new URL(fixture.apiBase)
  assert(['127.0.0.1', 'localhost'].includes(apiURL.hostname) && apiURL.port && apiURL.port !== '8080')
  const suite = JSON.parse(await fs.readFile(path.resolve(__dirname, '../../backend/evaluation/stage1-cases.json'), 'utf8'))
  const { createServer } = await import('../node_modules/vite/dist/node/index.js')
  const { default: vue } = await import('../node_modules/@vitejs/plugin-vue/dist/index.mjs')
  const server = await createServer({ root: path.resolve(__dirname, '..'), configFile: false, plugins: [vue()],
    server: { host: '127.0.0.1', port: 5186, strictPort: false,
      proxy: { '/api': { target: apiURL.origin, changeOrigin: true } } } })
  const checks = [], pageErrors = [], submitted = []
  let browser, failure, releaseResponse
  try {
    await server.listen()
    const origin = `http://127.0.0.1:${server.httpServer.address().port}`
    browser = await chromium.launch({
      executablePath: process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true
    })
    const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, serviceWorkers: 'block' })
    const page = await context.newPage()
    page.setDefaultTimeout(20000)
    page.on('pageerror', error => pageErrors.push(error.message))
    page.on('request', request => {
      if (request.method() === 'POST' && new URL(request.url()).pathname === '/api/ai/rag/chat') submitted.push(request.postDataJSON())
    })
    await page.goto(`${origin}/login`)
    await page.getByRole('textbox').first().fill(fixture.flowBrowser.username)
    await page.locator('input[type=password]').fill(fixture.flowBrowser.password)
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await page.waitForURL('**/chat')
    const token = await page.evaluate(() => localStorage.getItem('ai_tutor_token'))
    assert(token)
    const api = async (endpoint, method = 'GET', data) => {
      const response = await context.request.fetch(fixture.apiBase + endpoint,
        { method, data, headers: { Authorization: `Bearer ${token}` } })
      const result = await response.json()
      assert.equal(result.code, 200, `${method} ${endpoint}: ${result.message}`)
      return result.data
    }
    const upload = async (name, source) => {
      const response = await context.request.post(fixture.apiBase + '/documents/upload', {
        headers: { Authorization: `Bearer ${token}` },
        multipart: { file: { name, mimeType: 'text/plain', buffer: Buffer.from(source) } }
      })
      const result = await response.json()
      assert.equal(result.code, 200)
      assert.equal(result.data.processStatus, 'completed')
      return result.data.documentId
    }
    const mathId = await upload('stage4-recovery-math.txt', suite.materials.math)
    const physicsId = await upload('stage4-recovery-physics.txt', suite.materials.physics)
    const create = async (title, documentId = mathId) =>
      (await api('/conversations', 'POST', { title, mode: 'rag', documentIds: [documentId] })).conversationId
    const conversationId = await create('阶段四故障恢复')
    const physicsConversationId = await create('阶段四物理隔离', physicsId)
    const history = id => api(`/conversations/${id}/messages`)
    const open = async id => {
      await page.goto(`${origin}/library?conversationId=${id}`)
      await page.getByRole('button', { name: '在主聊天继续', exact: true }).waitFor()
      await page.waitForFunction(() => !document.querySelector('.rag-composer button')?.disabled)
    }
    const send = async question => {
      await page.locator('.rag-composer textarea').fill(question)
      await page.getByRole('button', { name: '基于资料回答', exact: true }).click()
    }
    const waitCompleted = async () => {
      await page.locator('.rag-history .message.assistant').last().waitFor()
      await page.waitForFunction(() => !document.querySelector('.rag-retry') && !document.querySelector('.rag-composer button')?.disabled)
    }
    const screenshot = filename => page.screenshot({ path: path.join(output, filename), fullPage: true, animations: 'disabled' })

    await open(conversationId)
    await fs.writeFile(fixture.browserFailureControl, 'fail one local model request')
    const beforeFailure = submitted.length
    await send('什么是导数？')
    await page.getByRole('button', { name: '重试回答', exact: true }).waitFor()
    let saved = await history(conversationId)
    assert.equal(saved.length, 1)
    assert.equal(saved[0].ragStatus, 'failed')
    assert.equal(saved[0].ragAttempt, 1)
    assert.equal(submitted.length - beforeFailure, 1)
    const requestId = saved[0].ragRequestId
    checks.push('Local model outage preserves exactly one failed question without automatic POST retry')
    await screenshot('failure-desktop.png')

    await page.reload()
    await page.getByRole('button', { name: '重试回答', exact: true }).waitFor()
    saved = await history(conversationId)
    assert.equal(saved[0].ragRequestId, requestId)
    assert.equal(submitted.length - beforeFailure, 1)
    checks.push('Reload restores failed question and retry control without submitting another request')
    await page.setViewportSize({ width: 360, height: 800 })
    const retryFits = await page.locator('.rag-retry button').evaluate(button => {
      const bounds = button.closest('.library-qa').getBoundingClientRect()
      const box = button.getBoundingClientRect()
      return box.left >= bounds.left && box.right <= bounds.right && button.scrollWidth <= button.clientWidth + 1
    })
    assert(retryFits)
    await screenshot('failure-360.png')
    checks.push('360px failure message and retry button remain inside the question panel')
    await page.setViewportSize({ width: 1440, height: 900 })

    for (const filename of ['stage4-recovery-physics.txt', 'stage4-recovery-math.txt']) {
      const response = page.waitForResponse(r => r.request().method() === 'PUT' && r.url().endsWith(`/conversations/${conversationId}/documents`))
      await page.locator('.source-list label.el-checkbox').filter({ hasText: filename }).click()
      assert.equal((await (await response).json()).code, 200)
      await page.waitForFunction(() => !document.querySelector('.rag-composer button')?.disabled)
    }
    await send('牛顿第二定律是什么？')
    await page.waitForFunction(() => !document.querySelector('.rag-composer button')?.disabled)
    await page.getByRole('button', { name: '重试回答', exact: true }).click()
    await waitCompleted()
    saved = await history(conversationId)
    const pair = saved.filter(message => message.ragRequestId === requestId)
    assert.equal(pair.length, 2)
    assert.equal(pair[0].ragAttempt, 2)
    assert.equal(pair[0].ragStatus, 'completed')
    assert(pair[1].sources.length > 0 && pair[1].sources.every(source => source.documentId === mathId))
    assert.equal(saved[0].messageContent, '什么是导数？')
    assert.equal(saved[2].messageContent, '牛顿第二定律是什么？')
    assert.deepEqual((await api(`/conversations/${conversationId}`)).documentIds, [physicsId])
    checks.push('Retry reuses original request and math sources after switching current textbook to physics')
    await page.reload()
    await page.locator('.rag-history .message.assistant').last().waitFor()
    assert.equal(await page.locator('.rag-history .message').count(), 4)
    await screenshot('retry-recovered.png')
    checks.push('Successful retry and both source snapshots survive reload without duplicate questions')

    const lostId = await create('阶段四响应丢失')
    await open(lostId)
    const lostStart = submitted.length
    await page.route('**/api/ai/rag/chat', async route => {
      const response = await route.fetch()
      assert.equal((await response.json()).code, 200)
      await route.abort('failed')
    })
    await send('什么是导数？')
    await waitCompleted()
    await page.unroute('**/api/ai/rag/chat')
    saved = await history(lostId)
    assert.equal(saved.length, 2)
    assert.equal(saved[0].ragAttempt, 1)
    assert.equal(submitted.length - lostStart, 1)
    checks.push('Lost response after backend success recovers saved answer without another POST')

    const unsentId = await create('阶段四请求未送达')
    await open(unsentId)
    const unsentStart = submitted.length
    await page.route('**/api/ai/rag/chat', route => route.abort('failed'))
    await page.route(`**/api/conversations/${unsentId}/messages`, route => route.abort('failed'))
    await send('什么是导数？')
    await page.getByRole('button', { name: '检查回答', exact: true }).waitFor()
    const unsent = submitted.at(-1)
    assert.equal((await history(unsentId)).length, 0)
    await screenshot('uncertain-request.png')
    await page.unroute('**/api/ai/rag/chat')
    await page.unroute(`**/api/conversations/${unsentId}/messages`)
    await page.getByRole('button', { name: '检查回答', exact: true }).click()
    await waitCompleted()
    saved = await history(unsentId)
    assert.equal(saved.length, 2)
    assert.equal(saved[0].ragRequestId, unsent.requestId)
    assert.equal(saved[0].ragAttempt, 1)
    assert.equal(submitted.length - unsentStart, 2)
    checks.push('Unsent request remains uncertain and manual recovery keeps same key and attempt')

    const lateId = await create('阶段四延迟响应')
    await open(lateId)
    let markReady
    const ready = new Promise(resolve => { markReady = resolve })
    const release = new Promise(resolve => { releaseResponse = resolve })
    await page.route('**/api/ai/rag/chat', async route => {
      const response = await route.fetch()
      assert.equal((await response.json()).code, 200)
      markReady()
      await release
      await route.fulfill({ response })
    })
    await send('导数有什么用途？')
    await Promise.race([ready, new Promise((_, reject) => {
      const timer = setTimeout(() => reject(new Error('Delayed backend response not ready')), 20000)
      timer.unref()
    })])
    await page.locator('.desktop-navigation .sidebar-conversation-item').filter({ hasText: '阶段四物理隔离' }).click()
    await page.waitForURL(`**/chat?conversationId=${physicsConversationId}`)
    releaseResponse()
    await page.waitForFunction(() => !document.querySelector('.chat-main button.is-loading'))
    assert(!(await page.locator('.chat-main').innerText()).includes('导数有什么用途？'))
    assert.equal((await history(physicsConversationId)).length, 0)
    await page.unroute('**/api/ai/rag/chat')
    checks.push('Late math reply cannot appear in a different selected conversation')
    await open(lateId)
    await page.locator('.rag-history .message.assistant').waitFor()
    assert.equal(await page.locator('.rag-history .message').count(), 2)
    checks.push('Returning to original conversation restores the late answer once')

    await api(`/documents/${mathId}`, 'DELETE')
    await page.reload()
    await page.getByText('部分已选教材不可用，请取消选择或等待资料处理完成。', { exact: true }).waitFor()
    assert(await page.getByRole('button', { name: '基于资料回答', exact: true }).isDisabled())
    saved = await history(lateId)
    assert(saved[1].sources.every(source => source.available === false))
    await page.locator('.rag-history .message-sources summary').click()
    await page.locator('.rag-history .source-unavailable').waitFor()
    assert(!(await page.locator('.rag-history .message-sources').innerText()).includes(suite.materials.math))
    await screenshot('deleted-source.png')
    checks.push('Deleted source disables new questions and marks historical citation unavailable')
    assert.deepEqual((await api(`/conversations/${physicsConversationId}`)).documentIds, [physicsId])
    assert.deepEqual(pageErrors, [])
    checks.push('Physics binding remains intact and no page script errors occurred')
  } catch (error) {
    failure = error
  } finally {
    releaseResponse?.()
    await browser?.close()
    await server.close()
    await fs.copyFile(__filename, path.join(output, 'recovery-browser-source.cjs'))
    await fs.writeFile(path.join(output, 'recovery-browser-result.json'), JSON.stringify({
      passed: !failure, checks, pageErrors, submittedRequestCount: submitted.length,
      realModelCalls: 0, modelMode: 'local_stub', error: failure?.message
    }, null, 2))
    await fs.writeFile(fixture.finishPath, failure ? 'recovery browser failed' : 'recovery browser finished')
  }
  if (failure) throw failure
  console.log(JSON.stringify({ passed: true, checks: checks.length, realModelCalls: 0, output }))
}

main().catch(error => { console.error(error); process.exitCode = 1 })
