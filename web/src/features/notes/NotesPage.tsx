import { useEffect, useRef, useState, type PointerEvent as ReactPointerEvent, type WheelEvent as ReactWheelEvent } from 'react'
import { Eye, FileText, FolderOpen, Link2, Network, Pencil, Plus, Save, Search, Tag, Trash2, X } from 'lucide-react'
import { notes as initialNotes } from '../../mocks/plannerData'
import type { Note } from '../../types/planner'
import { CategoryDialog, NoteDialog, type NoteDraftFields } from '../../components/dialogs/PlannerDialogs'
import { MarkdownPreview } from './MarkdownPreview'
import { markdownExcerpt } from './markdown'

/** 笔记模块将图谱交互和笔记列表放在同一领域边界内。 */
const graphPositions: Record<string, { x: number; y: number }> = {
  n1: { x: 330, y: 120 },
  n2: { x: 485, y: 190 },
  n3: { x: 270, y: 285 },
  n4: { x: 575, y: 90 },
  n5: { x: 420, y: 360 },
  n6: { x: 135, y: 225 },
  n7: { x: 650, y: 285 },
  n8: { x: 175, y: 390 },
}

export function NoteGraph({ noteItems, activeId, onSelect }: { noteItems: Note[]; activeId: string; onSelect: (id: string) => void }) {
  const initialPositions = Object.fromEntries(noteItems.map((note, index) => {
    const fallback = {
      x: 380 + Math.cos((index / Math.max(noteItems.length, 1)) * Math.PI * 2) * 260,
      y: 235 + Math.sin((index / Math.max(noteItems.length, 1)) * Math.PI * 2) * 170,
    }
    return [note.id, graphPositions[note.id] ?? fallback]
  })) as Record<string, { x: number; y: number }>
  const [positions, setPositions] = useState<Record<string, { x: number; y: number }>>(initialPositions)
  const [viewport, setViewport] = useState({ x: 0, y: 0, scale: 1 })
  const svgRef = useRef<SVGSVGElement | null>(null)
  const interactionRef = useRef<{ kind: 'node' | 'pan'; noteId?: string; startX: number; startY: number; originX: number; originY: number } | null>(null)
  const suppressClickRef = useRef(false)

  useEffect(() => {
    setPositions((current) => {
      const next: Record<string, { x: number; y: number }> = {}
      noteItems.forEach((note, index) => {
        const fallback = {
          x: 380 + Math.cos((index / Math.max(noteItems.length, 1)) * Math.PI * 2) * 260,
          y: 235 + Math.sin((index / Math.max(noteItems.length, 1)) * Math.PI * 2) * 170,
        }
        next[note.id] = current[note.id] ?? graphPositions[note.id] ?? fallback
      })
      return next
    })
  }, [noteItems])

  const handlePointerDown = (clientX: number, clientY: number, noteId?: string) => {
    suppressClickRef.current = false
    const point = noteId ? positions[noteId] : viewport
    interactionRef.current = {
      kind: noteId ? 'node' : 'pan',
      noteId,
      startX: clientX,
      startY: clientY,
      originX: point.x,
      originY: point.y,
    }
  }

  const handlePointerMove = (event: ReactPointerEvent<SVGSVGElement>) => {
    const interaction = interactionRef.current
    if (!interaction) return
    const rect = svgRef.current?.getBoundingClientRect()
    if (!rect) return
    const deltaX = event.clientX - interaction.startX
    const deltaY = event.clientY - interaction.startY
    if (Math.abs(deltaX) + Math.abs(deltaY) > 3) suppressClickRef.current = true
    if (interaction.kind === 'node' && interaction.noteId) {
      setPositions((current) => ({
        ...current,
        [interaction.noteId as string]: {
          x: interaction.originX + deltaX / rect.width * 760 / viewport.scale,
          y: interaction.originY + deltaY / rect.height * 470 / viewport.scale,
        },
      }))
    } else {
      setViewport((current) => ({
        ...current,
        x: interaction.originX + deltaX / rect.width * 760,
        y: interaction.originY + deltaY / rect.height * 470,
      }))
    }
  }

  const handlePointerUp = () => {
    const interaction = interactionRef.current
    if (interaction?.kind === 'node' && interaction.noteId && !suppressClickRef.current) {
      onSelect(interaction.noteId)
    }
    interactionRef.current = null
    window.setTimeout(() => { suppressClickRef.current = false }, 0)
  }

  const handleWheel = (event: ReactWheelEvent<SVGSVGElement>) => {
    event.preventDefault()
    const nextScale = Math.max(0.55, Math.min(2.1, viewport.scale - event.deltaY * 0.001))
    setViewport((current) => ({ ...current, scale: nextScale }))
  }

  const resetViewport = () => setViewport({ x: 0, y: 0, scale: 1 })
  const zoom = (amount: number) => setViewport((current) => ({ ...current, scale: Math.max(0.55, Math.min(2.1, current.scale + amount)) }))
  const relations = noteItems.flatMap((note) =>
    note.relatedIds
      .filter((relatedId) => note.id < relatedId && positions[relatedId])
      .map((relatedId) => ({ from: note.id, to: relatedId })),
  )

  return (
    <div className="note-graph">
      <div className="graph-tools" aria-label="图谱工具">
        <button type="button" onClick={() => zoom(-0.1)} title="缩小">−</button>
        <button type="button" onClick={resetViewport} title="重置视图">{Math.round(viewport.scale * 100)}%</button>
        <button type="button" onClick={() => zoom(0.1)} title="放大">+</button>
      </div>
      <svg
        ref={svgRef}
        viewBox="0 0 760 470"
        role="img"
        aria-label="笔记关系图谱，可拖动节点和平移画布"
        onPointerDown={(event) => { event.currentTarget.setPointerCapture(event.pointerId); handlePointerDown(event.clientX, event.clientY) }}
        onPointerMove={handlePointerMove}
        onPointerUp={handlePointerUp}
        onPointerCancel={handlePointerUp}
        onWheel={handleWheel}
      >
        <g transform={`translate(${viewport.x} ${viewport.y}) scale(${viewport.scale})`}>
          <g className="graph-links">
          {relations.map((relation) => (
            <line
              key={relation.from + relation.to}
              x1={positions[relation.from].x}
              y1={positions[relation.from].y}
              x2={positions[relation.to].x}
              y2={positions[relation.to].y}
            />
          ))}
          </g>
          <g className="graph-nodes">
          {noteItems.map((note) => {
            const position = positions[note.id]
            return (
              <g
                key={note.id}
                className={activeId === note.id ? 'active' : ''}
                onPointerDown={(event) => { event.stopPropagation(); svgRef.current?.setPointerCapture(event.pointerId); handlePointerDown(event.clientX, event.clientY, note.id) }}
                onClick={(event) => event.preventDefault()}
                onKeyDown={(event) => event.key === 'Enter' && onSelect(note.id)}
                role="button"
                tabIndex={0}
              >
                <circle cx={position.x} cy={position.y} r={activeId === note.id ? 28 : 22} fill={note.color} />
                <text x={position.x} y={position.y + 42} textAnchor="middle">{note.title.slice(0, 10)}</text>
              </g>
            )
          })}
          </g>
        </g>
      </svg>
      <div className="graph-legend">
        <span><i style={{ backgroundColor: '#7c647d' }} />学习笔记</span>
        <span><i style={{ backgroundColor: '#d39a24' }} />计划方法</span>
        <span><i style={{ backgroundColor: '#b85f42' }} />产品与收藏</span>
        <span><i style={{ backgroundColor: '#72806a' }} />复盘记录</span>
      </div>
    </div>
  )
}

