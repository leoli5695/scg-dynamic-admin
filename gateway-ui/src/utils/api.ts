import axios, { CancelTokenSource, AxiosError } from 'axios';

// Create axios instance - use empty baseURL to leverage Vite proxy in development
const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '',
  timeout: 5 * 60 * 1000, // 5 minutes timeout for AI analysis
  headers: {
    'Content-Type': 'application/json',
  },
});

// Request interceptor
api.interceptors.request.use(
  (config) => {
    // Add auth token if needed
    const token = localStorage.getItem('token');
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
  },
  (error) => {
    console.error('Request error:', error);
    return Promise.reject(error);
  }
);

// Response interceptor
api.interceptors.response.use(
  (response) => response,
  (error: AxiosError) => {
    // If request was cancelled, don't process error
    if (axios.isCancel(error)) {
      console.log('Request cancelled:', error.message);
      return Promise.resolve(null); // Return null instead of rejecting
    }

    const status = error.response?.status;
    
    if (status === 401 || status === 403) {
      // Handle unauthorized/forbidden access - redirect to login
      console.error('Unauthorized/Forbidden access');
      
      // Clear invalid token and redirect to login
      const token = localStorage.getItem('token');
      if (token) {
        localStorage.removeItem('token');
        localStorage.removeItem('user');
      }
      
      // Redirect to login page
      window.location.href = '/login';
    } else if (status && status >= 500) {
      console.error('Server error:', error.response?.data || error.message);
    } else if (status && status >= 400) {
      console.error('Client error:', error.response?.data || error.message);
    }
    
    return Promise.reject(error);
  }
);

/**
 * Create a cancel token source for a request
 * Useful for manually cancelling requests
 */
export const createCancelToken = (): CancelTokenSource => {
  return axios.CancelToken.source();
};

/**
 * Check if error is from a cancelled request
 */
export const isCancel = (error: unknown): boolean => {
  return axios.isCancel(error);
};

/**
 * Cancel token source class for managing multiple requests
 */
export class RequestCanceller {
  private cancelTokens: Map<string, CancelTokenSource> = new Map();

  /**
   * Get or create a cancel token for a request
   */
  getToken(requestId: string): CancelTokenSource {
    if (!this.cancelTokens.has(requestId)) {
      this.cancelTokens.set(requestId, axios.CancelToken.source());
    }
    return this.cancelTokens.get(requestId)!;
  }

  /**
   * Cancel a specific request
   */
  cancel(requestId: string, reason?: string): void {
    const token = this.cancelTokens.get(requestId);
    if (token) {
      token.cancel(reason || `Request ${requestId} cancelled`);
      this.cancelTokens.delete(requestId);
    }
  }

  /**
   * Cancel all pending requests
   */
  cancelAll(reason?: string): void {
    this.cancelTokens.forEach((token, id) => {
      token.cancel(reason || `Request ${id} cancelled`);
    });
    this.cancelTokens.clear();
  }

  /**
   * Remove a completed request from tracking
   */
  remove(requestId: string): void {
    this.cancelTokens.delete(requestId);
  }
}

export default api;