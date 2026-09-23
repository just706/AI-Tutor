const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')

// Run the production TypeScript without adding a test framework or requiring Node's TS loader.
const source = fs.readFileSync(path.join(__dirname, '../src/utils/graphLabels.ts'), 'utf8')
const compiled = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
}).outputText
const exported = {}
vm.runInNewContext(compiled, { exports: exported })
const { graphNodeLabel, graphLinkLabel } = exported

test('node names from uploaded material are displayed as text, not executable HTML', () => {
  assert.equal(graphNodeLabel({ id: 1, name: '<img src=x onerror=alert(1)>', confidence: 80 }),
    '&lt;img src=x onerror=alert(1)&gt;<br/>Confidence 80%')
})

test('relation labels escape tags, quotes and literal HTML entities', () => {
  assert.equal(graphLinkLabel({ relationReason: '<svg onload="alert(1)"> & \'x\'' }),
    '&lt;svg onload=&quot;alert(1)&quot;&gt; &amp; &#39;x&#39;')
  assert.equal(graphLinkLabel({ relationType: '&lt;script&gt;' }), '&amp;lt;script&amp;gt;')
})

test('normal labels preserve names, confidence, line breaks and fallbacks', () => {
  assert.equal(graphNodeLabel({ id: 7, name: 'ArrayList', confidence: 85 }),
    'ArrayList<br/>Confidence 85%')
  assert.equal(graphNodeLabel({ id: 7 }), '7<br/>Confidence 0%')
  assert.equal(graphLinkLabel({}), 'Related')
})
