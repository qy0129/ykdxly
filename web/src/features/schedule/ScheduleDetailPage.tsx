import { useCallback, useEffect, useRef, useState } from 'react'
import { BookOpen, Check, CheckCircle2, ChevronDown, ChevronLeft, ChevronRight, ExternalLink, Image, LoaderCircle, NotebookPen, PenLine, Play, Plus, RefreshCw, Save, Send, Sparkles, X } from 'lucide-react'
import type { CalendarItem, Note, Plan, SourceMaterial } from '../../types/planner'
import { plannerApi } from '../../services/plannerApi'
import { MarkdownPreview } from '../notes/MarkdownPreview'

function fallbackMaterials(item: CalendarItem, plan?: Plan): SourceMaterial[] {
  const query = [plan?.title, item.title].filter(Boolean).join(' ').trim() || item.title
  const encodedQuery = encodeURIComponent(query)
  return [
    { id: `bilibili-search-${item.id}`, kind: 'platform', source: '哔哩哔哩', title: `搜索：${query}`, summary: '打开哔哩哔哩搜索相关视频、方法和经验分享。', meta: '平台入口 · 可选', url: `https://search.bilibili.com/all?keyword=${encodedQuery}`, color: '#b85f42' },
    { id: `xiaohongshu-search-${item.id}`, kind: 'platform', source: '小红书', title: `搜索：${query}`, summary: '打开小红书搜索相关经验、攻略和实践分享。', meta: '平台入口 · 可选', url: `https://www.xiaohongshu.com/search_result?keyword=${encodedQuery}`, color: '#d77d55' },
  ]
}

function materialNote(noteItems: Note[], material: SourceMaterial) {
  return noteItems.find((note) => (note.content || '').includes(material.url) || note.excerpt.includes(material.url))
}

function normalizeNoteSearch(value: string) {
  return value.toLocaleLowerCase().replace(/[^\p{L}\p{N}]+/gu, '')
}

/** 只返回确实提到当前安排或所属计划的笔记，避免用笔记列表顺序制造假关联。 */
function relatedScheduleNotes(noteItems: Note[], item: CalendarItem, plan?: Plan) {
  const itemTitle = normalizeNoteSearch(item.title)
  const planTitle = plan?.title ? normalizeNoteSearch(plan.title) : ''
  if (!itemTitle && !planTitle) return []

  return noteItems
    .map((note) => {
      const searchable = normalizeNoteSearch([note.title, note.excerpt, note.content, note.source].filter(Boolean).join(' '))
      let score = 0
      if (itemTitle && searchable.includes(itemTitle)) score += 5
      if (planTitle && searchable.includes(planTitle)) score += 2
      if (note.source === '日程小记' && itemTitle && normalizeNoteSearch(note.title).includes(itemTitle)) score += 3
      return { note, score }
    })
    .filter(({ score }) => score > 0)
    .sort((left, right) => right.score - left.score)
    .slice(0, 3)
    .map(({ note }) => note)
}

type ScheduleSection = { title: string; content: string }

function validScheduleSections(value: unknown): value is ScheduleSection[] {
  return Array.isArray(value) && value.length >= 3 && value.every((item) => {
    if (!item || typeof item !== 'object') return false
    const section = item as Partial<ScheduleSection>
    return typeof section.title === 'string' && section.title.trim().length > 0
      && typeof section.content === 'string' && section.content.trim().length > 0
  })
}

function fallbackScheduleSections(item: CalendarItem): ScheduleSection[] {
  return [
    { title: '事项概览', content: `围绕“${item.title}”先明确这次安排要达成的结果，再区分需要了解的信息、需要执行的步骤和可以验证的完成标准。` },
    { title: '行动步骤', content: '先列出整体框架，再选择可靠的参考内容核对细节；把关键结论、执行步骤和一个具体例子分别记下来。' },
    { title: '执行建议', content: '把主题拆成一个 20 至 30 分钟的小步骤，完成后记录结果、遇到的问题和下一步行动。' },
    { title: '资料使用提醒', content: '平台入口用于继续查找相关内容，打开原文时优先核对来源、发布时间和上下文，不要只依据搜索摘要下结论。' },
  ]
}

type NoteChatMessage = { role: 'user' | 'assistant'; content: string; markdown?: string }

