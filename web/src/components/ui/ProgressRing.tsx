import type { CSSProperties } from 'react'

interface ProgressRingProps {
  value: number
  color: string
  size?: number
}

/** 计划进度的通用视觉组件，不包含任何计划业务判断。 */
export function ProgressRing({ value, color, size = 42 }: ProgressRingProps) {
  const style = {
    '--progress': value * 3.6 + 'deg',
    '--ring-color': color,
    width: size,
    height: size,
  } as CSSProperties

  return (
    <div className="progress-ring" style={style} aria-label={'进度 ' + value + '%'}>
      <span>{value}</span>
    </div>
  )
}
