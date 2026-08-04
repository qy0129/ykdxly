import { useCallback, useEffect, useState } from 'react'
import { BookOpen, Check, CheckCircle2, ChevronLeft, ChevronRight, ExternalLink, NotebookPen, PenLine, Play, Plus, RefreshCw, Save, Sparkles, X } from 'lucide-react'
import type { CalendarItem, Note, Plan, SourceMaterial } from '../../types/planner'
import { plannerApi } from '../../services/plannerApi'

function fallbackMaterials(item: CalendarItem, plan?: Plan): SourceMaterial[] {
  const query = [plan?.title, item.title].filter(Boolean).join(' ').trim() || item.title
  const encodedQuery = encodeURIComponent(query)
  return [
    { id: `bilibili-search-${item.id}`, kind: 'platform', source: '哔哩哔哩', title: `搜索：${query}`, summary: '打开哔哩哔哩搜索相关课程和讲解视频。', meta: '平台入口 · 必备', url: `https://search.bilibili.com/all?keyword=${encodedQuery}`, color: '#b85f42' },
    { id: `xiaohongshu-search-${item.id}`, kind: 'platform', source: '小红书', title: `搜索：${query}`, summary: '打开小红书搜索相关学习笔记和经验分享。', meta: '平台入口 · 必备', url: `https://www.xiaohongshu.com/search_result?keyword=${encodedQuery}`, color: '#d77d55' },
  ]
}

function materialNote(noteItems: Note[], material: SourceMaterial) {
  return noteItems.find((note) => (note.content || '').includes(material.url) || note.excerpt.includes(material.url))
}

type StudySection = { title: string; content: string }

function validStudySections(value: unknown): value is StudySection[] {
  return Array.isArray(value) && value.length >= 3 && value.every((item) => {
    if (!item || typeof item !== 'object') return false
    const section = item as Partial<StudySection>
    return typeof section.title === 'string' && section.title.trim().length > 0
      && typeof section.content === 'string' && section.content.trim().length > 0
  })
}

function fallbackStudySections(item: CalendarItem): StudySection[] {
  return [
    { title: '核心理解', content: `围绕“${item.title}”先明确今天要解决的问题，再区分需要理解的概念、需要执行的方法和可以验证的结果。` },
    { title: '学习路径', content: '先建立整体框架，再选择一份可靠原文核对细节；把定义、步骤和一个具体例子分别记录下来。' },
    { title: '实践建议', content: '把主题拆成一个 20 至 30 分钟的小练习，完成后用自己的话复述要点，并记录一个仍然不清楚的问题。' },
    { title: '资料使用提醒', content: '平台入口用于继续查找资料，打开原文时优先核对来源、发布时间和上下文，不要只依据搜索摘要下结论。' },
  ]
}

