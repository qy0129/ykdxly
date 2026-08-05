import { useEffect, useMemo, useRef, useState, type PointerEvent as ReactPointerEvent, type WheelEvent as ReactWheelEvent } from 'react'
import { Eye, FileText, FolderOpen, Image, Link2, Network, Pencil, Plus, Save, Search, Tag, Trash2, X } from 'lucide-react'
import { notes as initialNotes } from '../../mocks/plannerData'
import type { Note } from '../../types/planner'
import { CategoryDialog, NoteDialog, type NoteDraftFields } from '../../components/dialogs/PlannerDialogs'
import { MarkdownPreview } from './MarkdownPreview'
import { markdownExcerpt } from './markdown'
import { plannerApi } from '../../services/plannerApi'

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

function graphLegendItems(noteItems: Note[]) {
  const colors = new Map<string, string>()
  noteItems.forEach((note) => {
    const category = note.category || '未分类'
    if (!colors.has(category)) colors.set(category, note.color)
  })
  return Array.from(colors, ([category, color]) => ({ category, color }))
}

const CATEGORY_COLOR_STORAGE_KEY = 'changlu-note-category-colors'
const CATEGORY_COLOR_PALETTE = ['#d39a24', '#7c647d', '#b85f42', '#72806a', '#4d7c8a', '#9a6b4f', '#716b9e', '#aa6572']
const DEFAULT_CATEGORY_COLORS: Record<string, string> = {
  学习笔记: '#7c647d',
  计划方法: '#d39a24',
  产品设计: '#b85f42',
  产品与收藏: '#b85f42',
  灵感收藏: '#b85f42',
  复盘记录: '#72806a',
}

function loadCategoryColors() {
  try {
    const saved = window.localStorage.getItem(CATEGORY_COLOR_STORAGE_KEY)
    return saved ? { ...DEFAULT_CATEGORY_COLORS, ...JSON.parse(saved) as Record<string, string> } : DEFAULT_CATEGORY_COLORS
  } catch {
    return DEFAULT_CATEGORY_COLORS
  }
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
        {graphLegendItems(noteItems).map((item) => <span key={item.category}><i style={{ backgroundColor: item.color }} />{item.category}</span>)}
      </div>
    </div>
  )
}

/*
 * 下面曾残留一份旧版关系图组件实现。它与上面的增强版同名，导致前端无法编译；
 * 保留在注释中便于后续查阅，实际页面统一使用上面的 ElasticNoteGraph。
 */
type ElasticGraphPoint = { x: number; y: number; vx: number; vy: number }
type ElasticGraphInteraction = {
  kind: 'node' | 'pan'
  noteId?: string
  startX: number
  startY: number
  originX: number
  originY: number
  lastX: number
  lastY: number
  lastAt: number
  offsetX: number
  offsetY: number
}

const ELASTIC_GRAPH_WIDTH = 760
const ELASTIC_GRAPH_HEIGHT = 470
const MIN_GRAPH_NODE_RADIUS = 14
const MAX_GRAPH_NODE_RADIUS = 32
const MAX_GRAPH_WORD_COUNT = 3000

function graphWordCount(note: Note) {
  return (note.content ?? note.excerpt ?? '').replace(/\s+/g, '').length
}

function graphFallbackPosition(index: number, count: number) {
  return {
    x: ELASTIC_GRAPH_WIDTH / 2 + Math.cos(index / Math.max(count, 1) * Math.PI * 2) * 250,
    y: ELASTIC_GRAPH_HEIGHT / 2 + Math.sin(index / Math.max(count, 1) * Math.PI * 2) * 165,
  }
}

function createElasticPositions(noteItems: Note[], current: Record<string, ElasticGraphPoint> = {}) {
  return Object.fromEntries(noteItems.map((note, index) => {
    const existing = current[note.id]
    const base = existing ?? graphPositions[note.id] ?? graphFallbackPosition(index, noteItems.length)
    return [note.id, { x: base.x, y: base.y, vx: existing?.vx ?? 0, vy: existing?.vy ?? 0 }]
  })) as Record<string, ElasticGraphPoint>
}

