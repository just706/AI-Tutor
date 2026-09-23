interface LabelNode {
  id?: string | number
  name?: string
  confidence?: number
}

interface LabelLink {
  relationReason?: string
  relationType?: string
}

export function graphNodeLabel(node: LabelNode): string {
  return `${escapeHtml(node.name || node.id)}<br/>Confidence ${escapeHtml(node.confidence ?? 0)}%`
}

export function graphLinkLabel(link: LabelLink): string {
  return escapeHtml(link.relationReason || link.relationType || 'Related')
}

// The graph renderer treats tooltip strings as HTML, including model-generated text.
function escapeHtml(value: unknown): string {
  return String(value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}
