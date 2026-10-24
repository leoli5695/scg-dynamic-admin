import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import App from './App';
import ErrorBoundary from './features/layout/components/ErrorBoundary';
import './i18n';
import './index.css';

ReactDOM.createRoot(document.getElementById('root')!).render(
  // FIX: top-level boundary. ErrorBoundary already existed but was only wrapped around the 20
  // instance-detail tabs, so a render error in AppShell, the instances list, token-usage,
  // kubernetes, license or middleware-detail blanked the entire app with no recovery UI. Placed
  // outside BrowserRouter so a router-level failure is caught too.
  <ErrorBoundary>
    <BrowserRouter>
      <App />
    </BrowserRouter>
  </ErrorBoundary>,
);
