/**
 * Audit logs feature root: switches between the table and timeline views and
 * owns the shared diff drawer + rollback flow. Rollback only sends the
 * `operator` field — the version-check flags the old UI passed were never
 * read by the REST rollback endpoint, so the conflict modal was removed.
 */
import React, { useState } from 'react';
import { Radio, Typography } from 'antd';
import { message } from '../../utils/antdStatic';
import { HistoryOutlined, TableOutlined, UnorderedListOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useSearchParams } from 'react-router-dom';
import { ApiError } from '../../api/http';
import { rollbackAuditLog } from '../../api/auditLogs';
import { useInstanceDetail } from '../instance-detail/InstanceDetailContext';
import TableView from './components/TableView';
import TimelineView from './components/TimelineView';
import DiffDrawer from './components/DiffDrawer';

const AuditLogsPage: React.FC = () => {
  const { t } = useTranslation();
  const { instance } = useInstanceDetail();
  const instanceId = instance.instanceId;
  const [params] = useSearchParams();

  const [viewMode, setViewMode] = useState<'table' | 'timeline'>('table');
  // Bumped after any mutation so table + stats re-query together.
  const [reloadKey, setReloadKey] = useState(0);

  // Diff drawer
  const [diffLogId, setDiffLogId] = useState<number | null>(null);
  const [drawerOpen, setDrawerOpen] = useState(false);

  const reload = () => setReloadKey((k) => k + 1);

  const handleRollback = async (logId: number) => {
    try {
      await rollbackAuditLog(logId, { operator: 'admin' });
      message.success(t('audit.rollback_success'));
      reload();
    } catch (e) {
      const detail = e instanceof ApiError ? `: ${e.message}` : '';
      message.error(`${t('audit.rollback_failed')}${detail}`);
    }
  };

  return (
    <div className="console-workspace-page console-resource-page">
      {/* View mode switch */}
      <div
        className="console-page-header"
        style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}
      >
        <Typography.Title level={3} style={{ margin: 0 }}>
          <HistoryOutlined style={{ marginRight: 10 }} />
          {t('menu.audit_logs')}
        </Typography.Title>
        <Radio.Group
          disabled={!!params.get('targetId')}
          value={params.get('targetId') ? 'table' : viewMode}
          onChange={(e) => setViewMode(e.target.value)}
          optionType="button"
          buttonStyle="solid"
          size="small"
        >
          <Radio.Button value="table">
            <TableOutlined /> {t('audit.view_table')}
          </Radio.Button>
          <Radio.Button value="timeline">
            <UnorderedListOutlined /> {t('audit.view_timeline')}
          </Radio.Button>
        </Radio.Group>
      </div>

      {viewMode === 'table' || params.get('targetId') ? (
        <TableView
          key={`${instanceId}?${params}`}
          instanceId={instanceId}
          reloadKey={reloadKey}
          onShowDiff={(logId) => {
            setDiffLogId(logId);
            setDrawerOpen(true);
          }}
          onRollback={handleRollback}
        />
      ) : (
        <TimelineView instanceId={instanceId} />
      )}

      <DiffDrawer
        instanceId={instanceId}
        logId={diffLogId}
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        onRollback={handleRollback}
      />
    </div>
  );
};

export default AuditLogsPage;