function ElasticNoteGraph({ noteItems, activeId, categoryColors, onSelect }: { noteItems: Note[]; activeId: string; categoryColors: Record<string, string>; onSelect: (id: string) => void }) {
  const [positions, setPositions] = useState<Record<string, ElasticGraphPoint>>(() => createElasticPositions(noteItems))
  const positionsRef = useRef(positions)
  const [viewport, setViewport] = useState({ x: 0, y: 0, scale: 1 })
  const [hoveredId, setHoveredId] = useState<string | null>(null)
  const [draggingId, setDraggingId] = useState<string | null>(null)
  const dragTargetRef = useRef<{ x: number; y: number } | null>(null)
  const svgRef = useRef<SVGSVGElement | null>(null)
  const interactionRef = useRef<ElasticGraphInteraction | null>(null)
  const suppressClickRef = useRef(false)

  const model = useMemo(() => {
    const noteIds = new Set(noteItems.map((note) => note.id))
    const relations = noteItems.flatMap((note) => note.relatedIds
      .filter((relatedId) => note.id < relatedId && noteIds.has(relatedId))
      .map((relatedId) => ({ from: note.id, to: relatedId })))
    const degree = Object.fromEntries(noteItems.map((note) => [note.id, new Set(note.relatedIds.filter((id) => noteIds.has(id))).size])) as Record<string, number>
    const maxDegree = Math.max(1, ...Object.values(degree))
    const radii = Object.fromEntries(noteItems.map((note) => {
      const wordScore = Math.sqrt(Math.min(graphWordCount(note), MAX_GRAPH_WORD_COUNT) / MAX_GRAPH_WORD_COUNT)
      const importanceScore = Math.min(degree[note.id] / Math.max(4, maxDegree), 1)
      const score = wordScore * 0.65 + importanceScore * 0.35
      return [note.id, MIN_GRAPH_NODE_RADIUS + score * (MAX_GRAPH_NODE_RADIUS - MIN_GRAPH_NODE_RADIUS)]
    })) as Record<string, number>
    const neighbors = Object.fromEntries(noteItems.map((note) => [note.id, new Set(note.relatedIds.filter((id) => noteIds.has(id)))])) as Record<string, Set<string>>
    return { relations, degree, radii, neighbors }
  }, [noteItems])
  const focusedId = hoveredId ?? draggingId

  useEffect(() => {
    const next = createElasticPositions(noteItems, positionsRef.current)
    positionsRef.current = next
    setPositions(next)
  }, [noteItems])

  useEffect(() => {
    let frame = 0
    let last = performance.now()
    const simulate = (now: number) => {
      const dt = Math.min((now - last) / 1000, 0.035)
      last = now
      const current = positionsRef.current
      if (draggingId && dragTargetRef.current && current[draggingId]) {
        current[draggingId].x = dragTargetRef.current.x
        current[draggingId].y = dragTargetRef.current.y
        current[draggingId].vx = 0
        current[draggingId].vy = 0
      }
      const force = Object.fromEntries(noteItems.map((note) => [note.id, { x: 0, y: 0 }])) as Record<string, { x: number; y: number }>

      noteItems.forEach((a, index) => noteItems.slice(index + 1).forEach((b) => {
        const pa = current[a.id]
        const pb = current[b.id]
        if (!pa || !pb) return
        const dx = pb.x - pa.x
        const dy = pb.y - pa.y
        const distanceSquared = Math.max(dx * dx + dy * dy, 900)
        const distance = Math.sqrt(distanceSquared)
        const nx = dx / distance
        const ny = dy / distance
        const repel = 750000 / distanceSquared
        force[a.id].x -= nx * repel
        force[a.id].y -= ny * repel
        force[b.id].x += nx * repel
        force[b.id].y += ny * repel
        const minimumDistance = model.radii[a.id] + model.radii[b.id] + 28
        if (distance < minimumDistance) {
          const bump = (minimumDistance - distance) * 40
          force[a.id].x -= nx * bump
          force[a.id].y -= ny * bump
          force[b.id].x += nx * bump
          force[b.id].y += ny * bump
        }
      }))

      if (draggingId) {
        const source = current[draggingId]
        if (source) noteItems.forEach((note) => {
          if (note.id === draggingId || model.neighbors[draggingId]?.has(note.id)) return
          const target = current[note.id]
          if (!target) return
          const dx = target.x - source.x
          const dy = target.y - source.y
          const distance = Math.max(1, Math.hypot(dx, dy))
          const range = model.radii[draggingId] + model.radii[note.id] + 54
          if (distance < range) {
            const push = (range - distance) * 20
            force[note.id].x += dx / distance * push
            force[note.id].y += dy / distance * push
          }
        })
      }

      model.relations.forEach(({ from, to }) => {
        const fromPoint = current[from]
        const toPoint = current[to]
        if (!fromPoint || !toPoint) return
        const dx = toPoint.x - fromPoint.x
        const dy = toPoint.y - fromPoint.y
        const distance = Math.max(1, Math.hypot(dx, dy))
        const draggedRelation = draggingId === from || draggingId === to
        const stretch = (distance - 150) * (draggedRelation ? 7.8 : 3.1)
        const nx = dx / distance
        const ny = dy / distance
        force[from].x += nx * stretch
        force[from].y += ny * stretch
        force[to].x -= nx * stretch
        force[to].y -= ny * stretch
        if (draggedRelation && draggingId) {
          const otherId = draggingId === from ? to : from
          force[otherId].x += (current[draggingId].x - current[otherId].x) * 0.04
          force[otherId].y += (current[draggingId].y - current[otherId].y) * 0.04
        }
      })

      noteItems.forEach((note) => {
        const point = current[note.id]
        if (!point || draggingId === note.id) return
        force[note.id].x += (ELASTIC_GRAPH_WIDTH / 2 - point.x) * 0.12
        force[note.id].y += (ELASTIC_GRAPH_HEIGHT / 2 - point.y) * 0.12
        const damping = Math.pow(0.91, dt * 60)
        point.vx = Math.max(-520, Math.min(520, (point.vx + force[note.id].x * dt) * damping))
        point.vy = Math.max(-520, Math.min(520, (point.vy + force[note.id].y * dt) * damping))
        point.x += point.vx * dt
        point.y += point.vy * dt
        const padding = 42
        if (point.x < padding || point.x > ELASTIC_GRAPH_WIDTH - padding) { point.x = Math.max(padding, Math.min(ELASTIC_GRAPH_WIDTH - padding, point.x)); point.vx *= -0.4 }
        if (point.y < padding || point.y > ELASTIC_GRAPH_HEIGHT - padding) { point.y = Math.max(padding, Math.min(ELASTIC_GRAPH_HEIGHT - padding, point.y)); point.vy *= -0.4 }
      })

      setPositions({ ...current })
      frame = window.requestAnimationFrame(simulate)
    }
    frame = window.requestAnimationFrame(simulate)
    return () => window.cancelAnimationFrame(frame)
  }, [draggingId, model, noteItems])

  const pointFromEvent = (event: { clientX: number; clientY: number }) => {
    const rect = svgRef.current?.getBoundingClientRect()
    if (!rect) return { x: 0, y: 0, rawX: 0, rawY: 0 }
    const rawX = (event.clientX - rect.left) / rect.width * ELASTIC_GRAPH_WIDTH
    const rawY = (event.clientY - rect.top) / rect.height * ELASTIC_GRAPH_HEIGHT
    return { x: (rawX - viewport.x) / viewport.scale, y: (rawY - viewport.y) / viewport.scale, rawX, rawY }
  }

  const handlePointerDown = (event: ReactPointerEvent<SVGElement>, noteId?: string) => {
    suppressClickRef.current = false
    const point = pointFromEvent(event)
    const node = noteId ? positionsRef.current[noteId] : null
    interactionRef.current = {
      kind: noteId ? 'node' : 'pan', noteId,
      startX: event.clientX, startY: event.clientY,
      originX: noteId && node ? node.x : viewport.x, originY: noteId && node ? node.y : viewport.y,
      lastX: point.x, lastY: point.y, lastAt: performance.now(),
      offsetX: noteId && node ? point.x - node.x : 0,
      offsetY: noteId && node ? point.y - node.y : 0,
    }
    dragTargetRef.current = noteId && node ? { x: node.x, y: node.y } : null
    setDraggingId(noteId ?? null)
  }

  const handlePointerMove = (event: ReactPointerEvent<SVGSVGElement>) => {
    const interaction = interactionRef.current
    if (!interaction) return
    const rect = svgRef.current?.getBoundingClientRect()
    if (!rect) return
    const point = pointFromEvent(event)
    const deltaX = event.clientX - interaction.startX
    const deltaY = event.clientY - interaction.startY
    if (Math.abs(deltaX) + Math.abs(deltaY) > 3) suppressClickRef.current = true
    if (interaction.kind === 'node' && interaction.noteId) {
      const node = positionsRef.current[interaction.noteId]
      if (!node) return
      const now = performance.now()
      const elapsed = Math.max(16, now - interaction.lastAt)
      node.x = point.x - interaction.offsetX
      node.y = point.y - interaction.offsetY
      dragTargetRef.current = { x: node.x, y: node.y }
      node.vx = Math.max(-480, Math.min(480, (point.x - interaction.lastX) / elapsed * 1000))
      node.vy = Math.max(-480, Math.min(480, (point.y - interaction.lastY) / elapsed * 1000))
      interaction.lastX = point.x
      interaction.lastY = point.y
      interaction.lastAt = now
      setPositions({ ...positionsRef.current })
    } else {
      setViewport((current) => ({ ...current, x: interaction.originX + deltaX / rect.width * ELASTIC_GRAPH_WIDTH, y: interaction.originY + deltaY / rect.height * ELASTIC_GRAPH_HEIGHT }))
    }
  }

  const handlePointerUp = () => {
    const interaction = interactionRef.current
    if (interaction?.kind === 'node' && interaction.noteId && !suppressClickRef.current) onSelect(interaction.noteId)
    interactionRef.current = null
    dragTargetRef.current = null
    setDraggingId(null)
    window.setTimeout(() => { suppressClickRef.current = false }, 0)
  }

  const handleWheel = (event: ReactWheelEvent<SVGSVGElement>) => {
    event.preventDefault()
    const point = pointFromEvent(event)
    const nextScale = Math.max(0.55, Math.min(2.1, viewport.scale - event.deltaY * 0.001))
    setViewport({ x: point.rawX - point.x * nextScale, y: point.rawY - point.y * nextScale, scale: nextScale })
  }

  const resetViewport = () => setViewport({ x: 0, y: 0, scale: 1 })
  const resetLayout = () => {
    const next = Object.fromEntries(noteItems.map((note, index) => {
      const angle = index / Math.max(noteItems.length, 1) * Math.PI * 2 - 0.5
      const radius = 155 + index % 3 * 26
      return [note.id, { x: ELASTIC_GRAPH_WIDTH / 2 + Math.cos(angle) * radius, y: ELASTIC_GRAPH_HEIGHT / 2 + Math.sin(angle) * radius * 0.72, vx: (Math.random() - 0.5) * 120, vy: (Math.random() - 0.5) * 120 }]
    })) as Record<string, ElasticGraphPoint>
    positionsRef.current = next
    setPositions(next)
    resetViewport()
  }
  const zoom = (amount: number) => setViewport((current) => ({ ...current, scale: Math.max(0.55, Math.min(2.1, current.scale + amount)) }))

  return (
    <div className="note-graph elastic-note-graph">
      <div className="graph-tools" aria-label="图谱工具">
        <button type="button" onClick={() => zoom(-0.1)} title="缩小">−</button>
        <button type="button" onClick={resetViewport} title="重置视图">{Math.round(viewport.scale * 100)}%</button>
        <button type="button" onClick={() => zoom(0.1)} title="放大">+</button>
        <button type="button" onClick={resetLayout} title="重新布局">重排</button>
      </div>
      <svg
        ref={svgRef}
        viewBox={`0 0 ${ELASTIC_GRAPH_WIDTH} ${ELASTIC_GRAPH_HEIGHT}`}
        role="img"
        aria-label="笔记关系图谱，可拖动节点和画布"
        onPointerDown={(event) => { event.currentTarget.setPointerCapture(event.pointerId); handlePointerDown(event) }}
        onPointerMove={handlePointerMove}
        onPointerUp={handlePointerUp}
        onPointerCancel={handlePointerUp}
        onWheel={handleWheel}
      >
        <g transform={`translate(${viewport.x} ${viewport.y}) scale(${viewport.scale})`}>
          <g className="graph-links">
            {model.relations.map((relation) => {
              const from = positions[relation.from]
              const to = positions[relation.to]
              if (!from || !to) return null
              const active = focusedId === relation.from || focusedId === relation.to
              const stretched = draggingId === relation.from || draggingId === relation.to
              return <line key={relation.from + relation.to} className={`${active ? 'active' : ''} ${stretched ? 'stretched' : ''}`} x1={from.x} y1={from.y} x2={to.x} y2={to.y} />
            })}
          </g>
          <g className="graph-nodes">
            {noteItems.map((note) => {
              const position = positions[note.id]
              if (!position) return null
              const radius = model.radii[note.id]
              const focused = focusedId === note.id
              return (
                <g
                  key={note.id}
                  data-note-id={note.id}
                  className={`graph-node ${focused ? 'focused' : ''}`}
                  onPointerDown={(event) => { event.stopPropagation(); svgRef.current?.setPointerCapture(event.pointerId); handlePointerDown(event, note.id) }}
                  onPointerEnter={() => setHoveredId(note.id)}
                  onPointerLeave={() => setHoveredId(null)}
                  onClick={(event) => event.preventDefault()}
                  onKeyDown={(event) => event.key === 'Enter' && onSelect(note.id)}
                  role="button"
                  tabIndex={0}
                  aria-label={`${note.title}，${graphWordCount(note)} 字，${model.degree[note.id]} 条关系`}
                  aria-current={activeId === note.id ? 'true' : undefined}
                >
                  <title>{`${graphWordCount(note)} 字 · ${model.degree[note.id]} 条关系`}</title>
                  <circle className="graph-node-halo" cx={position.x} cy={position.y} r={radius + 14} />
                  <circle className="graph-node-core" cx={position.x} cy={position.y} r={radius} fill={categoryColors[note.category] ?? note.color} />
                  <line className="graph-focus-mark" x1={position.x - 8} x2={position.x + 8} y1={position.y - radius - 13} y2={position.y - radius - 13} />
                  <text className="graph-node-title" x={position.x} y={position.y + radius + 18} textAnchor="middle">{note.title.slice(0, 10)}</text>
                  <text className="graph-node-meta" x={position.x} y={position.y + radius + 32} textAnchor="middle">{note.category}</text>
                </g>
              )
            })}
          </g>
        </g>
      </svg>
      <div className="graph-legend">
        {graphLegendItems(noteItems).map((item) => <span key={item.category}><i style={{ backgroundColor: item.color }} />{item.category}</span>)}
      </div>
    </div>
  )
}

