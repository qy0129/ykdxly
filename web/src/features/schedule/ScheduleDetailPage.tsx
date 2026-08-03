import { useState } from 'react'
import { BookOpen, Check, CheckCircle2, ChevronLeft, ChevronRight, ExternalLink, NotebookPen, PenLine, Play, Plus, RefreshCw, Save, Sparkles, X } from 'lucide-react'
import type { CalendarItem, Note, Plan, SourceMaterial } from '../../types/planner'

function buildSearchMaterials(item: CalendarItem, plan?: Plan): SourceMaterial[] {
  const query = [plan?.title, item.title].filter(Boolean).join(' ').trim()
  const encodedQuery = encodeURIComponent(query || item.title)
  return [
    {
      id: `bilibili-search-${item.id}`,
      source: '哔哩哔哩',
      title: `搜索：${query || item.title}`,
      summary: '打开哔哩哔哩，查看与当前计划和日程相关的视频。',
      meta: '哔哩哔哩搜索',
      url: `https://search.bilibili.com/all?keyword=${encodedQuery}`,
      color: '#b85f42',
    },
    {
      id: `xiaohongshu-search-${item.id}`,
      source: '小红书',
      title: `搜索：${query || item.title}`,
      summary: '打开小红书，查看与当前计划和日程相关的笔记。',
      meta: '小红书搜索',
      url: `https://www.xiaohongshu.com/search_result?keyword=${encodedQuery}`,
      color: '#d77d55',
    },
  ]
}

/** 日程详情聚合资料与关联笔记，但不直接访问后端。 */
export function ScheduleDetailPage({
  item,
  plansData,
  noteItems,
  onNotesChange,
  onToggle,
  onEdit,
  onDelete,
  onBack,
}: {
  item: CalendarItem
  plansData: Plan[]
  noteItems: Note[]
  onNotesChange: (notes: Note[]) => void
  onToggle: (id: string) => void
  onEdit: (item: CalendarItem) => void
  onDelete: (id: string) => void
  onBack: () => void
}) {
  const [personalNote, setPersonalNote] = useState('今天要重点理解极限的直观含义，再通过三道典型题检查自己是否真的掌握。')
  const [saved, setSaved] = useState(true)
  const [savedSources, setSavedSources] = useState<string[]>([])
  const [personalNoteId, setPersonalNoteId] = useState<string | null>(null)
  const plan = plansData.find((value) => value.id === item.planId)
  const materials = buildSearchMaterials(item, plan)

  const saveSource = (materialId: string) => {
    if (savedSources.includes(materialId)) return
    const material = materials.find((value) => value.id === materialId)
    if (!material) return
    const next: Note = {
      id: 'source-note-' + Date.now(),
      title: material.title,
      category: '灵感收藏',
      excerpt: material.summary,
      updatedAt: '刚刚',
      color: material.color,
      relatedIds: [],
      source: material.source,
    }
    onNotesChange([next, ...noteItems])
    setSavedSources((current) => [...current, materialId])
  }

  const savePersonalNote = () => {
    const next: Note = {
      id: personalNoteId ?? 'study-note-' + Date.now(),
      title: item.title + ' · 学习记录',
      category: '学习笔记',
      excerpt: personalNote,
      updatedAt: '刚刚',
      color: item.color,
      relatedIds: [],
      source: '日程学习记录',
    }
    onNotesChange(personalNoteId
      ? noteItems.map((note) => note.id === personalNoteId ? next : note)
      : [next, ...noteItems])
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
          <p>{item.date} · {item.time} · {item.duration} 分钟 {plan ? '· ' + plan.title : ''}</p>
        </div>
        <div className="schedule-heading-actions">
          <button className="secondary-button" type="button" onClick={() => onToggle(item.id)}><CheckCircle2 size={16} /> {item.status === 'done' ? '已完成' : '标记完成'}</button>
          <button className="icon-button" type="button" onClick={() => onEdit(item)} title="编辑安排"><PenLine size={16} /></button>
          <button className="icon-button danger-icon" type="button" onClick={() => onDelete(item.id)} title="删除安排"><X size={16} /></button>
        </div>
      </section>

      <div className="schedule-detail-grid">
        <section className="knowledge-content">
          <div className="section-heading">
            <div><span className="eyebrow">自动收集</span><h3>学习资料与知识笔记</h3></div>
            <button className="secondary-button" type="button"><RefreshCw size={15} /> 重新获取</button>
          </div>

          <article className="knowledge-summary">
            <div className="summary-mark"><Sparkles size={18} /></div>
            <div>
              <strong>开始前先抓住三个关键点</strong>
              <ul>
                <li>极限关注的是逼近过程，不要求函数在该点真正取到极限值。</li>
                <li>连续需要函数值存在、极限存在，并且两者相等。</li>
                <li>做题时先判断结构，再选择等价无穷小、夹逼或洛必达法则。</li>
              </ul>
            </div>
          </article>

          <div className="material-list">
            {materials.map((material) => {
              const isSaved = savedSources.includes(material.id)
              return (
                <article className="material-row" key={material.id}>
                  {material.source === '哔哩哔哩' ? (
                    <div className="video-preview">
                      <Play size={22} fill="currentColor" />
                      <span>{material.meta.split(' · ')[0]}</span>
                    </div>
                  ) : (
                    <div className="source-preview" style={{ backgroundColor: material.color }}>
                      {material.source === '小红书' ? <NotebookPen size={24} /> : <ExternalLink size={24} />}
                    </div>
                  )}
                  <div className="material-copy">
                    <span className="material-source">{material.source}</span>
                    <strong>{material.title}</strong>
                    <p>{material.summary}</p>
                    <small>{material.meta}</small>
                  </div>
                  <div className="material-actions">
                    <a
                      className="icon-button"
                      href={material.url}
                      target="_blank"
                      rel="noopener noreferrer"
                      title={`打开${material.source}来源`}
                      aria-label={`打开${material.source}来源：${material.title}`}
                    >
                      <ExternalLink size={16} />
                    </a>
                    <button
                      className={isSaved ? 'saved-source' : 'icon-button'}
                      type="button"
                      title="收藏为笔记"
                      onClick={() => saveSource(material.id)}
                    >
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
          <textarea value={personalNote} onChange={(event) => { setPersonalNote(event.target.value); setSaved(false) }} />
          <button className="primary-button note-save-button" type="button" onClick={savePersonalNote}><Save size={16} /> 保存到长期笔记</button>

          <div className="schedule-related">
            <span className="eyebrow">相关长期笔记</span>
            {noteItems.filter((note) => note.category === '学习笔记').slice(0, 3).map((note) => (
              <article key={note.id}>
                <i style={{ backgroundColor: note.color }} />
                <div><strong>{note.title}</strong><span>{note.updatedAt}</span></div>
                <ChevronRight size={15} />
              </article>
            ))}
          </div>
        </aside>
      </div>
    </div>
  )
}
