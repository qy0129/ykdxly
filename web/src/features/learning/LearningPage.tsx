import { useEffect, useState } from 'react'
import { ArrowLeft, CheckCircle2, Circle, GraduationCap, Milestone, RefreshCw, Target } from 'lucide-react'
import { plannerApi, type LearningGoal, type LearningGoalDetail } from '../../services/plannerApi'

const statusLabel: Record<string, string> = {
  active: '进行中',
  paused: '已暂停',
  completed: '已完成',
  abandoned: '已放弃',
}

const priorityLabel: Record<string, string> = {
  high: '高',
  medium: '中',
  low: '低',
}

const taskStatusLabel: Record<string, string> = {
  pending: '待办',
  done: '已完成',
  in_progress: '进行中',
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

export function LearningPage({ onDataChanged }: { onDataChanged?: () => void }) {
  const [goals, setGoals] = useState<LearningGoal[]>([])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [detail, setDetail] = useState<LearningGoalDetail | null>(null)
  const [detailBusy, setDetailBusy] = useState(false)
  const [taskBusyId, setTaskBusyId] = useState<string | null>(null)

  const load = async () => {
    setBusy(true)
    setError('')
    try {
      setGoals(await plannerApi.loadLearningGoals())
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '无法加载学习目标')
    } finally {
      setBusy(false)
    }
  }

  const openGoal = async (id: string) => {
    setSelectedId(id)
    setDetail(null)
    setDetailBusy(true)
    setError('')
    try {
      setDetail(await plannerApi.loadLearningGoal(id))
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '无法加载目标详情')
    } finally {
      setDetailBusy(false)
    }
  }

  const closeDetail = () => {
    setSelectedId(null)
    setDetail(null)
  }

  const toggleTask = async (task: LearningGoalDetail['days'][number]['tasks'][number]) => {
    if (taskBusyId || !selectedId) return
    setTaskBusyId(task.id)
    setError('')
    try {
      const action = task.status === 'done' ? 'reopen' : 'complete'
      await plannerApi.learningTaskAction(task.id, task.version, action)
      await Promise.all([openGoal(selectedId), load()])
      onDataChanged?.()
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '无法更新每日任务状态')
    } finally {
      setTaskBusyId(null)
    }
  }

  useEffect(() => { void load() }, [])

  if (selectedId) {
    const goal = detail?.goal
    return (
      <div className="learning-page content-page">
        <section className="learning-header">
          <div>
            <span className="eyebrow">学习规划</span>
            <h2>{goal ? goal.title : '目标详情'}</h2>
            <p>量化指标、里程碑与每日任务</p>
          </div>
          <div className="learning-header-actions">
            <button className="secondary-button" type="button" disabled={detailBusy} onClick={() => void openGoal(selectedId)}>
              <RefreshCw className={detailBusy ? 'spin' : ''} size={16} /> 刷新
            </button>
            <button className="secondary-button" type="button" onClick={closeDetail}>
              <ArrowLeft size={16} /> 返回
            </button>
          </div>
        </section>
        {error && <p className="ai-chat-error" role="alert">{error}</p>}
        {detailBusy && !detail && <p className="learning-loading">正在加载详情…</p>}

        {goal && (
          <div className="learning-detail">
            <div className="learning-detail-head">
              <span className={`learning-status learning-status-${goal.status}`}>{statusLabel[goal.status] ?? goal.status}</span>
              <span className="learning-domain">{goal.domain || '未分类领域'} · 优先级 {priorityLabel[goal.priority ?? 'medium']}</span>
              {goal.targetDate && <span className="learning-domain">目标日期：{goal.targetDate}</span>}
            </div>

            {goal.description && <p className="learning-detail-desc">{goal.description}</p>}

            {Array.isArray(goal.targetMetrics) && goal.targetMetrics.length > 0 && (
              <section className="learning-detail-section">
                <h3><Target size={15} /> 量化目标</h3>
                <div className="learning-metrics">
                  {goal.targetMetrics.map((metric, index) => (
                    <div className="learning-metric-card" key={`${metric.label}-${index}`}>
                      <strong>{metric.value}</strong>
                      {metric.unit && <em>{metric.unit}</em>}
                      <span>{metric.label}</span>
                    </div>
                  ))}
                </div>
              </section>
            )}

            {Array.isArray(goal.milestones) && goal.milestones.length > 0 && (
              <section className="learning-detail-section">
                <h3><Milestone size={15} /> 里程碑</h3>
                <ul className="learning-milestones">
                  {goal.milestones.map((item, index) => <li key={index}>{item}</li>)}
                </ul>
              </section>
            )}

            <section className="learning-detail-section">
              <h3>进度</h3>
              <div className="learning-progress">
                <div className="learning-progress-track">
                  <div className="learning-progress-bar" style={{ width: `${Math.min(100, Math.round(goal.progress))}%` }} />
                </div>
                <span>{Math.round(goal.progress)}%</span>
              </div>
            </section>

            <section className="learning-detail-section">
              <h3>每日任务（{detail?.days.length ?? 0} 天）</h3>
              {detail && detail.days.length === 0 && <p className="learning-empty-hint">还没有排期任务。</p>}
              <div className="learning-days">
                {detail?.days.map((day) => (
                  <article className={`learning-day${day.date === todayString() ? ' today' : ''}`} key={day.date}>
                    <div className="learning-day-head"><strong>{dayLabel(day.date)}</strong><span>{day.date}</span></div>
                    {day.tasks.map((task) => (
                      <button
                        className={`learning-task-row${task.status === 'done' ? ' is-done' : ''}`}
                        type="button"
                        key={task.id}
                        disabled={taskBusyId !== null}
                        onClick={() => void toggleTask(task)}
                        aria-label={task.status === 'done' ? `恢复任务：${task.title}` : `完成任务：${task.title}`}
                      >
                        <span className="learning-task-check" aria-hidden="true">
                          {taskBusyId === task.id ? <RefreshCw className="spin" size={16} /> : task.status === 'done' ? <CheckCircle2 size={16} /> : <Circle size={16} />}
                        </span>
                        <div>
                          <span>{task.title}</span>
                          {task.description && <p>{task.description}</p>}
                        </div>
                        <div className="learning-task-meta">
                          {task.minutes != null && <em>{task.minutes} 分钟</em>}
                          <em className={`learning-task-status status-${task.status}`}>{taskStatusLabel[task.status] ?? task.status}</em>
                        </div>
                      </button>
                    ))}
                  </article>
                ))}
              </div>
            </section>

            <section className="learning-detail-section">
              <h3>最近学习会话（{detail?.sessions.length ?? 0}）</h3>
              {detail && detail.sessions.length === 0 && <p className="learning-empty-hint">还没有学习记录。</p>}
              <ul className="learning-sessions">
                {detail?.sessions.map((session) => (
                  <li key={session.id}>
                    <span>{session.title}</span>
                    <em>{session.actualMinutes ?? session.plannedMinutes} 分钟 · {session.completedAt ? session.completedAt.slice(0, 10) : session.createdAt.slice(0, 10)}</em>
                  </li>
                ))}
              </ul>
            </section>
          </div>
        )}
      </div>
    )
  }

  return (
    <div className={`learning-page content-page${busy ? ' is-refreshing' : ''}`}>
      <section className="learning-header">
        <div>
          <span className="eyebrow">学习规划</span>
          <h2>学习目标</h2>
          <p>点开目标查看量化指标、里程碑和每日任务</p>
        </div>
        <button className="secondary-button" type="button" disabled={busy} onClick={() => void load()}>
          <RefreshCw className={busy ? 'spin' : ''} size={16} /> {busy ? '刷新中' : '刷新'}
        </button>
      </section>

      {error && <p className="ai-chat-error" role="alert">{error}</p>}

      {!goals.length && !busy && (
        <div className="learning-empty">
          <GraduationCap size={40} />
          <p>还没有学习目标</p>
          <p>去「AI 对话」里说一句“创建一个学习目标：明年 6 月雅思 7 分，每周 10 小时”</p>
        </div>
      )}

      <section className="learning-grid">
        {goals.map((goal) => (
          <button className="learning-card" type="button" key={goal.id} onClick={() => void openGoal(goal.id)}>
            <div className="learning-card-head">
              <strong>{goal.title}</strong>
              <span className={`learning-status learning-status-${goal.status}`}>{statusLabel[goal.status] ?? goal.status}</span>
            </div>
            <p className="learning-domain">{goal.domain || '未分类领域'}</p>
            <dl className="learning-meta">
              {goal.targetDate ? <div><dt>目标日期</dt><dd>{goal.targetDate}</dd></div> : null}
              {goal.weeklyHours != null ? <div><dt>每周时长</dt><dd>{goal.weeklyHours} 小时</dd></div> : null}
              <div><dt>优先级</dt><dd>{priorityLabel[goal.priority ?? 'medium'] ?? goal.priority}</dd></div>
            </dl>
            {Array.isArray(goal.targetMetrics) && goal.targetMetrics.length > 0 && (
              <div className="learning-card-metrics">
                {goal.targetMetrics.slice(0, 3).map((metric, index) => (
                  <span key={index}>{metric.label} {metric.value}{metric.unit ?? ''}</span>
                ))}
              </div>
            )}
            <div className="learning-progress">
              <div className="learning-progress-track">
                <div className="learning-progress-bar" style={{ width: `${Math.min(100, Math.round(goal.progress))}%` }} />
              </div>
              <span>{Math.round(goal.progress)}%</span>
            </div>
          </button>
        ))}
      </section>
    </div>
  )
}
