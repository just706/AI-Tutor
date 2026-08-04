import type { KnowledgePoint } from '../types/domain'

export function flattenKnowledgePoints(points: KnowledgePoint[]) {
  const result: KnowledgePoint[] = []
  for (const point of points) {
    result.push(point)
    result.push(...flattenKnowledgePoints(point.children || []))
  }
  return result
}

export function firstSelectableKnowledgePoint(points: KnowledgePoint[]) {
  const flattened = flattenKnowledgePoints(points)
  return flattened.find((point) => !point.children || point.children.length === 0) || flattened[0] || null
}

export function findKnowledgePoint(points: KnowledgePoint[], id: number) {
  return flattenKnowledgePoints(points).find((point) => point.id === id) || null
}
