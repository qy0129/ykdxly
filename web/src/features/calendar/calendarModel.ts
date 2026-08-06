import type { CalendarItem, Plan } from '../../types/planner'

export const weekLabels = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']

/** 每日五行 + 运势（按日期确定性生成，非真实占卜，仅供日历格子的视觉填充）。 */
const ELEMENTS = [
  { label: '金', color: '#d4af37' },
  { label: '木', color: '#4caf50' },
  { label: '水', color: '#2196f3' },
  { label: '火', color: '#e05d44' },
  { label: '土', color: '#8d6e63' },
]
const FORTUNES = [
  { text: '大吉', emoji: '🍀' },
  { text: '吉', emoji: '😊' },
  { text: '平', emoji: '🙂' },
  { text: '小凶', emoji: '😅' },
]

export function dayVibe(date: Date) {
  const startOfYear = new Date(date.getFullYear(), 0, 0)
  const dayOfYear = Math.floor((date.getTime() - startOfYear.getTime()) / 86400000)
  const element = ELEMENTS[((dayOfYear % 5) + 5) % 5]
  const fortune = FORTUNES[(date.getFullYear() + date.getMonth() + date.getDate()) % FORTUNES.length]
  return { element: element.label, elementColor: element.color, fortune: fortune.text, emoji: fortune.emoji }
}

/**
 * 日历中既展示普通日程，也展示由待办映射出的条目。
 * 这里统一进度口径，页面无需知道每种条目的状态差异。
 */
export function getCalendarProgress(item: CalendarItem, plans: Plan[]) {
  if (item.kind === 'todo') return item.status === 'done' ? 100 : 0
  if (item.status === 'done') return 100
  if (!item.taskId) return 0
  const task = plans.flatMap((plan) => plan.items.flatMap((stage) => stage.tasks))
    .find((candidate) => candidate.id === item.taskId)
  return task?.status === 'done' ? 100 : 0
}
