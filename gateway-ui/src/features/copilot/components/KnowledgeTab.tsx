import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  Alert, Button, Card, Empty, Input, Popconfirm, Select, Space, Statistic, Table, Tag, Typography, Upload,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { DeleteOutlined, PlusOutlined, ReloadOutlined, SearchOutlined, UploadOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { message } from '../../../utils/antdStatic';
import {
  deleteKnowledgeChunk, getKnowledgeStats, ingestKnowledge,
  listKnowledgeChunks, reindexKnowledge, searchKnowledge,
} from '../../../api/copilot';
import { ApiError } from '../../../api/http';
import type { KnowledgeChunkItem, KnowledgeSearchHit, KnowledgeStats } from '../../../types/copilot';

const { Text, Paragraph } = Typography;
const { TextArea } = Input;

const SOURCE_TYPES = ['runbook', 'incident', 'doc', 'feedback'] as const;

const errText = (e: unknown, fallback: string) =>
  (e instanceof ApiError && e.message) ? e.message : fallback;

const KnowledgeTab: React.FC = () => {
  const { t } = useTranslation();

  const [stats, setStats] = useState<KnowledgeStats | null>(null);

  // 添加文档表单
  const [formType, setFormType] = useState<string>('runbook');
  const [formTitle, setFormTitle] = useState('');
  const [formContent, setFormContent] = useState('');
  const [ingesting, setIngesting] = useState(false);
  const [importing, setImporting] = useState(false);
  const pendingRef = useRef(0);

  // 测试检索
  const [query, setQuery] = useState('');
  const [searching, setSearching] = useState(false);
  const [hits, setHits] = useState<KnowledgeSearchHit[] | null>(null);
  const [searchMode, setSearchMode] = useState('');

  // 分块列表
  const [chunks, setChunks] = useState<KnowledgeChunkItem[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(10);
  const [listType, setListType] = useState<string | undefined>(undefined);
  const [listLoading, setListLoading] = useState(false);
  const [reindexing, setReindexing] = useState(false);

  const loadStats = useCallback(async () => {
    try {
      setStats(await getKnowledgeStats());
    } catch (e) {
      console.error('load knowledge stats failed', e);
    }
  }, []);

  const loadChunks = useCallback(async (p = page, size = pageSize, type = listType) => {
    setListLoading(true);
    try {
      const res = await listKnowledgeChunks({ type, page: p, size });
      setChunks(res.content || []);
      setTotal(res.total || 0);
    } catch (e) {
      message.error(errText(e, t('copilot.kb_load_failed')));
    } finally {
      setListLoading(false);
    }
  }, [page, pageSize, listType, t]);

  useEffect(() => {
    loadStats();
    loadChunks(0, pageSize, undefined);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const refreshAll = () => {
    loadStats();
    loadChunks(page, pageSize, listType);
  };

  const handleIngest = async () => {
    if (!formContent.trim()) {
      message.warning(t('copilot.kb_content_required'));
      return;
    }
    setIngesting(true);
    try {
      const res = await ingestKnowledge({
        sourceType: formType,
        title: formTitle.trim() || undefined,
        content: formContent,
      });
      if (res.success) {
        message.success(t('copilot.kb_ingest_success', { n: res.chunks ?? 0 }));
        setFormTitle('');
        setFormContent('');
        setPage(0);
        refreshAll();
      } else {
        message.error(res.message || t('copilot.kb_ingest_failed'));
      }
    } catch (e) {
      message.error(errText(e, t('copilot.kb_ingest_failed')));
    } finally {
      setIngesting(false);
    }
  };

  // 批量导入：浏览器本地读取 .md/.txt 文本后逐个走 /knowledge/ingest（复用现有端点，无需 multipart）
  const handleFile = (file: File) => {
    pendingRef.current += 1;
    setImporting(true);
    file.text()
      .then(text => ingestKnowledge({
        sourceType: formType,
        sourceRef: file.name,
        title: file.name,
        content: text,
      }))
      .then(res => {
        if (res.success) {
          message.success(t('copilot.kb_import_success', { name: file.name, n: res.chunks ?? 0 }));
        } else {
          message.error(`${file.name}: ${res.message || t('copilot.kb_ingest_failed')}`);
        }
      })
      .catch(e => message.error(`${file.name}: ${errText(e, t('copilot.kb_ingest_failed'))}`))
      .finally(() => {
        pendingRef.current -= 1;
        if (pendingRef.current <= 0) {
          setImporting(false);
          setPage(0);
          refreshAll();
        }
      });
    return false; // 阻止 antd 自动上传，改由本地读取处理
  };

  const handleSearch = async () => {
    if (!query.trim()) {
      message.warning(t('copilot.kb_query_required'));
      return;
    }
    setSearching(true);
    try {
      const res = await searchKnowledge({ query: query.trim(), topK: 5 });
      setHits(res.results || []);
      setSearchMode(res.mode || '');
      if ((!res.results || res.results.length === 0) && res.hint) {
        message.info(res.hint);
      }
    } catch (e) {
      message.error(errText(e, t('copilot.kb_search_failed')));
    } finally {
      setSearching(false);
    }
  };

  const handleDelete = async (id: number) => {
    try {
      await deleteKnowledgeChunk(id);
      message.success(t('copilot.kb_delete_success'));
      refreshAll();
    } catch (e) {
      message.error(errText(e, t('copilot.kb_delete_failed')));
    }
  };

  const handleReindex = async () => {
    setReindexing(true);
    try {
      const res = await reindexKnowledge();
      message.success(t('copilot.kb_reindex_success', { n: res.indexedChunks }));
      loadStats();
    } catch (e) {
      message.error(errText(e, t('copilot.kb_reindex_failed')));
    } finally {
      setReindexing(false);
    }
  };

  const TYPE_COLORS: Record<string, string> = {
    runbook: 'blue', incident: 'red', feedback: 'green', doc: 'default',
  };
  const typeColor = (ty: string) => TYPE_COLORS[ty] || 'default';

  const columns: ColumnsType<KnowledgeChunkItem> = [
    {
      title: t('copilot.kb_col_title'), dataIndex: 'title', key: 'title', width: 200,
      render: (v: string) => <Text style={{ color: 'rgba(255,255,255,0.85)' }}>{v || '-'}</Text>,
    },
    {
      title: t('copilot.kb_col_type'), dataIndex: 'sourceType', key: 'sourceType', width: 110,
      render: (v: string) => <Tag color={typeColor(v)}>{v}</Tag>,
    },
    {
      title: t('copilot.kb_col_preview'), dataIndex: 'preview', key: 'preview',
      render: (v: string) => (
        <Text type="secondary" style={{ fontSize: 12 }} ellipsis={{ tooltip: v }}>{v}</Text>
      ),
    },
    { title: t('copilot.kb_col_dim'), dataIndex: 'dim', key: 'dim', width: 80 },
    {
      title: t('copilot.kb_col_actions'), key: 'actions', width: 80,
      render: (_: unknown, record: KnowledgeChunkItem) => (
        <Popconfirm
          title={t('copilot.kb_delete_confirm')}
          okText={t('copilot.confirm_execute')}
          cancelText={t('copilot.confirm_cancel')}
          onConfirm={() => handleDelete(record.id)}
        >
          <Button size="small" type="text" danger icon={<DeleteOutlined />} />
        </Popconfirm>
      ),
    },
  ];

  return (
    <div style={{ padding: '16px' }}>
      <Alert
        type="info"
        showIcon
        message={t('copilot.kb_title')}
        description={t('copilot.kb_desc')}
        style={{ marginBottom: '16px' }}
      />

      {/* 统计 */}
      <Card size="small" style={{ background: 'rgba(255,255,255,0.05)', marginBottom: '16px' }}
        extra={
          <Button size="small" icon={<ReloadOutlined />} loading={reindexing} onClick={handleReindex}>
            {t('copilot.kb_reindex')}
          </Button>
        }
        title={t('copilot.kb_stats')}
      >
        <Space size="large" wrap>
          <Statistic title={t('copilot.kb_stats_total')} value={stats?.totalChunks ?? 0} />
          <Statistic title={t('copilot.kb_stats_indexed')} value={stats?.indexedChunks ?? 0} />
          <Statistic title={t('copilot.kb_stats_dim')} value={stats?.dim ?? 0} />
          {stats?.byType && Object.entries(stats.byType).map(([k, v]) => (
            <Statistic key={k} title={k} value={v as number} />
          ))}
        </Space>
      </Card>

      {/* 测试检索 */}
      <Card size="small" title={t('copilot.kb_search_test')}
        style={{ background: 'rgba(255,255,255,0.05)', marginBottom: '16px' }}>
        <Space.Compact style={{ width: '100%' }}>
          <Input
            value={query}
            onChange={e => setQuery(e.target.value)}
            placeholder={t('copilot.kb_search_placeholder')}
            onPressEnter={handleSearch}
            style={{ background: 'rgba(0,0,0,0.2)', color: '#fff' }}
          />
          <Button type="primary" icon={<SearchOutlined />} loading={searching} onClick={handleSearch}>
            {t('copilot.kb_search_btn')}
          </Button>
        </Space.Compact>
        {hits && (
          <div style={{ marginTop: 12 }}>
            {hits.length === 0 ? (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('copilot.kb_no_result')} />
            ) : (
              <>
                <Text type="secondary" style={{ fontSize: 12 }}>
                  {t('copilot.kb_mode')}: {searchMode} · {hits.length}
                </Text>
                {hits.map((h, i) => (
                  <Card key={i} size="small" style={{ marginTop: 8, background: 'rgba(0,0,0,0.2)' }}>
                    <Space style={{ marginBottom: 4 }}>
                      {h.sourceType && <Tag color={typeColor(h.sourceType)}>{h.sourceType}</Tag>}
                      {typeof h.score === 'number' && <Tag>{t('copilot.kb_score')}: {h.score}</Tag>}
                      <Text strong style={{ color: 'rgba(255,255,255,0.85)' }}>{h.title}</Text>
                    </Space>
                    <Paragraph style={{ color: 'rgba(255,255,255,0.75)', marginBottom: 0, whiteSpace: 'pre-wrap' }}>
                      {h.content}
                    </Paragraph>
                  </Card>
                ))}
              </>
            )}
          </div>
        )}
      </Card>

      {/* 添加知识 */}
      <Card size="small" title={t('copilot.kb_add')}
        style={{ background: 'rgba(255,255,255,0.05)', marginBottom: '16px' }}>
        <Space direction="vertical" style={{ width: '100%' }} size="middle">
          <Space wrap>
            <Select
              value={formType}
              onChange={setFormType}
              style={{ width: 160 }}
              options={SOURCE_TYPES.map(s => ({ value: s, label: t(`copilot.kb_type_${s}`) }))}
            />
            <Input
              value={formTitle}
              onChange={e => setFormTitle(e.target.value)}
              placeholder={t('copilot.kb_title_placeholder')}
              style={{ width: 320, background: 'rgba(0,0,0,0.2)', color: '#fff' }}
            />
          </Space>
          <TextArea
            value={formContent}
            onChange={e => setFormContent(e.target.value)}
            placeholder={t('copilot.kb_content_placeholder')}
            autoSize={{ minRows: 4, maxRows: 12 }}
            style={{ background: 'rgba(0,0,0,0.2)', color: '#fff' }}
          />
          <Space wrap>
            <Button type="primary" icon={<PlusOutlined />} loading={ingesting} onClick={handleIngest}>
              {t('copilot.kb_submit')}
            </Button>
            <Upload
              accept=".md,.markdown,.txt"
              multiple
              showUploadList={false}
              beforeUpload={handleFile}
            >
              <Button icon={<UploadOutlined />} loading={importing}>
                {t('copilot.kb_import')}
              </Button>
            </Upload>
            <Text type="secondary" style={{ fontSize: 12 }}>{t('copilot.kb_import_hint')}</Text>
          </Space>
        </Space>
      </Card>

      {/* 分块列表 */}
      <Card size="small" title={t('copilot.kb_list')}
        style={{ background: 'rgba(255,255,255,0.05)' }}
        extra={
          <Select
            allowClear
            placeholder={t('copilot.kb_filter_type')}
            style={{ width: 150 }}
            value={listType}
            onChange={(v) => { setListType(v); setPage(0); loadChunks(0, pageSize, v); }}
            options={SOURCE_TYPES.map(s => ({ value: s, label: t(`copilot.kb_type_${s}`) }))}
          />
        }
      >
        <Table<KnowledgeChunkItem>
          rowKey="id"
          size="small"
          loading={listLoading}
          columns={columns}
          dataSource={chunks}
          pagination={{
            current: page + 1,
            pageSize,
            total,
            showSizeChanger: true,
            onChange: (p, size) => { setPage(p - 1); setPageSize(size); loadChunks(p - 1, size, listType); },
          }}
        />
      </Card>
    </div>
  );
};

export default KnowledgeTab;
