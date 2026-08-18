/**
 * ErrorAlert Component
 * Reusable error display with optional retry
 */
import React from 'react';
import { Alert, Button } from 'antd';

interface ErrorAlertProps {
  message?: string;
  description?: string;
  onRetry?: () => void;
  showRetry?: boolean;
}

const ErrorAlert: React.FC<ErrorAlertProps> = ({
  message = 'Error',
  description,
  onRetry,
  showRetry = false,
}) => {
  if (!showRetry || !onRetry) {
    return <Alert type="error" message={message} description={description} showIcon />;
  }

  return (
    <Alert
      type="error"
      message={message}
      description={description}
      showIcon
      action={
        <Button size="small" onClick={onRetry}>
          Retry
        </Button>
      }
    />
  );
};

export default ErrorAlert;
