import axios from 'axios';

// Create axios instance with baseURL for gateway-admin backend
const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080',
  timeout: 10000,
  headers: {
    'Content-Type': 'application/json',
  },
});

// Request interceptor
api.interceptors.request.use(
  (config) => {
    // Add auth token if needed
    const token = localStorage.getItem('token');
    console.log('[API Interceptor] Request URL:', config.url);
    console.log('[API Interceptor] Token present:', !!token);
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
      console.log('[API Interceptor] Added Authorization header');
    } else {
      console.warn('[API Interceptor] No token found in localStorage!');
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
      console.error('Unauthorized/Forbidden access:', error.response?.data);
      
      // Check if token exists in localStorage
      const token = localStorage.getItem('token');
      if (!token) {
        console.warn('[API Interceptor] No token found, redirecting to login...');
        // Only redirect if token doesn't exist (user is not logged in)
        window.location.href = '/login';
      } else {
        console.warn('[API Interceptor] Token exists but request failed with 403/401. This may indicate an invalid token.');
        // Don't redirect, let the component handle the error
      }
    } else if (error.response?.status >= 500) {
      console.error('Server error:', error.response?.data || error.message);
    } else if (error.response?.status >= 400) {
      console.error('Client error:', error.response?.data || error.message);
    }
    
    return Promise.reject(error);
  }
);

export default api;
