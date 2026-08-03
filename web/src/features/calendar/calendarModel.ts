import type { CalendarItem, Plan } from '../../types/planner'

export const weekLabels = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']

/**
 * 日历中既展示普通日程，也展示由待办映射出的条目。
 * 这里统一进度口径，页面无需知道每种条目的状态差异。
 */
export function getCalendarProgress(item: CalendarItem, plans: Plan[]) {
  if (item.kind === 'todo') return item.status === 'done' ? 100 : 0
  if (item.status === 'done') return 100
  return item.progress ?? plans.find((plan) => plan.id === item.planId)?.progress ?? 0
}
