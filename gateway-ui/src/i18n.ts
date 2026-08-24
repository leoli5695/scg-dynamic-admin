import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import LanguageDetector from 'i18next-browser-languagedetector';
// Domain-split language packs (locales/<lang>/<domain>.json, flat keys).
import zhCommon from './locales/zh/common.json';
import zhMenu from './locales/zh/menu.json';
import zhLogin from './locales/zh/login.json';
import zhInstance from './locales/zh/instance.json';
import zhRoutes from './locales/zh/routes.json';
import zhServices from './locales/zh/services.json';
import zhStrategy from './locales/zh/strategy.json';
import zhAuthPolicy from './locales/zh/authPolicy.json';
import zhCert from './locales/zh/cert.json';
import zhPlugins from './locales/zh/plugins.json';
import zhMiddleware from './locales/zh/middleware.json';
import zhMonitor from './locales/zh/monitor.json';
import zhAlert from './locales/zh/alert.json';
import zhTrace from './locales/zh/trace.json';
import zhAccessLog from './locales/zh/accessLog.json';
import zhAudit from './locales/zh/audit.json';
import zhDiagnostic from './locales/zh/diagnostic.json';
import zhTopology from './locales/zh/topology.json';
import zhFilterChain from './locales/zh/filterChain.json';
import zhReplay from './locales/zh/replay.json';
import zhCopilot from './locales/zh/copilot.json';
import zhStressTest from './locales/zh/stressTest.json';
import zhK8s from './locales/zh/k8s.json';
import zhMisc from './locales/zh/misc.json';
import enCommon from './locales/en/common.json';
import enMenu from './locales/en/menu.json';
import enLogin from './locales/en/login.json';
import enInstance from './locales/en/instance.json';
import enRoutes from './locales/en/routes.json';
import enServices from './locales/en/services.json';
import enStrategy from './locales/en/strategy.json';
import enAuthPolicy from './locales/en/authPolicy.json';
import enCert from './locales/en/cert.json';
import enPlugins from './locales/en/plugins.json';
import enMiddleware from './locales/en/middleware.json';
import enMonitor from './locales/en/monitor.json';
import enAlert from './locales/en/alert.json';
import enTrace from './locales/en/trace.json';
import enAccessLog from './locales/en/accessLog.json';
import enAudit from './locales/en/audit.json';
import enDiagnostic from './locales/en/diagnostic.json';
import enTopology from './locales/en/topology.json';
import enFilterChain from './locales/en/filterChain.json';
import enReplay from './locales/en/replay.json';
import enCopilot from './locales/en/copilot.json';
import enStressTest from './locales/en/stressTest.json';
import enK8s from './locales/en/k8s.json';
import enMisc from './locales/en/misc.json';

/** Merge domain packs into one flat resource (keys stay flat, e.g. "routes.title"). */
const merge = (...packs: Record<string, string>[]): Record<string, string> =>
  Object.assign({}, ...packs);

const resources = {
  en: {
    translation: merge(
      enCommon, enMenu, enLogin, enInstance, enRoutes, enServices, enStrategy,
      enAuthPolicy, enCert, enPlugins, enMiddleware, enMonitor, enAlert, enTrace,
      enAccessLog, enAudit, enDiagnostic, enTopology, enFilterChain, enReplay,
      enCopilot, enStressTest, enK8s, enMisc,
    ),
  },
  zh: {
    translation: merge(
      zhCommon, zhMenu, zhLogin, zhInstance, zhRoutes, zhServices, zhStrategy,
      zhAuthPolicy, zhCert, zhPlugins, zhMiddleware, zhMonitor, zhAlert, zhTrace,
      zhAccessLog, zhAudit, zhDiagnostic, zhTopology, zhFilterChain, zhReplay,
      zhCopilot, zhStressTest, zhK8s, zhMisc,
    ),
  },
};

i18n
  .use(LanguageDetector)
  .use(initReactI18next)
  .init({
    resources,
    fallbackLng: 'en',
    debug: false,
    interpolation: {
      escapeValue: false,
    },
    detection: {
      order: ['localStorage', 'navigator', 'htmlTag', 'path', 'subdomain'],
      caches: ['localStorage'],
      lookupLocalStorage: 'i18nextLng',
    },
  });

export default i18n;