export function NotesPage({
  noteItems,
  onChange,
  onCreate,
  onDelete,
  onDeleteMany,
  selectedNoteId,
}: {
  noteItems: Note[]
  onChange: (notes: Note[]) => void
  onCreate: (fields: NoteDraftFields) => Note
  onDelete: (id: string) => Promise<boolean>
  onDeleteMany: (ids: string[]) => Promise<boolean>
  selectedNoteId?: string
}) {
  const [activeCategory, setActiveCategory] = useState('全部笔记')
  const [activeId, setActiveId] = useState(initialNotes[0].id)
  const [mode, setMode] = useState<'列表' | '关系图谱'>('列表')
  const [editorMode, setEditorMode] = useState<'edit' | 'preview'>('edit')
  const [query, setQuery] = useState('')
  const [noteDialogOpen, setNoteDialogOpen] = useState(false)
  const [categoryDialogOpen, setCategoryDialogOpen] = useState(false)
  const [selectedIds, setSelectedIds] = useState<string[]>([])

  useEffect(() => {
    if (selectedNoteId) setActiveId(selectedNoteId)
  }, [selectedNoteId])

  useEffect(() => {
    const ids = new Set(noteItems.map((note) => note.id))
    setSelectedIds((current) => current.filter((id) => ids.has(id)))
  }, [noteItems])

  const categories = ['全部笔记', ...Array.from(new Set(noteItems.map((note) => note.category)))]
  const visibleNotes = noteItems.filter((note) => {
    const categoryMatch = activeCategory === '全部笔记' || note.category === activeCategory
    const queryMatch = !query.trim() || (note.title + note.excerpt + (note.content ?? '')).toLowerCase().includes(query.toLowerCase())
    return categoryMatch && queryMatch
  })
  const visibleIds = visibleNotes.map((note) => note.id)
  const allVisibleSelected = visibleIds.length > 0 && visibleIds.every((id) => selectedIds.includes(id))
  const activeNote = noteItems.find((note) => note.id === activeId) ?? noteItems[0]

  if (!activeNote) {
    return (
      <div className="content-page simple-empty">
        还没有笔记，先创建第一条笔记吧。
        <button className="primary-button" type="button" onClick={() => setNoteDialogOpen(true)}>
          <Plus size={16} /> 新建笔记
        </button>
        {noteDialogOpen && <NoteDialog categories={categories} onClose={() => setNoteDialogOpen(false)} onSubmit={(fields) => { const next = onCreate(fields); setActiveId(next.id); setNoteDialogOpen(false) }} />}
      </div>
    )
  }

  const updateActiveNote = (changes: Partial<Note>) => {
    onChange(noteItems.map((note) => note.id === activeNote.id ? { ...note, ...changes } : note))
  }

  const noteContent = activeNote.content ?? activeNote.excerpt
  const updateMarkdown = (value: string) => updateActiveNote({ content: value, excerpt: markdownExcerpt(value), updatedAt: '刚刚' })

  const createNote = (fields: NoteDraftFields) => {
    const next = onCreate(fields)
    setActiveId(next.id)
    setMode('列表')
    setNoteDialogOpen(false)
  }

  const createCategory = (category: string) => {
    const next: Note = {
      id: 'n-' + Date.now(),
      title: '未命名笔记',
      category,
      excerpt: '',
      updatedAt: '刚刚',
      color: '#72806a',
      relatedIds: [],
      source: '个人创建',
    }
    onChange([next, ...noteItems])
    setActiveCategory(category)
    setActiveId(next.id)
    setCategoryDialogOpen(false)
  }

  const toggleSelection = (id: string) => {
    setSelectedIds((current) => current.includes(id) ? current.filter((value) => value !== id) : [...current, id])
  }

  const deleteSelected = async () => {
    const deletingIds = selectedIds
    if (deletingIds.length === 0 || !await onDeleteMany(deletingIds)) return
    setSelectedIds([])
    if (deletingIds.includes(activeId)) setActiveId(noteItems.find((note) => !deletingIds.includes(note.id))?.id ?? '')
  }

  return (
    <div className="notes-page">
      <aside className="note-categories">
        <div className="note-category-heading">
          <span className="eyebrow">知识库</span>
          <strong>{noteItems.length} 篇长期笔记</strong>
        </div>
        {categories.map((category) => (
          <button
            type="button"
            className={activeCategory === category ? 'active' : ''}
            onClick={() => { setActiveCategory(category); setSelectedIds([]) }}
            key={category}
          >
            <FolderOpen size={16} />
            <span>{category}</span>
            <b>{category === '全部笔记' ? noteItems.length : noteItems.filter((note) => note.category === category).length}</b>
          </button>
        ))}
        <button className="new-category" type="button" onClick={() => setCategoryDialogOpen(true)}><Plus size={15} /> 新建分类</button>
      </aside>

      <section className="note-workspace">
        <div className="notes-toolbar">
          <div className="note-search"><Search size={16} /><input value={query} onChange={(event) => { setQuery(event.target.value); setSelectedIds([]) }} placeholder="搜索笔记" /></div>
          <div className="segmented-control">
            {(['列表', '关系图谱'] as const).map((item) => (
              <button type="button" className={mode === item ? 'active' : ''} onClick={() => setMode(item)} key={item}>
                {item === '列表' ? <FileText size={14} /> : <Network size={14} />} {item}
              </button>
            ))}
          </div>
          {mode === '列表' && <div className="batch-actions notes-batch-actions">
            {visibleNotes.length > 0 && <label className="batch-select"><input className="batch-checkbox" type="checkbox" checked={allVisibleSelected} onChange={() => setSelectedIds(allVisibleSelected ? selectedIds.filter((id) => !visibleIds.includes(id)) : Array.from(new Set([...selectedIds, ...visibleIds])))} />全选当前</label>}
            {selectedIds.length > 0 && <button className="secondary-button danger-text" type="button" onClick={() => void deleteSelected()}><Trash2 size={15} /> 删除（{selectedIds.length}）</button>}
          </div>}
          <button className="primary-button" type="button" onClick={() => setNoteDialogOpen(true)}><Plus size={16} /> 新建笔记</button>
        </div>

        {mode === '关系图谱' ? (
          <div className="graph-workspace">
            <div className="section-heading">
              <div><span className="eyebrow">知识关系</span><h3>笔记关系图谱</h3></div>
              <span className="section-note">拖动节点整理图谱，点击节点打开笔记</span>
            </div>
            <NoteGraph noteItems={noteItems} activeId={activeId} onSelect={(id) => { setActiveId(id); setMode('列表') }} />
          </div>
        ) : (
          <div className="notes-layout">
            <div className="note-list">
              {visibleNotes.map((note) => (
                <div className={`note-list-item ${activeId === note.id ? 'active' : ''}`} key={note.id}>
                  <input className="batch-checkbox" type="checkbox" checked={selectedIds.includes(note.id)} onChange={() => toggleSelection(note.id)} aria-label={`选择笔记：${note.title}`} />
                  <button className="note-list-open" type="button" onClick={() => setActiveId(note.id)}>
                    <span className="note-list-top"><i style={{ backgroundColor: note.color }} />{note.category}<small>{note.updatedAt}</small></span>
                    <strong>{note.title}</strong>
                    <p>{note.excerpt || '开始记录你的想法……'}</p>
                    <span className="note-source"><Link2 size={12} />{note.source}</span>
                  </button>
                </div>
              ))}
            </div>
            <article className="note-editor">
              <div className="note-editor-meta">
                <label><Tag size={14} /><select value={activeNote.category} onChange={(event) => updateActiveNote({ category: event.target.value })}>{categories.slice(1).map((category) => <option key={category}>{category}</option>)}</select></label>
                <span>{activeNote.updatedAt}</span>
                <div className="note-editor-mode" role="tablist" aria-label="笔记编辑模式">
                  <button type="button" className={editorMode === 'edit' ? 'active' : ''} onClick={() => setEditorMode('edit')} role="tab" aria-selected={editorMode === 'edit'}><Pencil size={13} /> 编辑</button>
                  <button type="button" className={editorMode === 'preview' ? 'active' : ''} onClick={() => setEditorMode('preview')} role="tab" aria-selected={editorMode === 'preview'}><Eye size={13} /> 预览</button>
                </div>
                <button className="secondary-button" type="button"><Save size={15} /> 已保存</button>
                <button className="icon-button danger-icon" type="button" onClick={() => { void onDelete(activeNote.id).then((deleted) => { if (deleted) setActiveId(noteItems.find((note) => note.id !== activeNote.id)?.id ?? '') }) }} title="删除笔记"><X size={15} /></button>
              </div>
              <input className="note-title-input" value={activeNote.title} onChange={(event) => updateActiveNote({ title: event.target.value, updatedAt: '刚刚' })} />
              {editorMode === 'edit' ? (
                <textarea
                  className="note-body-input"
                  value={noteContent}
                  onChange={(event) => updateMarkdown(event.target.value)}
                  placeholder="记录长期有价值的知识、想法与连接……"
                />
              ) : <MarkdownPreview value={noteContent} />}
              <div className="related-notes">
                <span className="eyebrow">关联笔记</span>
                <div>
                  {activeNote.relatedIds.map((id) => {
                    const related = noteItems.find((note) => note.id === id)
                    return related ? <button type="button" onClick={() => setActiveId(related.id)} key={id}><i style={{ backgroundColor: related.color }} />{related.title}</button> : null
                  })}
                  <select className="relation-select" value="" onChange={(event) => {
                    const relatedId = event.target.value
                    if (!relatedId) return
                    onChange(noteItems.map((note) => note.id === activeNote.id
                      ? { ...note, relatedIds: Array.from(new Set([...note.relatedIds, relatedId])) }
                      : note.id === relatedId
                        ? { ...note, relatedIds: Array.from(new Set([...note.relatedIds, activeNote.id])) }
                        : note))
                  }}>
                    <option value="">添加关联</option>
                    {noteItems.filter((note) => note.id !== activeNote.id && !activeNote.relatedIds.includes(note.id)).map((note) => <option value={note.id} key={note.id}>{note.title}</option>)}
                  </select>
                </div>
              </div>
            </article>
          </div>
        )}
      </section>
      {noteDialogOpen && <NoteDialog initialCategory={activeCategory} categories={categories} onClose={() => setNoteDialogOpen(false)} onSubmit={createNote} />}
      {categoryDialogOpen && <CategoryDialog onClose={() => setCategoryDialogOpen(false)} onSubmit={createCategory} />}
    </div>
  )
}
