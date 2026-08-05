import MarkdownIt from 'markdown-it'
import katex from 'katex'
import texmath from 'markdown-it-texmath'

const markdown = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: true
})

markdown.use(texmath, {
  engine: katex,
  delimiters: ['dollars', 'brackets', 'beg_end'],
  katexOptions: {
    throwOnError: false,
    strict: false
  }
})

export function renderMarkdown(content?: string) {
  return markdown.render(normalizeMathBlocks(content || ''))
}

function normalizeMathBlocks(content: string) {
  return content
    .replace(/\\\[([\s\S]*?)\\\]/g, (_match, formula: string) => toDisplayMath(formula))
    .replace(/(^|\n)\[\s*\n([\s\S]*?\\(?:frac|sum|int|sqrt|begin|cdot|quad|Rightarrow|Leftarrow|le|ge|times)[\s\S]*?)\n\](?=\n|$)/g,
      (_match, prefix: string, formula: string) => `${prefix}${toDisplayMath(formula)}`)
}

function toDisplayMath(formula: string) {
  return `\n\n$$\n${formula.trim()}\n$$\n\n`
}
