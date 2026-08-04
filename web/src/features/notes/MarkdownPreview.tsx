import { renderMarkdown } from './markdown'
import 'katex/dist/katex.min.css'

export function MarkdownPreview({ value }: { value: string }) {
  if (!value.trim()) return <div className="markdown-preview markdown-empty">暂无内容</div>
  return <div className="markdown-preview" dangerouslySetInnerHTML={{ __html: renderMarkdown(value) }} />
}
