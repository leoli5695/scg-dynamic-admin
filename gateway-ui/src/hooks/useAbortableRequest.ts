import { useRef, useCallback, useEffect } from 'react';
import axios, { AxiosRequestConfig, AxiosResponse, CancelTokenSource, AxiosError } from 'axios';

/**
 * Hook for making abortable HTTP requests
 * Automatically cancels all pending requests when component unmounts
 * Prevents memory leaks and state updates after unmount
 */
export function useAbortableRequest() {
  // Store all pending requests' cancel tokens
  const cancelTokensRef = useRef<Map<string, CancelTokenSource>>(new Map());
  // Track if component is mounted
  const isMountedRef = useRef(true);

  // Cleanup on unmount
  useEffect(() => {
    isMountedRef.current = true;
    
    return () => {
      isMountedRef.current = false;
      // Cancel all pending requests
      cancelTokensRef.current.forEach((cancelToken, requestId) => {
        cancelToken.cancel(`Request ${requestId} cancelled due to component unmount`);
      });
      cancelTokensRef.current.clear();
    };
  }, []);

  /**
   * Make an abortable request
   * @param method - HTTP method (get, post, put, delete, etc.)
   * @param url - Request URL
   * @param config - Axios request config
   * @param requestId - Optional unique identifier for the request (for manual cancellation)
   * @returns Promise with response or null if cancelled
   */
  const abortableRequest = useCallback(async <T = unknown>(
    method: string,
    url: string,
    config?: AxiosRequestConfig,
    requestId?: string
  ): Promise<AxiosResponse<T> | null> => {
    // Generate unique request ID if not provided
    const id = requestId || `${method}-${url}-${Date.now()}`;
    
    // Create cancel token
    const cancelTokenSource = axios.CancelToken.source();
    cancelTokensRef.current.set(id, cancelTokenSource);

    try {
      const response = await axios({
        method,
        url,
        ...config,
        cancelToken: cancelTokenSource.token,
      });

      // Remove from pending requests after completion
      cancelTokensRef.current.delete(id);

      // Only return response if component is still mounted
      if (!isMountedRef.current) {
        return null;
      }

      return response as AxiosResponse<T>;
    } catch (error: unknown) {
      // Remove from pending requests
      cancelTokensRef.current.delete(id);

      // If request was cancelled, don't throw error
      if (axios.isCancel(error)) {
        const cancelError = error as { message: string };
        console.log(`Request ${id} cancelled:`, cancelError.message);
        return null;
      }

      // Only throw if component is still mounted
      if (isMountedRef.current) {
        throw error;
      }

      return null;
    }
  }, []);

  /**
   * Cancel a specific request by ID
   * @param requestId - The unique identifier of the request to cancel
   */
  const cancelRequest = useCallback((requestId: string) => {
    const cancelToken = cancelTokensRef.current.get(requestId);
    if (cancelToken) {
      cancelToken.cancel(`Request ${requestId} manually cancelled`);
      cancelTokensRef.current.delete(requestId);
    }
  }, []);

  /**
   * Cancel all pending requests
   */
  const cancelAll = useCallback(() => {
    cancelTokensRef.current.forEach((cancelToken, requestId) => {
      cancelToken.cancel(`Request ${requestId} cancelled`);
    });
    cancelTokensRef.current.clear();
  }, []);

  /**
   * Check if component is still mounted
   * Useful for conditional state updates
   */
  const isMounted = useCallback(() => isMountedRef.current, []);

  return {
    abortableRequest,
    cancelRequest,
    cancelAll,
    isMounted,
  };
}

export default useAbortableRequest;