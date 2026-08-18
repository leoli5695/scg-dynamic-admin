/**
 * Unified Error Handling Utility
 * Provides consistent error extraction and user notification
 */
import { message } from 'antd';
import axios from 'axios';
import logger from './logger';

/**
 * API error shape from Axios
 */
export interface ApiError {
  response?: {
    data?: {
      message?: string;
      error?: string;
    };
  };
  message?: string;
}

/**
 * Extract user-friendly error message from unknown error
 */
export const extractErrorMessage = (error: unknown, defaultMessage: string): string => {
  // Cancelled requests - return empty string (don't show error)
  if (axios.isCancel(error)) return '';

  const apiError = error as ApiError;
  return apiError.response?.data?.message
    || apiError.response?.data?.error
    || apiError.message
    || defaultMessage;
};

/**
 * Handle API error: log and show user notification
 * @param error - The caught error (unknown type)
 * @param defaultMessage - Fallback message if error doesn't contain one
 * @param options - Optional configuration
 */
export const handleApiError = (
  error: unknown,
  defaultMessage: string,
  options?: {
    /** Show error notification (default: true) */
    showNotification?: boolean;
    /** Log error to console (default: true) */
    logError?: boolean;
  },
): string => {
  const { showNotification = true, logError = true } = options || {};

  // Cancelled requests - silently ignore
  if (axios.isCancel(error)) return '';

  const errorMsg = extractErrorMessage(error, defaultMessage);

  if (logError) {
    logger.error(defaultMessage, error);
  }

  if (showNotification && errorMsg) {
    message.error(errorMsg);
  }

  return errorMsg;
};

export default handleApiError;
