declare module 'markdown-it-texmath' {
  import type MarkdownIt from 'markdown-it'

  interface TexMathOptions {
    engine: unknown
    delimiters?: string | string[]
    katexOptions?: Record<string, unknown>
  }

  const texmath: MarkdownIt.PluginWithOptions<TexMathOptions>
  export default texmath
}
