import katex from 'katex'

function escapeHtml(value: string) {
  const entities: Record<string, string> = {
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#39;',
  }
  return value.replace(/[&<>"']/g, (character) => entities[character] ?? character)
}

function safeUrl(value: string) {
  return /^(https?:|mailto:|#)/i.test(value.trim())
}

function renderMath(source: string, displayMode: boolean) {
  try {
    return katex.renderToString(source.trim(), {
      displayMode,
      output: 'htmlAndMathml',
      throwOnError: false,
    })
  } catch {
    return `<span class="math-error">${escapeHtml(source)}</span>`
  }
}

function renderInline(source: string) {
  const protectedParts: string[] = []
  const protect = (part: string) => {
    const token = `@@MDPART${protectedParts.length}@@`
    protectedParts.push(part)
    return token
  }

  // 先保护代码，避免代码中的 $ 符号被误当成数学公式。
  let value = source.replace(/`([^`\n]+)`/g, (_, code: string) => protect(`<code>${escapeHtml(code)}</code>`))
  value = value.replace(/\\\[([\s\S]*?)\\\]/g, (_, formula: string) => protect(renderMath(formula, true)))
  value = value.replace(/\\\(([\s\S]*?)\\\)/g, (_, formula: string) => protect(renderMath(formula, false)))
  value = value.replace(/\$(?!\$)([^$\n]+?)\$(?!\$)/g, (_, formula: string) => protect(renderMath(formula, false)))
  value = escapeHtml(value)
  value = value.replace(/!?\[([^\]]+)\]\(([^)\s]+)(?:\s+"[^"]*")?\)/g, (match, label: string, url: string) => {
    if (match.startsWith('!') || !safeUrl(url)) return match
    return protect(`<a href="${url}">${label}</a>`)
  })
  value = value.replace(/(\*\*|__)(.+?)\1/g, '<strong>$2</strong>')
  value = value.replace(/~~(.+?)~~/g, '<del>$1</del>')
  value = value.replace(/(?<!\*)\*([^*\n]+)\*(?!\*)/g, '<em>$1</em>')
  value = value.replace(/(?<!_)_([^_\n]+)_(?!_)/g, '<em>$1</em>')

  return value.replace(/@@MDPART(\d+)@@/g, (_, index: string) => protectedParts[Number(index)] ?? '')
}

function isHorizontalRule(line: string) {
  return /^\s{0,3}([-*_])(?:\s*\1){2,}\s*$/.test(line)
}

export function renderMarkdown(source: string) {
  const lines = source.replace(/\r\n?/g, '\n').split('\n')
  const output: string[] = []
  let paragraph: string[] = []
  let index = 0

  const flushParagraph = () => {
    if (!paragraph.length) return
    output.push(`<p>${paragraph.map(renderInline).join('<br />')}</p>`)
    paragraph = []
  }

  while (index < lines.length) {
    const line = lines[index]
    const fence = line.match(/^\s{0,3}```\s*([\w-]*)\s*$/)
    if (fence) {
      flushParagraph()
      index += 1
      const code: string[] = []
      while (index < lines.length && !/^\s{0,3}```\s*$/.test(lines[index])) {
        code.push(lines[index])
        index += 1
      }
      if (index < lines.length) index += 1
      const language = fence[1] ? ` class="language-${escapeHtml(fence[1])}"` : ''
      output.push(`<pre><code${language}>${escapeHtml(code.join('\n'))}</code></pre>`)
      continue
    }

    const singleLineMath = line.match(/^\s{0,3}\$\$([\s\S]+?)\$\$\s*$/)
    if (singleLineMath) {
      flushParagraph()
      output.push(`<div class="math-block">${renderMath(singleLineMath[1], true)}</div>`)
      index += 1
      continue
    }

    if (/^\s{0,3}\$\$\s*$/.test(line)) {
      flushParagraph()
      index += 1
      const formula: string[] = []
      while (index < lines.length && !/^\s{0,3}\$\$\s*$/.test(lines[index])) {
        formula.push(lines[index])
        index += 1
      }
      if (index < lines.length) index += 1
      output.push(`<div class="math-block">${renderMath(formula.join('\n'), true)}</div>`)
      continue
    }

    const heading = line.match(/^\s{0,3}(#{1,6})\s+(.+?)\s*#*\s*$/)
    if (heading) {
      flushParagraph()
      const level = heading[1].length
      output.push(`<h${level}>${renderInline(heading[2])}</h${level}>`)
      index += 1
      continue
    }

    if (isHorizontalRule(line)) {
      flushParagraph()
      output.push('<hr />')
      index += 1
      continue
    }

    if (/^\s{0,3}>\s?/.test(line)) {
      flushParagraph()
      const quote: string[] = []
      while (index < lines.length && /^\s{0,3}>\s?/.test(lines[index])) {
        quote.push(lines[index].replace(/^\s{0,3}>\s?/, ''))
        index += 1
      }
      output.push(`<blockquote>${quote.map(renderInline).join('<br />')}</blockquote>`)
      continue
    }

    const unordered = line.match(/^\s{0,3}[-+*]\s+(.+)$/)
    const ordered = line.match(/^\s{0,3}\d+[.)]\s+(.+)$/)
    if (unordered || ordered) {
      flushParagraph()
      const items: string[] = []
      const pattern = unordered ? /^\s{0,3}[-+*]\s+(.+)$/ : /^\s{0,3}\d+[.)]\s+(.+)$/
      while (index < lines.length) {
        const item = lines[index].match(pattern)
        if (!item) break
        items.push(`<li>${renderInline(item[1])}</li>`)
        index += 1
      }
      output.push(`<${unordered ? 'ul' : 'ol'}>${items.join('')}</${unordered ? 'ul' : 'ol'}>`)
      continue
    }

    if (!line.trim()) flushParagraph()
    else paragraph.push(line)
    index += 1
  }
  flushParagraph()
  return output.join('')
}

export function markdownExcerpt(source: string) {
  return source
    .replace(/```[\s\S]*?```/g, ' ')
    .replace(/\$\$[\s\S]*?\$\$/g, '公式')
    .replace(/\\\[[\s\S]*?\\\]/g, '公式')
    .replace(/\\\([\s\S]*?\\\)/g, '公式')
    .replace(/\$(?!\$)[^$\n]+\$(?!\$)/g, '公式')
    .replace(/!?\[([^\]]+)\]\([^)]+\)/g, '$1')
    .replace(/^\s{0,3}#{1,6}\s+/gm, '')
    .replace(/[>*_~`]/g, '')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, 180)
}
