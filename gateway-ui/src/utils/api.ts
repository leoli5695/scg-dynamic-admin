import axios from 'axios';

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
  (error) => {
    if (error.response?.status === 401 || error.response?.status === 403) {
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
    } else if (error.response?.status >= 500) {
      console.error('Server error:', error.response?.data || error.message);
    } else if (error.response?.status >= 400) {
      console.error('Client error:', error.response?.data || error.message);
    }
    
    return Promise.reject(error);
  }
);

export default api;
