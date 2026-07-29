interface Props {
  message: string;
  onRetry?: () => void;
}

export default function ErrorState({ message, onRetry }: Props) {
  return (
    <div className="error-state" role="alert">
      <span className="error-icon">⚠️</span>
      <p className="error-message">{message}</p>
      {onRetry && (
        <button className="btn btn-secondary" onClick={onRetry}>
          重试
        </button>
      )}
    </div>
  );
}
