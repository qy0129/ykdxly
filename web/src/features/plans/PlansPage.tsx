import { useEffect, useMemo, useState } from 'react'
import { CheckCircle2, Circle, PenLine, Plus, Target, Trash2 } from 'lucide-react'
import type { CalendarItem, Plan, PlanTask } from '../../types/planner'
import { PlanDialog } from '../../components/dialogs/PlannerDialogs'

const statusLabel: Record<string, string> = {
  active: '进行中',
  paused: '已暂停',
  completed: '已完成',
}

/** 饮食计划识别：按标题特征，用于独立展示"健康饮食"板块。 */
export function isDietPlan(plan?: Plan | null) {
  return Boolean(plan && /饮食|减脂|健康餐|增肌餐|控糖/.test(plan.title))
}

function todayString() {
  const now = new Date()
  const local = new Date(now.getTime() - now.getTimezoneOffset() * 60000)
  return local.toISOString().slice(0, 10)
}

function dayLabel(date: string) {
  if (date === todayString()) return '今天'
  const yesterday = new Date()
  yesterday.setDate(yesterday.getDate() - 1)
  const yStr = new Date(yesterday.getTime() - yesterday.getTimezoneOffset() * 60000).toISOString().slice(0, 10)
  if (date === yStr) return '昨天'
  return date
}

