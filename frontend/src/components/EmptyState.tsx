interface Props {
  icon?: string;
  message: string;
  action?: { label: string; onClick: () => void };
}

export default function EmptyState({ icon = '📄', message, action }: Props) {
  return (
    <div className="empty-state">
      <span className="empty-icon">{icon}</span>
      <p className="empty-message">{message}</p>
      {action && (
        <button className="btn btn-primary" onClick={action.onClick}>
          {action.label}
        </button>
      )}
    </div>
  );
}
