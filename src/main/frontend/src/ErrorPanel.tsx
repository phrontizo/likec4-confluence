import type { FailureView } from './errors'

export function ErrorPanel({ failure }: { failure: FailureView }) {
  return (
    <div data-testid="likec4-error" role="alert" className={`likec4-error likec4-${failure.tone}`}>
      <strong>{failure.title}</strong>
      <ul>
        {failure.lines.map((line, i) => (
          <li key={`${i}-${line}`}>{line}</li>
        ))}
      </ul>
    </div>
  )
}
