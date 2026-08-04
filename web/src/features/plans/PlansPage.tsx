import { useEffect, useState } from 'react'
import { format } from 'date-fns'
import { CheckCircle2, Circle, Clock3, PenLine, Plus, Sparkles, Trash2, X } from 'lucide-react'
import type { CalendarItem, Plan, PlanItem, PlanTask } from '../../types/planner'
import { ProgressRing } from '../../components/ui/ProgressRing'
import { PlanDialog, StageDialog, TaskDialog, type StageDraftFields, type TaskDraftFields } from '../../components/dialogs/PlannerDialogs'

/** 计划页按阶段展示可执行任务；阶段和计划进度均由任务状态计算。 */
export function PlansPage({
  activePlanId, plansData, calendarItems, onPlanChange, onUpdatePlan, onDeletePlans,
  onCreateStage, onUpdateStage, onDeleteStage, onCreateTask, onUpdateTask,
  onDeleteTask, onDeleteTasks, onAddSchedule, onOpenSchedule, onAiAdjust,
}: {
  activePlanId: string
  plansData: Plan[]
  calendarItems: CalendarItem[]
  onPlanChange: (id: string) => void
  onUpdatePlan: (plan: Plan) => Promise<boolean | void> | boolean | void
  onDeletePlans: (ids: string[]) => Promise<boolean>
  onCreateStage: (planId: string, item: PlanItem) => void
  onUpdateStage: (planId: string, item: PlanItem) => void
  onDeleteStage: (planId: string, item: PlanItem) => void
  onCreateTask: (planId: string, stageId: string, fields: TaskDraftFields) => Promise<boolean>
  onUpdateTask: (task: PlanTask) => Promise<boolean>
  onDeleteTask: (task: PlanTask) => void
  onDeleteTasks: (tasks: PlanTask[]) => Promise<boolean>
  onAddSchedule: (planId: string) => void
  onOpenSchedule: (item: CalendarItem) => void
  onAiAdjust: (plan: Plan) => void
}) {
  const plan = plansData.find((item) => item.id === activePlanId) ?? plansData[0]
  const [editingPlan, setEditingPlan] = useState(false)
  const [stageDialog, setStageDialog] = useState<{ item?: PlanItem } | null>(null)
  const [taskDialog, setTaskDialog] = useState<{ stageId: string; task?: PlanTask } | null>(null)
  const [selectedTaskIds, setSelectedTaskIds] = useState<string[]>([])
  const [selectedPlanIds, setSelectedPlanIds] = useState<string[]>([])
  const [deletingPlans, setDeletingPlans] = useState(false)

  useEffect(() => {
    const taskIds = new Set(plan?.items.flatMap((stage) => stage.tasks.map((task) => task.id)) ?? [])
    setSelectedTaskIds((current) => current.filter((id) => taskIds.has(id)))
  }, [plan])

  useEffect(() => {
    const planIds = new Set(plansData.map((item) => item.id))
    setSelectedPlanIds((current) => current.filter((id) => planIds.has(id)))
  }, [plansData])

  if (!plan) return <div className="content-page simple-empty">还没有长期计划，先创建一个计划吧。</div>
  const today = format(new Date(), 'yyyy-MM-dd')
  const nextItems = calendarItems
    .filter((item) => item.planId === plan.id && item.status !== 'done')
    .filter((item) => item.date >= today)
    .sort((left, right) => `${left.date}T${left.time}`.localeCompare(`${right.date}T${right.time}`))
    .slice(0, 5)
  const allTasks = plan.items.flatMap((stage) => stage.tasks)
  const allPlansSelected = plansData.length > 0 && plansData.every((item) => selectedPlanIds.includes(item.id))

  const toggleTaskSelection = (taskId: string) => {
    setSelectedTaskIds((current) => current.includes(taskId)
      ? current.filter((id) => id !== taskId)
      : [...current, taskId])
  }

  const deleteSelectedTasks = async () => {
    const tasks = allTasks.filter((task) => selectedTaskIds.includes(task.id))
    if (tasks.length > 0 && await onDeleteTasks(tasks)) setSelectedTaskIds([])
  }

  const deleteSelectedPlans = async () => {
    if (deletingPlans || selectedPlanIds.length === 0) return
    setDeletingPlans(true)
    try {
      if (await onDeletePlans(selectedPlanIds)) setSelectedPlanIds([])
    } finally {
      setDeletingPlans(false)
    }
  }

  const savePlanDialog = async (next: Plan) => {
    const result = await onUpdatePlan(next)
    if (result !== false) setEditingPlan(false)
  }

  const startStageEdit = (item: PlanItem) => {
    setStageDialog({ item })
  }

  const saveStageDialog = (fields: StageDraftFields) => {
    if (stageDialog?.item) {
      onUpdateStage(plan.id, { ...stageDialog.item, title: fields.title, dueLabel: fields.dueLabel || '待安排' })
    } else {
      onCreateStage(plan.id, { id: `stage-${Date.now()}`, title: fields.title, dueLabel: fields.dueLabel || '待安排', progress: 0, taskProgress: 0, effortProgress: 0, version: 0, tasks: [] })
    }
    setStageDialog(null)
  }

  const createTask = async (fields: TaskDraftFields) => {
    if (!taskDialog) return false
    if (taskDialog.task) {
      const updated = await onUpdateTask({ ...taskDialog.task, ...fields })
      if (updated) setTaskDialog(null)
      return updated
    }
    // 创建失败时保留弹窗，避免用户填写的任务内容直接消失。
    const created = await onCreateTask(plan.id, taskDialog.stageId, fields)
    if (created) setTaskDialog(null)
    return created
  }

  const frequencyLabel = (task: PlanTask) => ({
    once: '仅一次',
    daily: '每天',
    every_other_day: '隔一天',
    weekdays: '工作日',
    weekly: '每周',
  }[task.recurrenceType] ?? '仅一次')

  return (
    <div className="plans-page content-page">
      <div className="plans-list-toolbar">
        <div><span className="eyebrow">长期计划</span><strong>{plansData.length} 项计划</strong></div>
        <div className="batch-actions">
          {plansData.length > 0 && <label className="batch-select"><input className="batch-checkbox" type="checkbox" checked={allPlansSelected} onChange={() => setSelectedPlanIds(allPlansSelected ? [] : plansData.map((item) => item.id))} />全选计划</label>}
          {selectedPlanIds.length > 0 && <button className="secondary-button danger-text" type="button" onClick={() => void deleteSelectedPlans()} disabled={deletingPlans}><Trash2 size={15} /> {deletingPlans ? '删除中…' : `删除已选（${selectedPlanIds.length}）`}</button>}
        </div>
      </div>
      <div className="plans-switcher" role="tablist" aria-label="选择长期计划">
        {plansData.map((item) => <div className="plan-switcher-item" key={item.id}>
          <input className="batch-checkbox" type="checkbox" checked={selectedPlanIds.includes(item.id)} onChange={() => setSelectedPlanIds((current) => current.includes(item.id) ? current.filter((id) => id !== item.id) : [...current, item.id])} aria-label={`选择计划：${item.title}`} />
          <button type="button" role="tab" aria-selected={item.id === plan.id} className={item.id === plan.id ? 'active' : ''} onClick={() => { setSelectedTaskIds([]); onPlanChange(item.id) }}><i style={{ backgroundColor: item.color }} /><span>{item.title}</span><b>{item.taskProgress}%</b></button>
        </div>)}
      </div>

      <section className="plan-overview">
        <div className="plan-overview-main">
          <ProgressRing value={plan.taskProgress} color={plan.color} size={76} />
          <div><span className="eyebrow">长期计划</span><h2>{plan.title}</h2><p>{plan.completedTasks} 项已完成，共 {plan.totalTasks} 项 · 目标日期 {plan.dueDate || '待安排'}</p><small>执行完成率 {plan.taskProgress}% · 预计工时完成率 {plan.effortProgress}%</small></div>
        </div>
        <div className="overview-actions">
          <button className="secondary-button" type="button" onClick={() => onAiAdjust(plan)}><Sparkles size={17} /> AI 调整</button>
          <button className="secondary-button" type="button" onClick={() => setEditingPlan(true)}><PenLine size={16} /> 编辑计划</button>
          <button className="primary-button" type="button" onClick={() => onAddSchedule(plan.id)}><Plus size={17} /> 添加安排</button>
        </div>
      </section>

      <div className="plan-detail-grid">
        <section className="plan-stages">
          <div className="section-heading">
            <div><span className="eyebrow">阶段与任务</span><h3>推进路径</h3></div>
            <div className="section-heading-actions">
              {selectedTaskIds.length > 0 && <button className="secondary-button danger-text" type="button" onClick={() => void deleteSelectedTasks()}><Trash2 size={15} /> 删除已选（{selectedTaskIds.length}）</button>}
              <button className="secondary-button" type="button" onClick={() => setStageDialog({})}><Plus size={15} /> 添加阶段</button>
            </div>
          </div>
          {plan.items.map((stage, index) => <section className="stage-task-group" key={stage.id}>
            <article className="stage-row">
              <span className="stage-index">{String(index + 1).padStart(2, '0')}</span>
              <div className="stage-copy"><strong>{stage.title}</strong><span>{stage.dueLabel} · 执行 {stage.taskProgress}% · 工时 {stage.effortProgress}%</span><div className="stage-track"><i style={{ width: `${stage.taskProgress}%`, backgroundColor: plan.color }} /></div></div>
              <span className="row-actions"><b>{stage.taskProgress}%</b><button className="icon-button" type="button" onClick={() => startStageEdit(stage)} title="编辑阶段"><PenLine size={14} /></button><button className="icon-button danger-icon" type="button" onClick={() => onDeleteStage(plan.id, stage)} title="删除阶段"><X size={14} /></button></span>
            </article>
            <div className="plan-task-list">
              {stage.tasks.map((task) => <article className={`plan-task-row ${task.status}`} key={task.id}>
                <button className={`icon-button task-select-button ${selectedTaskIds.includes(task.id) ? 'selected' : ''}`} type="button" onClick={() => toggleTaskSelection(task.id)} title={selectedTaskIds.includes(task.id) ? '取消选择' : '选择任务'} aria-pressed={selectedTaskIds.includes(task.id)}>{selectedTaskIds.includes(task.id) ? <CheckCircle2 size={18} /> : <Circle size={18} />}</button>
                <div><strong>{task.title}</strong><span>{frequencyLabel(task)} · {task.estimatedMinutes ? `每次 ${task.estimatedMinutes} 分钟` : '未估时'}{task.scheduledTime ? ` · ${task.scheduledTime}` : ''}{task.reason ? ` · ${task.reason}` : ''}</span></div>
                <span className="task-status">{task.scheduleCount > 0 ? `${task.completedScheduleCount}/${task.scheduleCount} · ${task.scheduleProgress}%` : task.status}</span>
                <button className="icon-button" type="button" onClick={() => setTaskDialog({ stageId: stage.id, task })} title="编辑任务"><PenLine size={15} /></button>
                <button className="icon-button danger-icon" type="button" onClick={() => onDeleteTask(task)} title="移入回收站"><X size={15} /></button>
              </article>)}
              <button className="text-button task-add-button" type="button" onClick={() => setTaskDialog({ stageId: stage.id })}><Plus size={14} /> 添加任务</button>
            </div>
          </section>)}
        </section>

        <section className="next-actions">
          <div className="section-heading"><div><span className="eyebrow">接下来</span><h3>近期安排</h3></div></div>
          {nextItems.map((item) => <button className="next-action-row" type="button" onClick={() => onOpenSchedule(item)} key={item.id}><Clock3 size={19} /><div><strong>{item.title}</strong><span>{item.date} · {item.time}{item.taskId ? ' · 已关联任务' : ''}</span></div><span>{item.duration}m</span></button>)}
          {nextItems.length === 0 && <div className="simple-empty">该计划暂时没有近期安排。</div>}
        </section>
      </div>

      {editingPlan && <PlanDialog initial={plan} onClose={() => setEditingPlan(false)} onSubmit={savePlanDialog} />}
      {stageDialog && <StageDialog initial={stageDialog.item} onClose={() => setStageDialog(null)} onSubmit={saveStageDialog} />}
      {taskDialog && <TaskDialog initial={taskDialog.task} stageTitle={plan.items.find((stage) => stage.id === taskDialog.stageId)?.title ?? '计划阶段'} onClose={() => setTaskDialog(null)} onSubmit={createTask} />}
    </div>
  )
}