/** 计划页按学习目标的排版展示：计划卡片 → 点开查看按日期分组的每日任务。 */
export function PlansPage({
  plansData, calendarItems, onUpdatePlan, onDeletePlans,
  onAddSchedule, onOpenSchedule, onToggle, onCreatePlan,
  pendingPlanId, onConsumePendingPlan,
}: {
  plansData: Plan[]
  calendarItems: CalendarItem[]
  onUpdatePlan: (plan: Plan) => Promise<boolean | void> | boolean | void
  onDeletePlans: (ids: string[]) => Promise<boolean>
  onAddSchedule: (planId: string) => void
  onOpenSchedule: (item: CalendarItem) => void
  onToggle: (id: string) => void
  onCreatePlan: () => void
  pendingPlanId?: string | null
  onConsumePendingPlan?: () => void
}) {
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [editingPlan, setEditingPlan] = useState(false)

  // 侧栏/新建计划传入的信号：跳到对应计划详情；消费后清除，不影响初始网格视图。
  useEffect(() => {
    if (pendingPlanId) {
      setSelectedId(pendingPlanId)
      onConsumePendingPlan?.()
    }
  }, [pendingPlanId, onConsumePendingPlan])

  const plan = plansData.find((item) => item.id === selectedId) ?? null

  // 每日任务来自该计划的日程，按日期分组；并关联到真实 PlanTask 以展示任务标题与详细说明。
  const taskById = useMemo(() => {
    const map = new Map<string, PlanTask>()
    for (const stage of plan?.items ?? []) {
      for (const task of stage.tasks) map.set(task.id, task)
    }
    return map
  }, [plan])
  const daySections = useMemo(() => {
    if (!plan) return []
    const map = new Map<string, CalendarItem[]>()
    for (const item of calendarItems) {
      if (item.planId !== plan.id) continue
      const list = map.get(item.date) ?? []
      list.push(item)
      map.set(item.date, list)
    }
    return [...map.entries()]
      .sort((left, right) => left[0].localeCompare(right[0]))
      .map(([date, list]) => ({
        date,
        items: [...list].sort((left, right) => left.time.localeCompare(right.time)),
      }))
  }, [plan, calendarItems])

  // 卡片列表视图
  if (!plan) {
    return (
      <div className="learning-page content-page">
        <section className="learning-header">
          <div>
            <span className="eyebrow">计划</span>
            <h2>计划</h2>
            <p>点开计划查看每天的安排和进度</p>
          </div>
          <div className="learning-header-actions">
            {plansData.length > 0 && (
              <button className="secondary-button danger-text" type="button"
                onClick={() => void onDeletePlans(plansData.map((item) => item.id))}>
                删除全部
              </button>
            )}
            <button className="primary-button" type="button" onClick={onCreatePlan}><Plus size={16} /> 新建计划</button>
          </div>
        </section>

        {plansData.length === 0 && (
          <div className="learning-empty">
            <Target size={40} />
            <p>还没有计划</p>
            <p>去「AI 对话」里说一句“帮我制定一个学习计划”</p>
          </div>
        )}

        <section className="learning-grid">
          {plansData.map((item) => (
            <div className="learning-card" key={item.id}>
              <button className="learning-card-body" type="button" onClick={() => setSelectedId(item.id)}>
                <div className="learning-card-head">
                  <strong>{item.title}</strong>
                  <span className={`learning-status learning-status-${item.status}`}>{statusLabel[item.status] ?? item.status}</span>
                </div>
                <p className="learning-domain">{item.subtitle || '长期计划'}</p>
                <dl className="learning-meta">
                  {item.dueDate ? <div><dt>目标日期</dt><dd>{item.dueDate}</dd></div> : null}
                  <div><dt>任务</dt><dd>{item.completedTasks} / {item.totalTasks}</dd></div>
                </dl>
                <div className="learning-progress">
                  <div className="learning-progress-track">
                    <div className="learning-progress-bar" style={{ width: `${Math.min(100, Math.round(item.taskProgress))}%` }} />
                  </div>
                  <span>{Math.round(item.taskProgress)}%</span>
                </div>
              </button>
              <button className="learning-card-delete" type="button" onClick={() => void onDeletePlans([item.id])} title="删除计划" aria-label={`删除计划：${item.title}`}>
                <Trash2 size={14} />
              </button>
            </div>
          ))}
        </section>
      </div>
    )
  }

  // 详情视图：每天具体干的事情
  return (
    <div className="learning-page content-page">
      <section className="learning-header">
        <div>
          <span className="eyebrow">计划</span>
          <h2>{plan.title}</h2>
          <p>{plan.subtitle || '每日任务'}</p>
        </div>
        <div className="learning-header-actions">
          <button className="secondary-button" type="button" onClick={() => setEditingPlan(true)}><PenLine size={16} /> 编辑</button>
          <button className="primary-button" type="button" onClick={() => onAddSchedule(plan.id)}><Plus size={16} /> 添加安排</button>
          <button className="secondary-button danger-text" type="button" onClick={() => void onDeletePlans([plan.id])}><Trash2 size={16} /> 删除计划</button>
        </div>
      </section>

      <div className="learning-detail">
        <div className="learning-detail-head">
          <span className={`learning-status learning-status-${plan.status}`}>{statusLabel[plan.status] ?? plan.status}</span>
          <span className="learning-domain">共 {plan.totalTasks} 项任务 · 已完成 {plan.completedTasks}</span>
          {plan.dueDate && <span className="learning-domain">目标日期：{plan.dueDate}</span>}
        </div>

        <section className="learning-detail-section">
          <h3>进度</h3>
          <div className="learning-progress">
            <div className="learning-progress-track">
              <div className="learning-progress-bar" style={{ width: `${Math.min(100, Math.round(plan.taskProgress))}%` }} />
            </div>
            <span>{Math.round(plan.taskProgress)}%</span>
          </div>
        </section>

        <section className="learning-detail-section">
          <h3>阶段分层（{plan.items.length} 个阶段）</h3>
          {plan.items.length === 0 && <p className="learning-empty-hint">还没有阶段。</p>}
          <div className="plan-stage-hierarchy">
            {plan.items.map((stage, index) => (
              <article className="plan-stage-node" key={stage.id}>
                <div className="plan-stage-head">
                  <span className="plan-stage-index">{String(index + 1).padStart(2, '0')}</span>
                  <strong>{stage.title}</strong>
                  <em>{Math.round(stage.taskProgress)}%</em>
                </div>
                <div className="plan-stage-track"><i style={{ width: `${Math.min(100, Math.round(stage.taskProgress))}%` }} /></div>
                <div className="plan-stage-tasks">
                  {stage.tasks.map((task) => (
                    <span className={`plan-stage-task ${task.status === 'done' ? 'done' : ''}`} key={task.id}>
                      {task.title}
                      {task.description ? <em>{task.description}</em> : null}
                    </span>
                  ))}
                  {stage.tasks.length === 0 && <small className="learning-empty-hint">本阶段暂无任务</small>}
                </div>
              </article>
            ))}
          </div>
        </section>

        <section className="learning-detail-section">
          <h3>每日任务（{daySections.length} 天）</h3>
          {daySections.length === 0 && (
            <p className="learning-empty-hint">还没有排期任务，点击「添加安排」为计划安排每日任务。</p>
          )}
          <div className="learning-days">
            {daySections.map((day) => (
              <article className={`learning-day${day.date === todayString() ? ' today' : ''}`} key={day.date}>
                <div className="learning-day-head"><strong>{dayLabel(day.date)}</strong><span>{day.date}</span></div>
                {day.items.map((item) => {
                  const task = item.taskId ? taskById.get(item.taskId) : undefined
                  const title = task?.title || item.title
                  const detail = task?.description || ''
                  const meta = `${item.time} · ${item.duration} 分钟`
                  return (
                    <div className={`learning-task-row${item.status === 'done' ? ' is-done' : ''}`} key={item.id}>
                      <button
                        className="learning-task-check"
                        type="button"
                        onClick={() => onToggle(item.id)}
                        aria-label={item.status === 'done' ? `恢复完成：${title}` : `完成任务：${title}`}
                        title={item.status === 'done' ? '标记为未完成' : '标记为完成'}
                      >
                        {item.status === 'done' ? <CheckCircle2 size={16} /> : <Circle size={16} />}
                      </button>
                      <button className="learning-task-body" type="button" onClick={() => onOpenSchedule(item)} title="打开当日安排">
                        <span>{title}</span>
                        <p>{[detail, meta].filter(Boolean).join(' · ')}</p>
                      </button>
                    </div>
                  )
                })}
              </article>
            ))}
          </div>
        </section>
      </div>

      {editingPlan && (
        <PlanDialog
          initial={plan}
          onClose={() => setEditingPlan(false)}
          onSubmit={async (next) => {
            const ok = await onUpdatePlan(next)
            if (ok !== false) setEditingPlan(false)
          }}
        />
      )}
    </div>
  )
}
