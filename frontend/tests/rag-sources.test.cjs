const assert = require('node:assert/strict')
const { test } = require('node:test')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const { parse, compileScript } = require('@vue/compiler-sfc')
const { createSSRApp } = require('vue')
const { renderToString } = require('@vue/server-renderer')

function sourceComponent() {
  const filename = path.join(__dirname, '../src/components/RagSources.vue')
  const { descriptor } = parse(fs.readFileSync(filename, 'utf8'), { filename })
  const script = compileScript(descriptor, { id: 'rag-source-test', inlineTemplate: true })
  const code = ts.transpileModule(script.content, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
  const context = { exports: {}, require }
  vm.runInNewContext(code, context)
  return context.exports.default
}

test('citation labels follow answer order and show the distinct textbook position', async () => {
  const html = await renderToString(createSSRApp(sourceComponent(), { sources: [
    { documentId: 21, fileName: 'Java.txt', chunkIndex: 7, snippet: '原始教材全文', available: true },
    { documentId: 21, fileName: 'Java.txt', chunkIndex: 2, snippet: '第二份依据', available: true }
  ] }))
  assert.match(html, /片段 1/)
  assert.match(html, /教材第 8 段/)
  assert.match(html, /片段 2/)
  assert.match(html, /教材第 3 段/)
  assert(html.indexOf('原始教材全文') < html.indexOf('第二份依据'))
})

test('unavailable evidence displays a warning and never renders snapshot text', async () => {
  const html = await renderToString(createSSRApp(sourceComponent(), { sources: [
    { documentId: 21, fileName: '不可暴露的文件名', chunkIndex: 7, snippet: '不可暴露的原文', available: false }
  ] }))
  assert.match(html, /教材已删除或暂不可访问/)
  assert(!html.includes('不可暴露的文件名'))
  assert(!html.includes('不可暴露的原文'))
})

test('textbook markup stays escaped and answers without sources show no citation panel', async () => {
  const html = await renderToString(createSSRApp(sourceComponent(), { sources: [
    { documentId: 21, fileName: '<img src=x onerror=alert(1)>', chunkIndex: 0, snippet: '<script>alert(1)</script>', available: true }
  ] }))
  assert(!html.includes('<script>'))
  assert(!html.includes('<img'))
  assert(html.includes('&lt;script&gt;'))
  const empty = await renderToString(createSSRApp(sourceComponent(), { sources: [] }))
  assert(!empty.includes('<details'))
})