const NOTE_PREFILL = '请根据本次日程内容整理一份结构化小记'
const NOTE_QUICK_SUGGESTIONS = ['整理成小记', '精简一点', '补充行动清单', '口语化一点']

export function ScheduleDetailPage({
  item,
  plansData,
  noteItems,
  onNotesChange,
  onDeleteNote,
  onOpenNote,
  onToggle,
  onEdit,
  onDelete,
  onBack,
}: {
  item: CalendarItem
  plansData: Plan[]
  noteItems: Note[]
  onNotesChange: (notes: Note[]) => void
  onDeleteNote: (id: string) => Promise<boolean>
  onOpenNote: (id: string) => void
  onToggle: (id: string) => void
  onEdit: (item: CalendarItem) => void
  onDelete: (id: string) => void
  onBack: () => void
}) {
  const plan = plansData.find((value) => value.id === item.planId)
  const [materials, setMaterials] = useState<SourceMaterial[]>([])
  const [keyPoints, setKeyPoints] = useState<string[]>([])
  const [scheduleNote, setScheduleNote] = useState('正在匹配相关资料并生成日程建议。')
  const [scheduleSections, setScheduleSections] = useState<ScheduleSection[]>([])
  const [aiGenerated, setAiGenerated] = useState(false)
  const [materialQuery, setMaterialQuery] = useState('')
  const [materialsLoading, setMaterialsLoading] = useState(true)
  const [materialsError, setMaterialsError] = useState('')
  const [personalNote, setPersonalNote] = useState('')
  const [personalNoteId, setPersonalNoteId] = useState<string | null>(null)
  const [noteMode, setNoteMode] = useState<'edit' | 'preview'>('edit')
  const [imageUploading, setImageUploading] = useState(false)
  const [savedSources, setSavedSources] = useState<string[]>([])
  const [chatOpen, setChatOpen] = useState(false)
  const [chatMessages, setChatMessages] = useState<NoteChatMessage[]>([])
  const [chatInput, setChatInput] = useState('')
  const [chatBusy, setChatBusy] = useState(false)
  const [noteVersions, setNoteVersions] = useState<string[]>([])
  const chatListRef = useRef<HTMLDivElement>(null)

  const loadMaterials = useCallback(async (refresh = false) => {
    setMaterialsLoading(true)
    setMaterialsError('')
    try {
      const result = await plannerApi.loadScheduleMaterials(item.id, refresh)
      if (!Array.isArray(result.materials) || !Array.isArray(result.keyPoints) || typeof result.studyNote !== 'string' || !validScheduleSections(result.sections)) {
        throw new Error('资料接口返回格式错误')
      }
      setMaterials(result.materials.length > 0 ? result.materials : fallbackMaterials(item, plan))
      setMaterialQuery(result.query)
      setKeyPoints(result.keyPoints)
      setScheduleNote(result.studyNote)
      setScheduleSections(result.sections)
      setAiGenerated(result.aiGenerated)
      setPersonalNote((current) => current || result.studyNote)
    } catch (cause) {
      setMaterials(fallbackMaterials(item, plan))
      setMaterialQuery([plan?.title, item.title].filter(Boolean).join(' '))
      setKeyPoints(['先确认资料和当前日程的关联，再选择可靠内容深入了解。', '记录关键结论、执行步骤和一个可验证的结果。', '完成后用自己的话复盘要点，并把疑问写入这次小记。'])
      setScheduleNote('当前先用基础框架整理日程方向；网络恢复后点击“重新获取”，让 AI 结合联网资料补充细节。')
      setScheduleSections(fallbackScheduleSections(item))
      setAiGenerated(false)
      setMaterialsError(cause instanceof Error ? cause.message : '资料获取失败')
      setPersonalNote((current) => current || '先选择可靠内容了解，再记录本次日程的收获、结果和疑问。')
    } finally {
      setMaterialsLoading(false)
    }
  }, [item, plan])

  useEffect(() => { void loadMaterials() }, [loadMaterials])
  useEffect(() => {
    setSavedSources(materials.filter((material) => materialNote(noteItems, material)).map((material) => material.id))
  }, [materials, noteItems])

  const toggleSavedSource = (material: SourceMaterial) => {
    const existing = materialNote(noteItems, material)
    if (existing) {
      onDeleteNote(existing.id)
      return
    }
    const content = `# ${material.title}\n\n关联安排：${item.title}\n\n${material.summary}\n\n来源：${material.source}\n\n[打开原文](${material.url})`
    const next: Note = {
      id: `source-note-${Date.now()}`,
      title: material.title,
      category: '灵感收藏',
      excerpt: `关联安排：${item.title}\n\n${material.summary}\n\n原文链接：${material.url}`,
      content,
      updatedAt: '刚刚',
      color: material.color,
      relatedIds: [],
      source: material.source,
    }
    onNotesChange([next, ...noteItems])
    setSavedSources((current) => [...current, material.id])
  }

  useEffect(() => {
    chatListRef.current?.scrollTo({ top: chatListRef.current.scrollHeight, behavior: 'smooth' })
  }, [chatMessages, chatBusy])

  const sendChat = async (text: string) => {
    const message = text.trim()
    if (!message || chatBusy) return
    const snapshot = personalNote
    setChatBusy(true)
    setChatMessages((current) => [...current, { role: 'user', content: message }])
    setChatInput('')
    try {
      // 历史只带指令与 AI 回复摘要（slice 前的旧数组天然排除本条），完整笔记始终由 draftNote 携带。
      const history = chatMessages.slice(-8).map(({ role, content }) => ({ role, content }))
      const result = await plannerApi.generateNoteContent({
        scheduleTitle: item.title,
        studyNote: scheduleNote,
        draftNote: personalNote,
        keyPoints,
        sections: scheduleSections,
        message,
        history,
      })
      setNoteVersions((stack) => [...stack, snapshot])
      setPersonalNote(result.markdown) // 整篇替换
      setChatMessages((current) => [...current, { role: 'assistant', content: result.reply, markdown: result.markdown }])
      setNoteMode('preview')
    } catch (cause) {
      setChatMessages((current) => [...current, {
        role: 'assistant',
        content: '生成失败：' + (cause instanceof Error ? cause.message : '未知错误'),
        markdown: undefined,
      }])
    } finally {
      setChatBusy(false)
    }
  }

  const toggleChatPanel = () => {
    if (chatOpen) { setChatOpen(false); return }
    setChatOpen(true)
    if (chatMessages.length === 0) void sendChat(NOTE_PREFILL)
  }

  const undoLastAdjust = () => {
    if (!noteVersions.length || chatBusy) return
    setPersonalNote(noteVersions[noteVersions.length - 1])
    setNoteVersions((stack) => stack.slice(0, -1))
  }

  const handleImageUpload = () => {
    const input = document.createElement('input')
    input.type = 'file'
    input.accept = 'image/*'
    input.onchange = async () => {
      const file = input.files?.[0]
      if (!file) return
      setImageUploading(true)
      try {
        const result = await plannerApi.uploadNoteImage(file)
        const imageMarkdown = `\n![${file.name}](${result.url})\n`
        setPersonalNote((current) => current + imageMarkdown)
      } catch (cause) {
        alert('图片上传失败：' + (cause instanceof Error ? cause.message : '未知错误'))
      } finally {
        setImageUploading(false)
      }
    }
    input.click()
  }

  const savePersonalNote = () => {
    const next: Note = {
      id: personalNoteId ?? `study-note-${Date.now()}`,
      title: `${item.title} · 小记`,
      category: '小记',
      excerpt: personalNote,
      content: personalNote,
      updatedAt: '刚刚',
      color: item.color,
      relatedIds: [],
      source: '日程小记',
    }
    onNotesChange(personalNoteId ? noteItems.map((note) => note.id === personalNoteId ? next : note) : [next, ...noteItems])
    setPersonalNoteId(next.id)
  }

  const relatedNotes = relatedScheduleNotes(noteItems, item, plan)

  return (
    <div className="schedule-detail content-page">
      <button className="back-button" type="button" onClick={onBack}><ChevronLeft size={17} /> 返回日历</button>
      <section className="schedule-heading">
        <div className="schedule-title-mark" style={{ backgroundColor: item.color }}><BookOpen size={22} /></div>
        <div>
          <span className="eyebrow">当日安排</span>
          <h2>{item.title}</h2>
          <p>{item.date} · {item.time} · {item.duration} 分钟 {plan ? `· ${plan.title}` : ''}</p>
        </div>
        <div className="schedule-heading-actions">
          <button className="secondary-button" type="button" onClick={() => onToggle(item.id)} title="切换这条日程的完成状态"><CheckCircle2 size={16} /> {item.status === 'done' ? '已完成' : '标记完成'}</button>
          <button className="icon-button" type="button" onClick={() => onEdit(item)} title="编辑安排"><PenLine size={16} /></button>
          <button className="icon-button danger-icon" type="button" onClick={() => onDelete(item.id)} title="删除安排"><X size={16} /></button>
        </div>
      </section>

      <div className="schedule-detail-grid">
        <section className="knowledge-content">
          <div className="section-heading">
            <div><span className="eyebrow">AI 资料整理</span><h3>AI 详细资料整理</h3><small>{materialQuery ? `参考主题：${materialQuery}` : '正在生成整理主题'}</small></div>
            <button className="secondary-button" type="button" onClick={() => void loadMaterials(true)} disabled={materialsLoading} title="刷新 AI 资料"><RefreshCw className={materialsLoading ? 'icon-spinning' : ''} size={15} /> 刷新</button>
          </div>

          <article className="knowledge-summary">
            <div className="summary-mark"><Sparkles size={18} /></div>
            <div>
              <strong>{materialsLoading ? '正在整理资料' : `开始前先掌握 ${keyPoints.length || 0} 个关键点`}</strong>
              <ul>{keyPoints.map((point) => <li key={point}>{point}</li>)}</ul>
            </div>
          </article>

          <article className="ai-material-detail">
            <div className="ai-material-detail-heading">
              <div><span className="eyebrow">日程内容</span><h3>从资料到可执行行动</h3></div>
              <span className="ai-material-status">{materialsLoading ? '整理中' : aiGenerated ? 'AI 已整理' : '基础整理'}</span>
            </div>
            <p className="ai-material-intro">{scheduleNote}</p>
            {materialsError && <p className="ai-material-error">资料状态：{materialsError}</p>}
            <div className="ai-material-sections">
              {scheduleSections.map((section) => (
                <section key={section.title}>
                  <h4>{section.title}</h4>
                  <p>{section.content}</p>
                </section>
              ))}
            </div>
          </article>

          <div className="material-list">
            <div className="materials-divider"><span>延伸搜索入口</span></div>
            {materials.map((material) => {
              const isSaved = savedSources.includes(material.id)
              const isVideo = material.source.toLowerCase().includes('bilibili') || material.source.includes('哔哩')
              return (
                <article className="material-row" key={material.id}>
                  {isVideo ? <div className="video-preview"><Play size={22} fill="currentColor" /><span>{material.source}</span></div> : <div className="source-preview" style={{ backgroundColor: material.color }}>{material.source.includes('小红') ? <NotebookPen size={24} /> : <ExternalLink size={24} />}</div>}
                  <div className="material-copy">
                    <span className="material-source">{material.source}</span>
                    <strong>{material.title}</strong>
                    <p>{material.summary}</p>
                    <small>{material.meta}</small>
                  </div>
                  <div className="material-actions">
                    <a className="icon-button" href={material.url} title={`打开${material.source}原文`} aria-label={`打开${material.source}原文：${material.title}`}><ExternalLink size={16} /></a>
                    <button className={isSaved ? 'saved-source' : 'icon-button'} type="button" onClick={() => toggleSavedSource(material)} title={isSaved ? '取消收藏这条资料' : '收藏资料为笔记，笔记中会保留原文链接'} aria-label={isSaved ? `取消收藏：${material.title}` : `收藏为笔记：${material.title}`}>
                      {isSaved ? <Check size={16} /> : <Plus size={16} />}
                    </button>
                  </div>
                </article>
              )
            })}
          </div>
        </section>

        <aside className="schedule-notes">
          <div className="section-heading">
            <div><span className="eyebrow">我的记录</span><h3>本次小记</h3></div>
            <button className="primary-button save-note-top" type="button" onClick={savePersonalNote} title="保存到长期笔记"><Save size={14} /> 保存</button>
          </div>
          <div className="note-mode-tabs">
            <button type="button" className={noteMode === 'edit' ? 'active' : ''} onClick={() => setNoteMode('edit')}>✏️ 编辑</button>
            <button type="button" className={noteMode === 'preview' ? 'active' : ''} onClick={() => setNoteMode('preview')}>👁 预览</button>
          </div>
          {noteMode === 'edit' ? (
            <textarea value={personalNote} onChange={(event) => setPersonalNote(event.target.value)} placeholder="写下本次日程的收获、结果、疑问或下一步…支持 Markdown 语法" />
          ) : (
            <div className="note-preview-area" onClick={() => setNoteMode('edit')} role="button" tabIndex={0} onKeyDown={(e) => e.key === 'Enter' && setNoteMode('edit')}>
              {personalNote.trim() ? <MarkdownPreview value={personalNote} /> : <p className="note-preview-empty">点击此处开始编辑笔记</p>}
            </div>
          )}
          <div className="note-editor-toolbar">
            <button className="secondary-button" type="button" onClick={handleImageUpload} disabled={imageUploading} title="上传图片到笔记">
              <Image size={14} /> {imageUploading ? '上传中…' : '图片'}
            </button>
            <button className="secondary-button ai-write-button" type="button" onClick={toggleChatPanel} disabled={chatBusy} title="和 AI 对话来写笔记、反复调整笔记">
              <Sparkles size={14} /> {chatBusy ? 'AI 处理中…' : chatOpen ? '收起 AI 对话' : 'AI 写小记'}
            </button>
          </div>

          {chatOpen && (
            <div className="note-chat-panel">
              <div className="note-chat-header">
                <span><Sparkles size={13} /> AI 小记助手</span>
                <button className="icon-button" type="button" onClick={() => setChatOpen(false)} title="收起对话" aria-label="收起对话"><ChevronDown size={15} /></button>
              </div>

              {noteVersions.length > 0 && (
                <div className="note-chat-undo">
                  <button className="secondary-button" type="button" onClick={undoLastAdjust} disabled={chatBusy} title="恢复上一次 AI 调整前的笔记内容"><RefreshCw size={12} /> 撤销本次调整</button>
                </div>
              )}

              <div className="note-chat-messages" ref={chatListRef}>
                {chatMessages.length === 0 && <p className="note-chat-empty">点击下方快捷指令开始，或直接输入你的要求。</p>}
                {chatMessages.map((message, index) => (
                  <article className={`ai-chat-message ${message.role}`} key={`${message.role}-${index}`}>
                    <span>{message.role === 'assistant' ? 'AI' : '你'}</span>
                    <div className="note-chat-body">
                      <p>{message.content}</p>
                      {message.role === 'assistant' && message.markdown && (
                        <div className="note-chat-reply-note"><MarkdownPreview value={message.markdown} /></div>
                      )}
                    </div>
                  </article>
                ))}
                {chatBusy && (
                  <article className="ai-chat-message assistant loading"><span>AI</span><p><LoaderCircle className="spin" size={13} /> 正在处理</p></article>
                )}
              </div>

              <div className="note-chat-suggestions">
                {NOTE_QUICK_SUGGESTIONS.map((suggestion) => (
                  <button type="button" key={suggestion} disabled={chatBusy} onClick={() => void sendChat(suggestion)}>{suggestion}</button>
                ))}
              </div>

              <div className="ai-chat-composer note-chat-composer">
                <textarea value={chatInput} onChange={(event) => setChatInput(event.target.value)} onKeyDown={(event) => { if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); void sendChat(chatInput) } }} placeholder="输入你的要求，Enter 发送" rows={1} />
                <div className="ai-composer-actions">
                  <button className="icon-button ai-send-button" type="button" disabled={chatBusy || !chatInput.trim()} onClick={() => void sendChat(chatInput)} title="发送" aria-label="发送"><Send size={15} /></button>
                </div>
              </div>
            </div>
          )}

          <div className="schedule-related">
            <span className="eyebrow">相关长期笔记</span>
            {relatedNotes.map((note) => (
              <button className="schedule-related-item" key={note.id} type="button" onClick={() => onOpenNote(note.id)}>
                <i style={{ backgroundColor: note.color }} />
                <div><strong>{note.title}</strong><span>{note.updatedAt}</span></div>
                <ChevronRight size={15} />
              </button>
            ))}
            {relatedNotes.length === 0 && <span className="schedule-related-empty">暂无与当前安排直接关联的长期笔记</span>}
          </div>
        </aside>
      </div>
    </div>
  )
}