export function ScheduleDetailPage({
  item,
  plansData,
  noteItems,
  onNotesChange,
  onDeleteNote,
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
  onToggle: (id: string) => void
  onEdit: (item: CalendarItem) => void
  onDelete: (id: string) => void
  onBack: () => void
}) {
  const plan = plansData.find((value) => value.id === item.planId)
  const [materials, setMaterials] = useState<SourceMaterial[]>([])
  const [keyPoints, setKeyPoints] = useState<string[]>([])
  const [studyNote, setStudyNote] = useState('正在匹配学习资料并生成学习建议。')
  const [studySections, setStudySections] = useState<StudySection[]>([])
  const [aiGenerated, setAiGenerated] = useState(false)
  const [materialQuery, setMaterialQuery] = useState('')
  const [materialsLoading, setMaterialsLoading] = useState(true)
  const [materialsError, setMaterialsError] = useState('')
  const [personalNote, setPersonalNote] = useState('')
  const [saved, setSaved] = useState(true)
  const [savedSources, setSavedSources] = useState<string[]>([])
  const [personalNoteId, setPersonalNoteId] = useState<string | null>(null)

  const loadMaterials = useCallback(async (refresh = false) => {
    setMaterialsLoading(true)
    setMaterialsError('')
    try {
      const result = await plannerApi.loadScheduleMaterials(item.id, refresh)
      if (!Array.isArray(result.materials) || !Array.isArray(result.keyPoints) || typeof result.studyNote !== 'string' || !validStudySections(result.sections)) {
        throw new Error('资料接口返回格式错误')
      }
      setMaterials(result.materials.length > 0 ? result.materials : fallbackMaterials(item, plan))
      setMaterialQuery(result.query)
      setKeyPoints(result.keyPoints)
      setStudyNote(result.studyNote)
      setStudySections(result.sections)
      setAiGenerated(result.aiGenerated)
      setPersonalNote((current) => current || result.studyNote)
    } catch (cause) {
      setMaterials(fallbackMaterials(item, plan))
      setMaterialQuery([plan?.title, item.title].filter(Boolean).join(' '))
      setKeyPoints(['先确认资料和当前日程的关联，再选择一篇可靠原文深入学习。', '记录定义、步骤和一个可验证的例子。', '学习结束后用自己的话复述要点，并把疑问写入本次笔记。'])
      setStudyNote('当前先用基础框架整理学习方向；网络恢复后点击“重新获取”，让 AI 结合联网资料补充细节。')
      setStudySections(fallbackStudySections(item))
      setAiGenerated(false)
      setMaterialsError(cause instanceof Error ? cause.message : '资料获取失败')
      setPersonalNote((current) => current || '先选择一篇可靠原文深入学习，再记录本次日程的收获和疑问。')
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
    const content = `# ${material.title}\n\n${material.summary}\n\n来源：${material.source}\n\n[打开原文](${material.url})`
    const next: Note = {
      id: `source-note-${Date.now()}`,
      title: material.title,
      category: '灵感收藏',
      excerpt: `${material.summary}\n\n原文链接：${material.url}`,
      content,
      updatedAt: '刚刚',
      color: material.color,
      relatedIds: [],
      source: material.source,
    }
    onNotesChange([next, ...noteItems])
    setSavedSources((current) => [...current, material.id])
  }

  const savePersonalNote = () => {
    const next: Note = {
      id: personalNoteId ?? `study-note-${Date.now()}`,
      title: `${item.title} · 学习记录`,
      category: '学习笔记',
      excerpt: personalNote,
      content: personalNote,
      updatedAt: '刚刚',
      color: item.color,
      relatedIds: [],
      source: '日程学习记录',
    }
    onNotesChange(personalNoteId ? noteItems.map((note) => note.id === personalNoteId ? next : note) : [next, ...noteItems])
    setPersonalNoteId(next.id)
    setSaved(true)
  }

  return (
    <div className="schedule-detail content-page">
      <button className="back-button" type="button" onClick={onBack}><ChevronLeft size={17} /> 返回日历</button>
      <section className="schedule-heading">
        <div className="schedule-title-mark" style={{ backgroundColor: item.color }}><BookOpen size={22} /></div>
        <div>
          <span className="eyebrow">当日安排 · 学习</span>
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
              <div><span className="eyebrow">学习内容</span><h3>从资料到可执行理解</h3></div>
              <span className="ai-material-status">{materialsLoading ? '整理中' : aiGenerated ? 'AI 已整理' : '基础整理'}</span>
            </div>
            <p className="ai-material-intro">{studyNote}</p>
            {materialsError && <p className="ai-material-error">资料状态：{materialsError}</p>}
            <div className="ai-material-sections">
              {studySections.map((section) => (
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
            <div><span className="eyebrow">我的记录</span><h3>本次学习笔记</h3></div>
            <span className="save-state">{saved ? '已保存' : '未保存'}</span>
          </div>
          <textarea value={personalNote} onChange={(event) => { setPersonalNote(event.target.value); setSaved(false) }} placeholder="写下本次学习的收获、疑问或下一步…" />
          <button className="primary-button note-save-button" type="button" onClick={savePersonalNote} title="保存本次学习记录"><Save size={16} /> 保存到长期笔记</button>

          <div className="schedule-related">
            <span className="eyebrow">相关长期笔记</span>
            {noteItems.filter((note) => note.category === '学习笔记').slice(0, 3).map((note) => (
              <article key={note.id}><i style={{ backgroundColor: note.color }} /><div><strong>{note.title}</strong><span>{note.updatedAt}</span></div><ChevronRight size={15} /></article>
            ))}
          </div>
        </aside>
      </div>
    </div>
  )
}
