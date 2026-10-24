/**
 * Audit logs feature root: switches between the table and timeline views and
 * owns the shared diff drawer + rollback flow. Rollback only sends the
 * `operator` field — the version-check flags the old UI passed were never
 * read by the REST rollback endpoint, so the conflict modal was removed.
 */
import React, { useState } from 'react';
import { Radio } from 'antd';
import { message } from '../../utils/antdStatic';
import { TableOutlined, UnorderedListOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
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
    <div style={{ padding: 24 }}>
      {/* View mode switch */}
      <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 16 }}>
        <Radio.Group
          value={viewMode}
          onChange={(e) => setViewMode(e.target.value)}
          optionType="button"
          buttonStyle="solid"
          size="small"
        >
          <Radio.Button value="table"><TableOutlined /> {t('audit.view_table')}</Radio.Button>
          <Radio.Button value="timeline"><UnorderedListOutlined /> {t('audit.view_timeline')}</Radio.Button>
        </Radio.Group>
      </div>

      {viewMode === 'table' ? (
        <TableView
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
