/**
 * Auth context — single source of truth for login state.
 * Replaces scattered localStorage reads across pages.
 */
import React, {
  createContext, useCallback, useContext, useMemo, useState,
} from 'react';
import {
  AuthUser,
  clearStoredAuth,
  getStoredUser,
  loginRequest,
  logoutRequest,
  persistLogin,
} from './authService';

interface AuthContextValue {
  user: AuthUser | null;
  isLoggedIn: boolean;
  /** Performs login and persists the session. Throws ApiError on failure. */
  login: (username: string, password: string) => Promise<AuthUser>;
  /** Clears the session locally and notifies the backend. */
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export const AuthProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [user, setUser] = useState<AuthUser | null>(() => getStoredUser());

  const login = useCallback(async (username: string, password: string) => {
    const result = await loginRequest(username, password);
    const authUser = persistLogin(result);
    setUser(authUser);
    return authUser;
  }, []);

  const logout = useCallback(async () => {
    clearStoredAuth();
    setUser(null);
    await logoutRequest();
  }, []);

  const value = useMemo<AuthContextValue>(() => ({
    user,
    isLoggedIn: user !== null,
    login,
    logout,
  }), [user, login, logout]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
};

// Hook co-located with its provider by design.
// eslint-disable-next-line react-refresh/only-export-components
export const useAuth = (): AuthContextValue => {
  const ctx = useContext(AuthContext);
  if (!ctx) {
    throw new Error('useAuth must be used within AuthProvider');
  }
  return ctx;
};