/*
type ElasticGraphPoint = { x: number; y: number; vx: number; vy: number }
type ElasticGraphInteraction = {
  kind: 'node' | 'pan'
  noteId?: string
  startX: number
  startY: number
  originX: number
  originY: number
  lastX: number
  lastY: number
  lastAt: number
}

const ELASTIC_GRAPH_WIDTH = 760
const ELASTIC_GRAPH_HEIGHT = 470
const MIN_GRAPH_NODE_RADIUS = 14
const MAX_GRAPH_NODE_RADIUS = 32
const MAX_GRAPH_WORD_COUNT = 3000

function graphWordCount(note: Note) {
  return (note.content ?? note.excerpt ?? '').replace(/\s+/g, '').length
}

function graphFallbackPosition(index: number, count: number) {
  return {
    x: ELASTIC_GRAPH_WIDTH / 2 + Math.cos(index / Math.max(count, 1) * Math.PI * 2) * 250,
    y: ELASTIC_GRAPH_HEIGHT / 2 + Math.sin(index / Math.max(count, 1) * Math.PI * 2) * 165,
  }
}

function createElasticPositions(noteItems: Note[], current: Record<string, ElasticGraphPoint> = {}) {
  return Object.fromEntries(noteItems.map((note, index) => {
    const existing = current[note.id]
    const base = existing ?? graphPositions[note.id] ?? graphFallbackPosition(index, noteItems.length)
    return [note.id, { x: base.x, y: base.y, vx: existing?.vx ?? 0, vy: existing?.vy ?? 0 }]
  })) as Record<string, ElasticGraphPoint>
}

function ElasticNoteGraph({ noteItems, activeId, onSelect }: { noteItems: Note[]; activeId: string; onSelect: (id: string) => void }) {
  const [positions, setPositions] = useState<Record<string, ElasticGraphPoint>>(() => createElasticPositions(noteItems))
  const positionsRef = useRef(positions)
  const [viewport, setViewport] = useState({ x: 0, y: 0, scale: 1 })
  const [hoveredId, setHoveredId] = useState<string | null>(null)
  const [draggingId, setDraggingId] = useState<string | null>(null)
  const svgRef = useRef<SVGSVGElement | null>(null)
  const interactionRef = useRef<ElasticGraphInteraction | null>(null)
  const suppressClickRef = useRef(false)

  const model = useMemo(() => {
    const noteIds = new Set(noteItems.map((note) => note.id))
    const relations = noteItems.flatMap((note) => note.relatedIds
      .filter((relatedId) => note.id < relatedId && noteIds.has(relatedId))
      .map((relatedId) => ({ from: note.id, to: relatedId })))
    const degree = Object.fromEntries(noteItems.map((note) => [note.id, new Set(note.relatedIds.filter((id) => noteIds.has(id))).size])) as Record<string, number>
    const maxDegree = Math.max(1, ...Object.values(degree))
    const radii = Object.fromEntries(noteItems.map((note) => {
      const wordScore = Math.sqrt(Math.min(graphWordCount(note), MAX_GRAPH_WORD_COUNT) / MAX_GRAPH_WORD_COUNT)
      const importanceScore = Math.min(degree[note.id] / Math.max(4, maxDegree), 1)
      const score = wordScore * 0.65 + importanceScore * 0.35
      return [note.id, MIN_GRAPH_NODE_RADIUS + score * (MAX_GRAPH_NODE_RADIUS - MIN_GRAPH_NODE_RADIUS)]
    })) as Record<string, number>
    const neighbors = Object.fromEntries(noteItems.map((note) => [note.id, new Set(note.relatedIds.filter((id) => noteIds.has(id)))])) as Record<string, Set<string>>
    return { relations, degree, radii, neighbors }
  }, [noteItems])
  const focusedId = hoveredId ?? draggingId

  useEffect(() => {
    const next = createElasticPositions(noteItems, positionsRef.current)
    positionsRef.current = next
    setPositions(next)
  }, [noteItems])

  useEffect(() => {
    let frame = 0
    let last = performance.now()
    const simulate = (now: number) => {
      const dt = Math.min((now - last) / 1000, 0.035)
      last = now
      const current = positionsRef.current
      const force = Object.fromEntries(noteItems.map((note) => [note.id, { x: 0, y: 0 }])) as Record<string, { x: number; y: number }>

      noteItems.forEach((a, index) => noteItems.slice(index + 1).forEach((b) => {
        const pa = current[a.id]
        const pb = current[b.id]
        if (!pa || !pb) return
        const dx = pb.x - pa.x
        const dy = pb.y - pa.y
        const distanceSquared = Math.max(dx * dx + dy * dy, 900)
        const distance = Math.sqrt(distanceSquared)
        const nx = dx / distance
        const ny = dy / distance
        const repel = 750000 / distanceSquared
        force[a.id].x -= nx * repel
        force[a.id].y -= ny * repel
        force[b.id].x += nx * repel
        force[b.id].y += ny * repel
        const minimumDistance = model.radii[a.id] + model.radii[b.id] + 28
        if (distance < minimumDistance) {
          const bump = (minimumDistance - distance) * 40
          force[a.id].x -= nx * bump
          force[a.id].y -= ny * bump
          force[b.id].x += nx * bump
          force[b.id].y += ny * bump
        }
      }))

      if (draggingId) {
        const source = current[draggingId]
        if (source) noteItems.forEach((note) => {
          if (note.id === draggingId || model.neighbors[draggingId]?.has(note.id)) return
          const target = current[note.id]
          if (!target) return
          const dx = target.x - source.x
          const dy = target.y - source.y
          const distance = Math.max(1, Math.hypot(dx, dy))
          const range = model.radii[draggingId] + model.radii[note.id] + 54
          if (distance < range) {
            const push = (range - distance) * 20
            force[note.id].x += dx / distance * push
            force[note.id].y += dy / distance * push
          }
        })
      }

      model.relations.forEach(({ from, to }) => {
        const fromPoint = current[from]
        const toPoint = current[to]
        if (!fromPoint || !toPoint) return
        const dx = toPoint.x - fromPoint.x
        const dy = toPoint.y - fromPoint.y
        const distance = Math.max(1, Math.hypot(dx, dy))
        const draggedRelation = draggingId === from || draggingId === to
        const stretch = (distance - 150) * (draggedRelation ? 7.8 : 3.1)
        const nx = dx / distance
        const ny = dy / distance
        force[from].x += nx * stretch
        force[from].y += ny * stretch
        force[to].x -= nx * stretch
        force[to].y -= ny * stretch
        if (draggedRelation && draggingId) {
          const otherId = draggingId === from ? to : from
          force[otherId].x += (current[draggingId].x - current[otherId].x) * 0.04
          force[otherId].y += (current[draggingId].y - current[otherId].y) * 0.04
        }
      })

      noteItems.forEach((note) => {
        const point = current[note.id]
        if (!point || draggingId === note.id) return
        force[note.id].x += (ELASTIC_GRAPH_WIDTH / 2 - point.x) * 0.12
        force[note.id].y += (ELASTIC_GRAPH_HEIGHT / 2 - point.y) * 0.12
        const damping = Math.pow(0.91, dt * 60)
        point.vx = Math.max(-520, Math.min(520, (point.vx + force[note.id].x * dt) * damping))
        point.vy = Math.max(-520, Math.min(520, (point.vy + force[note.id].y * dt) * damping))
        point.x += point.vx * dt
        point.y += point.vy * dt
        const padding = 42
        if (point.x < padding || point.x > ELASTIC_GRAPH_WIDTH - padding) { point.x = Math.max(padding, Math.min(ELASTIC_GRAPH_WIDTH - padding, point.x)); point.vx *= -0.4 }
        if (point.y < padding || point.y > ELASTIC_GRAPH_HEIGHT - padding) { point.y = Math.max(padding, Math.min(ELASTIC_GRAPH_HEIGHT - padding, point.y)); point.vy *= -0.4 }
      })

      setPositions({ ...current })
      frame = window.requestAnimationFrame(simulate)
    }
    frame = window.requestAnimationFrame(simulate)
    return () => window.cancelAnimationFrame(frame)
  }, [draggingId, model, noteItems])

  const pointFromEvent = (event: { clientX: number; clientY: number }) => {
    const rect = svgRef.current?.getBoundingClientRect()
    if (!rect) return { x: 0, y: 0, rawX: 0, rawY: 0 }
    const rawX = (event.clientX - rect.left) / rect.width * ELASTIC_GRAPH_WIDTH
    const rawY = (event.clientY - rect.top) / rect.height * ELASTIC_GRAPH_HEIGHT
    return { x: (rawX - viewport.x) / viewport.scale, y: (rawY - viewport.y) / viewport.scale, rawX, rawY }
  }

  const handlePointerDown = (event: ReactPointerEvent<SVGElement>, noteId?: string) => {
    suppressClickRef.current = false
    const point = pointFromEvent(event)
    const node = noteId ? positionsRef.current[noteId] : null
    interactionRef.current = {
      kind: noteId ? 'node' : 'pan', noteId,
      startX: event.clientX, startY: event.clientY,
      originX: noteId && node ? node.x : viewport.x, originY: noteId && node ? node.y : viewport.y,
      lastX: point.x, lastY: point.y, lastAt: performance.now(),
    }
    setDraggingId(noteId ?? null)
  }

  const handlePointerMove = (event: ReactPointerEvent<SVGSVGElement>) => {
    const interaction = interactionRef.current
    if (!interaction) return
    const rect = svgRef.current?.getBoundingClientRect()
    if (!rect) return
    const point = pointFromEvent(event)
    const deltaX = event.clientX - interaction.startX
    const deltaY = event.clientY - interaction.startY
    if (Math.abs(deltaX) + Math.abs(deltaY) > 3) suppressClickRef.current = true
    if (interaction.kind === 'node' && interaction.noteId) {
      const node = positionsRef.current[interaction.noteId]
      if (!node) return
      const now = performance.now()
      const elapsed = Math.max(16, now - interaction.lastAt)
      node.x = interaction.originX + deltaX / rect.width * ELASTIC_GRAPH_WIDTH / viewport.scale
      node.y = interaction.originY + deltaY / rect.height * ELASTIC_GRAPH_HEIGHT / viewport.scale
      node.vx = Math.max(-480, Math.min(480, (point.x - interaction.lastX) / elapsed * 1000))
      node.vy = Math.max(-480, Math.min(480, (point.y - interaction.lastY) / elapsed * 1000))
      interaction.lastX = point.x
      interaction.lastY = point.y
      interaction.lastAt = now
      setPositions({ ...positionsRef.current })
    } else {
      setViewport((current) => ({ ...current, x: interaction.originX + deltaX / rect.width * ELASTIC_GRAPH_WIDTH, y: interaction.originY + deltaY / rect.height * ELASTIC_GRAPH_HEIGHT }))
    }
  }

  const handlePointerUp = () => {
    const interaction = interactionRef.current
    if (interaction?.kind === 'node' && interaction.noteId && !suppressClickRef.current) onSelect(interaction.noteId)
    interactionRef.current = null
    setDraggingId(null)
    window.setTimeout(() => { suppressClickRef.current = false }, 0)
  }

  const handleWheel = (event: ReactWheelEvent<SVGSVGElement>) => {
    event.preventDefault()
    const point = pointFromEvent(event)
    const nextScale = Math.max(0.55, Math.min(2.1, viewport.scale - event.deltaY * 0.001))
    setViewport({ x: point.rawX - point.x * nextScale, y: point.rawY - point.y * nextScale, scale: nextScale })
  }

  const resetViewport = () => setViewport({ x: 0, y: 0, scale: 1 })
  const resetLayout = () => {
    const next = Object.fromEntries(noteItems.map((note, index) => {
      const angle = index / Math.max(noteItems.length, 1) * Math.PI * 2 - 0.5
      const radius = 155 + index % 3 * 26
      return [note.id, { x: ELASTIC_GRAPH_WIDTH / 2 + Math.cos(angle) * radius, y: ELASTIC_GRAPH_HEIGHT / 2 + Math.sin(angle) * radius * 0.72, vx: (Math.random() - 0.5) * 120, vy: (Math.random() - 0.5) * 120 }]
    })) as Record<string, ElasticGraphPoint>
    positionsRef.current = next
    setPositions(next)
    resetViewport()
  }
  const zoom = (amount: number) => setViewport((current) => ({ ...current, scale: Math.max(0.55, Math.min(2.1, current.scale + amount)) }))

  return (
    <div className="note-graph elastic-note-graph">
      <div className="graph-tools" aria-label="图谱工具">
        <button type="button" onClick={() => zoom(-0.1)} title="缩小">−</button>
        <button type="button" onClick={resetViewport} title="重置视图">{Math.round(viewport.scale * 100)}%</button>
        <button type="button" onClick={() => zoom(0.1)} title="放大">+</button>
        <button type="button" onClick={resetLayout} title="重新布局">重排</button>
      </div>
      <svg
        ref={svgRef}
        viewBox={`0 0 ${ELASTIC_GRAPH_WIDTH} ${ELASTIC_GRAPH_HEIGHT}`}
        role="img"
        aria-label="笔记关系图谱，可拖动节点和画布"
        onPointerDown={(event) => { event.currentTarget.setPointerCapture(event.pointerId); handlePointerDown(event) }}
        onPointerMove={handlePointerMove}
        onPointerUp={handlePointerUp}
        onPointerCancel={handlePointerUp}
        onWheel={handleWheel}
      >
        <g transform={`translate(${viewport.x} ${viewport.y}) scale(${viewport.scale})`}>
          <g className="graph-links">
            {model.relations.map((relation) => {
              const from = positions[relation.from]
              const to = positions[relation.to]
              if (!from || !to) return null
              const active = focusedId === relation.from || focusedId === relation.to
              const stretched = draggingId === relation.from || draggingId === relation.to
              return <line key={relation.from + relation.to} className={`${active ? 'active' : ''} ${stretched ? 'stretched' : ''}`} x1={from.x} y1={from.y} x2={to.x} y2={to.y} />
            })}
          </g>
          <g className="graph-nodes">
            {noteItems.map((note) => {
              const position = positions[note.id]
              if (!position) return null
              const radius = model.radii[note.id]
              const focused = focusedId === note.id
              return (
                <g
                  key={note.id}
                  data-note-id={note.id}
                  className={`graph-node ${focused ? 'focused' : ''}`}
                  onPointerDown={(event) => { event.stopPropagation(); svgRef.current?.setPointerCapture(event.pointerId); handlePointerDown(event, note.id) }}
                  onPointerEnter={() => setHoveredId(note.id)}
                  onPointerLeave={() => setHoveredId(null)}
                  onClick={(event) => event.preventDefault()}
                  onKeyDown={(event) => event.key === 'Enter' && onSelect(note.id)}
                  role="button"
                  tabIndex={0}
                  aria-label={`${note.title}，${graphWordCount(note)} 字，${model.degree[note.id]} 条关系`}
                  aria-current={activeId === note.id ? 'true' : undefined}
                >
                  <title>{`${graphWordCount(note)} 字 · ${model.degree[note.id]} 条关系`}</title>
                  <circle className="graph-node-halo" cx={position.x} cy={position.y} r={radius + 14} />
                  <circle className="graph-node-core" cx={position.x} cy={position.y} r={radius} fill={note.color} />
                  <line className="graph-focus-mark" x1={position.x - 8} x2={position.x + 8} y1={position.y - radius - 13} y2={position.y - radius - 13} />
                  <text className="graph-node-title" x={position.x} y={position.y + radius + 18} textAnchor="middle">{note.title.slice(0, 10)}</text>
                  <text className="graph-node-meta" x={position.x} y={position.y + radius + 32} textAnchor="middle">{note.category}</text>
                </g>
              )
            })}
          </g>
        </g>
      </svg>
      <div className="graph-legend">
        {Array.from(new Set(noteItems.map((note) => note.category))).map((category) => (
          <span key={category}><i style={{ backgroundColor: categoryColors[category] }} />{category}</span>
        ))}
      </div>
    </div>
  )
}
*/

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
  const [categoryColorOverrides, setCategoryColorOverrides] = useState<Record<string, string>>(loadCategoryColors)
  const [imageUploading, setImageUploading] = useState(false)

  useEffect(() => {
    if (selectedNoteId) {
      setActiveId(selectedNoteId)
      setMode('列表')
    }
  }, [selectedNoteId])

  useEffect(() => {
    const ids = new Set(noteItems.map((note) => note.id))
    setSelectedIds((current) => current.filter((id) => ids.has(id)))
  }, [noteItems])

  const categories = useMemo(() => ['全部笔记', ...Array.from(new Set(noteItems.map((note) => note.category)))], [noteItems])
  const categoryColors = useMemo(() => Object.fromEntries(categories.slice(1).map((category, index) => [
    category,
    categoryColorOverrides[category] ?? CATEGORY_COLOR_PALETTE[index % CATEGORY_COLOR_PALETTE.length],
  ])) as Record<string, string>, [categories, categoryColorOverrides])
  const displayNotes = useMemo(() => noteItems.map((note) => ({ ...note, color: categoryColors[note.category] ?? note.color })), [categoryColors, noteItems])
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
        const currentContent = activeNote.content ?? activeNote.excerpt
        const imageMarkdown = `\n![${file.name}](${result.url})\n`
        updateActiveNote({
          content: currentContent + imageMarkdown,
          excerpt: markdownExcerpt(currentContent + imageMarkdown),
          updatedAt: '刚刚',
        })
      } catch (cause) {
        alert('图片上传失败：' + (cause instanceof Error ? cause.message : '未知错误'))
      } finally {
        setImageUploading(false)
      }
    }
    input.click()
  }

  const updateMarkdown = (value: string) => updateActiveNote({ content: value, excerpt: markdownExcerpt(value), updatedAt: '刚刚' })

  const createNote = (fields: NoteDraftFields) => {
    const next = onCreate(fields)
    setActiveId(next.id)
    setMode('列表')
    setNoteDialogOpen(false)
  }

  const updateCategoryColor = (category: string, color: string) => {
    setCategoryColorOverrides((current) => {
      const next = { ...current, [category]: color }
      try { window.localStorage.setItem(CATEGORY_COLOR_STORAGE_KEY, JSON.stringify(next)) }
      catch { /* Keep the current session working when storage is unavailable. */ }
      return next
    })
  }

  const createCategory = (category: string, color: string) => {
    const next: Note = {
      id: 'n-' + Date.now(),
      title: '未命名笔记',
      category,
      excerpt: '',
      updatedAt: '刚刚',
      color,
      relatedIds: [],
      source: '个人创建',
    }
    updateCategoryColor(category, color)
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
          <div className={`note-category-row ${category === '全部笔记' ? 'all-notes' : ''}`} key={category}>
            <button
              type="button"
              className={activeCategory === category ? 'active' : ''}
              onClick={() => { setActiveCategory(category); setSelectedIds([]); setMode('列表') }}
            >
              <FolderOpen size={16} style={category === '全部笔记' ? undefined : { color: categoryColors[category] }} />
              <span>{category}</span>
              <b>{category === '全部笔记' ? noteItems.length : noteItems.filter((note) => note.category === category).length}</b>
            </button>
            {category !== '全部笔记' && (
              <input
                className="note-category-color"
                type="color"
                value={categoryColors[category]}
                onChange={(event) => updateCategoryColor(category, event.target.value)}
                aria-label={`设置${category}的节点颜色`}
                title={`设置${category}的节点颜色`}
              />
            )}
          </div>
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
            <ElasticNoteGraph noteItems={displayNotes} activeId={activeId} categoryColors={categoryColors} onSelect={(id) => { setActiveId(id); setMode('列表') }} />
          </div>
        ) : (
          <div className="notes-layout">
            <div className="note-list">
              {visibleNotes.map((note) => {
                const noteColor = categoryColors[note.category] ?? note.color
                return (
                <div className={`note-list-item ${activeId === note.id ? 'active' : ''}`} key={note.id}>
                  <input className="batch-checkbox" type="checkbox" checked={selectedIds.includes(note.id)} onChange={() => toggleSelection(note.id)} aria-label={`选择笔记：${note.title}`} />
                  <button className="note-list-open" type="button" onClick={() => setActiveId(note.id)}>
                    <span className="note-list-top"><i style={{ backgroundColor: noteColor }} />{note.category}<small>{note.updatedAt}</small></span>
                    <strong>{note.title}</strong>
                    <p>{note.excerpt || '开始记录你的想法……'}</p>
                    <span className="note-source"><Link2 size={12} />{note.source}</span>
                  </button>
                </div>
                )
              })}
            </div>
            <article className="note-editor">
              <div className="note-editor-meta">
                <label><Tag size={14} /><select value={activeNote.category} onChange={(event) => updateActiveNote({ category: event.target.value })}>{categories.slice(1).map((category) => <option key={category}>{category}</option>)}</select></label>
                <span>{activeNote.updatedAt}</span>
                <div className="note-editor-mode" role="tablist" aria-label="笔记编辑模式">
                  <button type="button" className={editorMode === 'edit' ? 'active' : ''} onClick={() => setEditorMode('edit')} role="tab" aria-selected={editorMode === 'edit'}><Pencil size={13} /> 编辑</button>
                  <button type="button" className={editorMode === 'preview' ? 'active' : ''} onClick={() => setEditorMode('preview')} role="tab" aria-selected={editorMode === 'preview'}><Eye size={13} /> 预览</button>
                </div>
                <button className="secondary-button" type="button" onClick={handleImageUpload} disabled={imageUploading} title="上传图片"><Image size={14} /> {imageUploading ? '上传中' : '图片'}</button>
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
                    return related ? <button type="button" onClick={() => setActiveId(related.id)} key={id}><i style={{ backgroundColor: categoryColors[related.category] ?? related.color }} />{related.title}</button> : null
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
