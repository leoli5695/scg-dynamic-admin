import React, { useMemo, memo } from 'react';
import ReactMarkdown, { Components } from 'react-markdown';
import { Typography, Tag, Space, Divider, Alert, Tooltip, Table } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import {
  CheckCircleOutlined,
  WarningOutlined,
  CloseCircleOutlined,
  InfoCircleOutlined,
  ArrowUpOutlined,
  ArrowDownOutlined,
  BulbOutlined,
  ThunderboltOutlined,
  BugOutlined,
  ClockCircleOutlined,
  DashboardOutlined,
  FireOutlined,
  QuestionCircleOutlined,
} from '@ant-design/icons';
import type { ReactNode } from 'react';

const { Title, Text, Paragraph } = Typography;

/**
 * AI分析报告渲染器 - 增强版
 * 支持表格样式增强、交互功能、可视化增强
 */

interface AiReportRendererProps {
  content: string;
  style?: React.CSSProperties;
}

// 状态图标映射
const statusIconMap: Record<string, ReactNode> = {
  '✅': <CheckCircleOutlined style={{ color: '#52c41a' }} />,
  '⚠️': <WarningOutlined style={{ color: '#faad14' }} />,
  '❌': <CloseCircleOutlined style={{ color: '#ff4d4f' }} />,
  '🔴': <CloseCircleOutlined style={{ color: '#ff4d4f' }} />,
  '🟡': <WarningOutlined style={{ color: '#faad14' }} />,
  '🟢': <CheckCircleOutlined style={{ color: '#52c41a' }} />,
  '🟠': <WarningOutlined style={{ color: '#faad14' }} />,
  '📊': <DashboardOutlined />,
  '🔍': <InfoCircleOutlined />,
  '📈': <ArrowUpOutlined style={{ color: '#52c41a' }} />,
  '📉': <ArrowDownOutlined style={{ color: '#ff4d4f' }} />,
  '🎯': <BulbOutlined style={{ color: '#1890ff' }} />,
  '⚡': <ThunderboltOutlined style={{ color: '#1890ff' }} />,
  '🐛': <BugOutlined style={{ color: '#ff4d4f' }} />,
  '📝': <InfoCircleOutlined />,
  '⏱️': <ClockCircleOutlined />,
  '💡': <BulbOutlined style={{ color: '#1890ff' }} />,
  '🔥': <FireOutlined style={{ color: '#ff4d4f' }} />,
};

// 状态颜色映射
const statusColorMap: Record<string, string> = {
  '正常': 'success',
  '优秀': 'success',
  '异常': 'error',
  '警告': 'warning',
  '需优化': 'error',
  '关注': 'warning',
  '良好': 'success',
  '高': 'error',
  '高影响': 'error',
  '中': 'warning',
  '中影响': 'warning',
  '低': 'success',
  '低影响': 'success',
  'P0': 'error',
  'P1': 'warning',
  'P2': 'success',
};

// 影响等级映射
const impactLevelMap: Record<string, { color: string; icon: ReactNode }> = {
  '🔴 高影响': { color: '#ff4d4f', icon: <CloseCircleOutlined style={{ color: '#ff4d4f' }} /> },
  '🟠 中影响': { color: '#faad14', icon: <WarningOutlined style={{ color: '#faad14' }} /> },
  '🟢 低影响': { color: '#52c41a', icon: <CheckCircleOutlined style={{ color: '#52c41a' }} /> },
};

// 解析状态文本中的图标
const parseStatusIcon = (text: string): { icon: string; content: string } => {
  const iconMatch = text.match(/^(✅|⚠️|❌|🔴|🟡|🟢|🟠|📊|🔍|📈|📉|🎯|⚡|🐛|📝|⏱️|💡|🔥)\s*/);
  if (iconMatch) {
    return {
      icon: iconMatch[1],
      content: text.replace(iconMatch[0], '').trim(),
    };
  }
  return { icon: '', content: text };
};

// 检测是否是带单位的数字
const parseNumericValue = (text: string): { value: number; unit: string; isNumeric: boolean; raw: string } => {
  const numericMatch = text.match(/^([\d.]+)\s*(ms|s|%)?$/);
  if (numericMatch) {
    return {
      value: parseFloat(numericMatch[1]),
      unit: numericMatch[2] || '',
      isNumeric: true,
      raw: text,
    };
  }
  return { value: 0, unit: '', isNumeric: false, raw: text };
};

// 获取耗时对应的颜色
const getLatencyColor = (value: number, unit: string): string => {
  if (unit === 'ms') {
    if (value < 10) return '#52c41a';  // 绿色 - 快
    if (value < 50) return '#faad14';  // 黄色 - 中等
    return '#ff4d4f';                   // 红色 - 慢
  }
  if (unit === '%') {
    if (value >= 95) return '#52c41a';  // 绿色 - 高成功率
    if (value >= 80) return '#faad14';  // 黄色 - 中等
    return '#ff4d4f';                   // 红色 - 低
  }
  return 'rgba(255,255,255,0.85)';
};

// 迷你条形图组件
const MiniBar: React.FC<{ value: number; max: number; color: string }> = ({ value, max, color }) => {
  const percent = Math.min((value / max) * 100, 100);
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
      <div style={{
        width: 60,
        height: 8,
        background: 'rgba(255,255,255,0.1)',
        borderRadius: 4,
        overflow: 'hidden',
      }}>
        <div style={{
          width: `${percent}%`,
          height: '100%',
          background: color,
          borderRadius: 4,
          transition: 'width 0.3s ease',
        }} />
      </div>
      <Text style={{ fontSize: 12, color }}>{value.toFixed(1)}</Text>
    </div>
  );
};

// 解析 Markdown 表格数据
const parseMarkdownTable = (markdown: string): { tables: Array<{ headers: string[]; rows: string[][]; raw: string }> } => {
  const tables: Array<{ headers: string[]; rows: string[][]; raw: string }> = [];
  
  // 匹配表格块
  const tableRegex = /\|[^|]+\|[^\n]*\n(\|[-:| ]+\|[^\n]*\n)?((?:\|[^|]+\|[^\n]*\n?)+)/g;
  let match;
  
  while ((match = tableRegex.exec(markdown)) !== null) {
    const tableBlock = match[0];
    const lines = tableBlock.trim().split('\n');
    
    if (lines.length < 2) continue;
    
    // 解析表头
    const headerLine = lines[0];
    const headers = headerLine.split('|').filter(h => h.trim()).map(h => h.trim());
    
    // 解析数据行（跳过分隔线）
    const dataLines = lines.slice(2);
    const rows: string[][] = [];
    
    for (const line of dataLines) {
      if (!line.trim() || line.includes('---')) continue;
      const cells = line.split('|').filter(c => c.trim()).map(c => c.trim());
      if (cells.length > 0) {
        rows.push(cells);
      }
    }
    
    if (headers.length > 0 && rows.length > 0) {
      tables.push({ headers, rows, raw: tableBlock });
    }
  }
  
  return { tables };
};

// 智能表格渲染组件
const SmartTable: React.FC<{ headers: string[]; rows: string[][] }> = ({ headers, rows }) => {
  // 计算数字列的最大值（用于迷你条形图）
  const numericMaxValues: Record<number, number> = useMemo(() => {
    const maxValues: Record<number, number> = {};
    
    headers.forEach((_, colIndex) => {
      const numericValues = rows
        .map(row => parseNumericValue(row[colIndex] || ''))
        .filter(n => n.isNumeric)
        .map(n => n.value);
      
      if (numericValues.length > 0) {
        maxValues[colIndex] = Math.max(...numericValues);
      }
    });
    
    return maxValues;
  }, [headers, rows]);
  
  // 检测列类型
  const columnTypes: Record<number, 'name' | 'numeric' | 'status' | 'text'> = useMemo(() => {
    const types: Record<number, 'name' | 'numeric' | 'status' | 'text'> = {};
    
    headers.forEach((header, colIndex) => {
      const headerLower = header.toLowerCase();
      const headerParsed = parseStatusIcon(header);
      
      // 检测列名类型
      if (headerLower.includes('filter') || headerLower.includes('名称') || headerLower.includes('name')) {
        types[colIndex] = 'name';
      } else if (headerLower.includes('耗时') || headerLower.includes('time') || headerLower.includes('ms') || headerLower.includes('延迟')) {
        types[colIndex] = 'numeric';
      } else if (headerLower.includes('状态') || headerLower.includes('status') || headerLower.includes('影响')) {
        types[colIndex] = 'status';
      } else if (headerParsed.icon) {
        types[colIndex] = 'status';
      } else {
        // 根据数据内容判断
        const firstRowValue = rows[0]?.[colIndex] || '';
        if (parseNumericValue(firstRowValue).isNumeric) {
          types[colIndex] = 'numeric';
        } else if (parseStatusIcon(firstRowValue).icon || statusColorMap[firstRowValue]) {
          types[colIndex] = 'status';
        } else {
          types[colIndex] = 'text';
        }
      }
    });
    
    return types;
  }, [headers, rows]);
  
  // 构建列定义
  const columns: ColumnsType<Record<string, string>> = useMemo(() => {
    return headers.map((header, colIndex) => {
      const headerParsed = parseStatusIcon(header);
      const colType = columnTypes[colIndex];
      const maxValue = numericMaxValues[colIndex];
      
      return {
        title: headerParsed.icon ? (
          <Space size={4}>
            {statusIconMap[headerParsed.icon]}
            <Text strong style={{ color: 'rgba(255,255,255,0.95)' }}>{headerParsed.content}</Text>
          </Space>
        ) : (
          <Text strong style={{ color: 'rgba(255,255,255,0.95)' }}>{header}</Text>
        ),
        dataIndex: colIndex.toString(),
        key: colIndex,
        render: (text: string, record: Record<string, string>, rowIndex: number) => {
          const cellParsed = parseStatusIcon(text);
          const numeric = parseNumericValue(text);
          
          // 数字类型 - 显示颜色和迷你条形图
          if (colType === 'numeric' && numeric.isNumeric && maxValue) {
            const color = getLatencyColor(numeric.value, numeric.unit);
            return (
              <Tooltip title={`${numeric.value.toFixed(2)}${numeric.unit}`}>
                <MiniBar value={numeric.value} max={maxValue} color={color} />
              </Tooltip>
            );
          }
          
          // Filter 名称 - 可点击展开
          if (colType === 'name') {
            return (
              <Text 
                style={{ 
                  color: '#1890ff', 
                  fontWeight: 500,
                  cursor: 'pointer',
                }}
              >
                {text}
              </Text>
            );
          }
          
          // 影响分析列
          if (header.includes('影响') || headerParsed.content?.includes('影响')) {
            const impactInfo = impactLevelMap[text];
            if (impactInfo) {
              return (
                <Space size={4}>
                  {impactInfo.icon}
                  <Text style={{ color: impactInfo.color }}>{cellParsed.content}</Text>
                </Space>
              );
            }
          }
          
          // 状态类型
          if (colType === 'status') {
            // 检查是否是影响分析
            const impactMatch = text.match(/^(🔴|🟠|🟢)\s*(高影响|中影响|低影响)/);
            if (impactMatch) {
              const level = `${impactMatch[1]} ${impactMatch[2]}`;
              const impactInfo = impactLevelMap[level];
              if (impactInfo) {
                return (
                  <Space size={4}>
                    {impactInfo.icon}
                    <Text style={{ color: impactInfo.color }}>{impactMatch[2]}</Text>
                  </Space>
                );
              }
            }
            
            // 纯数字状态（如成功率）
            if (numeric.isNumeric) {
              const color = getLatencyColor(numeric.value, numeric.unit);
              const hasIcon = cellParsed.icon;
              return (
                <Space size={4}>
                  {hasIcon && statusIconMap[cellParsed.icon]}
                  <Text style={{ color, fontWeight: 500 }}>
                    {numeric.value.toFixed(2)}{numeric.unit}
                  </Text>
                </Space>
              );
            }
            
            // 带颜色的状态标签
            if (statusColorMap[cellParsed.content]) {
              return (
                <Space size={4}>
                  {cellParsed.icon && statusIconMap[cellParsed.icon]}
                  <Tag 
                    color={statusColorMap[cellParsed.content]}
                    style={{ margin: 0 }}
                  >
                    {cellParsed.content}
                  </Tag>
                </Space>
              );
            }
            
            // 带图标的状态
            if (cellParsed.icon) {
              return (
                <Space size={4}>
                  {statusIconMap[cellParsed.icon]}
                  <Text style={{ color: 'rgba(255,255,255,0.85)' }}>{cellParsed.content}</Text>
                </Space>
              );
            }
            
            // 普通数字
            if (numeric.isNumeric) {
              const color = getLatencyColor(numeric.value, numeric.unit);
              return (
                <Text style={{ color, fontWeight: 500 }}>
                  {numeric.value.toFixed(2)}{numeric.unit}
                </Text>
              );
            }
          }
          
          // 默认文本
          if (cellParsed.icon) {
            return (
              <Space size={4}>
                {statusIconMap[cellParsed.icon]}
                <Text style={{ color: 'rgba(255,255,255,0.85)' }}>{cellParsed.content}</Text>
              </Space>
            );
          }
          
          return <Text style={{ color: 'rgba(255,255,255,0.85)' }}>{text}</Text>;
        },
      };
    });
  }, [headers, columnTypes, numericMaxValues]);
  
  // 构建数据源
  const dataSource = useMemo(() => {
    return rows.map((row, rowIndex) => {
      const record: Record<string, string> = { key: rowIndex.toString() };
      row.forEach((cell, colIndex) => {
        record[colIndex.toString()] = cell;
      });
      return record;
    });
  }, [rows]);
  
  if (headers.length === 0 || rows.length === 0) {
    return null;
  }
  
  return (
    <Table
      columns={columns}
      dataSource={dataSource}
      pagination={false}
      size="small"
      style={{
        marginBottom: 16,
        background: 'rgba(255,255,255,0.03)',
        borderRadius: 8,
      }}
      className="ai-report-smart-table"
    />
  );
};

// Markdown组件配置
const createComponents = (onTableFound?: (table: { headers: string[]; rows: string[][] }) => void): Components => ({
  // 表格渲染
  table: ({ children }) => {
    return (
      <div style={{ overflowX: 'auto', marginBottom: 16 }}>
        <table className="ai-report-markdown-table">
          {children}
        </table>
      </div>
    );
  },
  
  thead: ({ children }) => <thead style={{ background: 'rgba(255,255,255,0.08)' }}>{children}</thead>,
  
  th: ({ children }) => (
    <th style={{
      padding: '12px 16px',
      textAlign: 'left',
      fontWeight: 600,
      color: 'rgba(255,255,255,0.95)',
      borderBottom: '1px solid rgba(255,255,255,0.1)',
    }}>
      {children}
    </th>
  ),
  
  td: ({ children }) => {
    // 尝试解析内容并应用样式
    const text = typeof children === 'string' ? children : '';
    const numeric = parseNumericValue(text);
    
    if (numeric.isNumeric) {
      const color = getLatencyColor(numeric.value, numeric.unit);
      return (
        <td style={{
          padding: '10px 16px',
          color: color,
          fontWeight: 500,
          borderBottom: '1px solid rgba(255,255,255,0.05)',
        }}>
          {numeric.value.toFixed(2)}{numeric.unit}
        </td>
      );
    }
    
    const parsed = parseStatusIcon(text);
    if (parsed.icon || statusColorMap[parsed.content]) {
      return (
        <td style={{
          padding: '10px 16px',
          borderBottom: '1px solid rgba(255,255,255,0.05)',
        }}>
          <Space size={4}>
            {parsed.icon && statusIconMap[parsed.icon]}
            {statusColorMap[parsed.content] ? (
              <Tag color={statusColorMap[parsed.content]} style={{ margin: 0 }}>
                {parsed.content}
              </Tag>
            ) : (
              <Text style={{ color: 'rgba(255,255,255,0.85)' }}>{parsed.content}</Text>
            )}
          </Space>
        </td>
      );
    }
    
    return (
      <td style={{
        padding: '10px 16px',
        color: 'rgba(255,255,255,0.85)',
        borderBottom: '1px solid rgba(255,255,255,0.05)',
      }}>
        {children}
      </td>
    );
  },
  
  tr: ({ children }) => <tr>{children}</tr>,

  // 标题渲染
  h1: ({ children }) => {
    const text = typeof children === 'string' ? children : '';
    const parsed = parseStatusIcon(text);
    return (
      <Title level={2} style={{
        color: 'rgba(255,255,255,0.95)',
        margin: '24px 0 16px 0',
        fontSize: 24,
        fontWeight: 600,
        borderBottom: '1px solid rgba(255,255,255,0.1)',
        paddingBottom: 8,
      }}>
        {parsed.icon && <span style={{ marginRight: 8 }}>{statusIconMap[parsed.icon]}</span>}
        {parsed.content || children}
      </Title>
    );
  },

  h2: ({ children }) => {
    const text = typeof children === 'string' ? children : '';
    const parsed = parseStatusIcon(text);
    return (
      <Title level={3} style={{
        color: 'rgba(255,255,255,0.95)',
        margin: '20px 0 12px 0',
        fontSize: 20,
        fontWeight: 600,
        borderBottom: '1px solid rgba(255,255,255,0.1)',
        paddingBottom: 8,
      }}>
        {parsed.icon && <span style={{ marginRight: 8 }}>{statusIconMap[parsed.icon]}</span>}
        {parsed.content || children}
      </Title>
    );
  },

  h3: ({ children }) => {
    const text = typeof children === 'string' ? children : '';
    const parsed = parseStatusIcon(text);
    return (
      <Title level={4} style={{
        color: 'rgba(255,255,255,0.95)',
        margin: '16px 0 8px 0',
        fontSize: 16,
        fontWeight: 600,
      }}>
        {parsed.icon && <span style={{ marginRight: 8 }}>{statusIconMap[parsed.icon]}</span>}
        {parsed.content || children}
      </Title>
    );
  },

  h4: ({ children }) => {
    return (
      <Title level={5} style={{
        color: 'rgba(255,255,255,0.95)',
        margin: '12px 0 6px 0',
        fontSize: 14,
        fontWeight: 600,
      }}>
        {children}
      </Title>
    );
  },

  // 段落渲染
  p: ({ children }) => {
    const text = typeof children === 'string' ? children : '';
    
    // 检测是否是引用块
    if (text.startsWith('> ')) {
      const content = text.replace(/^> /, '');
      return (
        <Alert
          type="info"
          showIcon
          style={{
            marginBottom: 12,
            background: 'rgba(24, 144, 255, 0.1)',
            border: '1px solid rgba(24, 144, 255, 0.3)',
          }}
          message={<Text style={{ color: 'rgba(255,255,255,0.85)' }}>{content}</Text>}
        />
      );
    }

    return (
      <Paragraph style={{
        color: 'rgba(255,255,255,0.85)',
        marginBottom: 12,
        lineHeight: 1.6,
      }}>
        {children}
      </Paragraph>
    );
  },

  // 列表渲染
  ul: ({ children }) => (
    <ul style={{
      paddingLeft: 20,
      marginBottom: 12,
      listStyleType: 'disc',
    }}>
      {children}
    </ul>
  ),

  ol: ({ children }) => (
    <ol style={{
      paddingLeft: 20,
      marginBottom: 12,
      listStyleType: 'decimal',
    }}>
      {children}
    </ol>
  ),

  li: ({ children }) => (
    <li style={{
      color: 'rgba(255,255,255,0.85)',
      marginBottom: 4,
      lineHeight: 1.6,
    }}>
      {children}
    </li>
  ),

  // 代码块渲染
  code: ({ className, children, ...props }) => {
    const isInline = !className;
    if (isInline) {
      return (
        <code style={{
          background: 'rgba(255,255,255,0.1)',
          padding: '2px 6px',
          borderRadius: 4,
          color: '#1890ff',
          fontSize: '13px',
        }}>
          {children}
        </code>
      );
    }
    return (
      <pre style={{
        background: 'rgba(0,0,0,0.3)',
        padding: 16,
        borderRadius: 8,
        overflow: 'auto',
        marginBottom: 16,
        border: '1px solid rgba(255,255,255,0.1)',
      }}>
        <code style={{
          color: '#52c41a',
          fontFamily: 'monospace',
          fontSize: '13px',
        }}>
          {children}
        </code>
      </pre>
    );
  },

  // 链接渲染
  a: ({ href, children }) => (
    <a
      href={href}
      target="_blank"
      rel="noopener noreferrer"
      style={{
        color: '#1890ff',
        textDecoration: 'underline',
      }}
    >
      {children}
    </a>
  ),

  // 分隔线渲染
  hr: () => <Divider style={{ borderColor: 'rgba(255,255,255,0.1)', margin: '16px 0' }} />,

  // 引用块渲染
  blockquote: ({ children }) => (
    <Alert
      type="info"
      showIcon
      style={{
        marginBottom: 12,
        background: 'rgba(24, 144, 255, 0.1)',
        border: '1px solid rgba(24, 144, 255, 0.3)',
      }}
      message={<div style={{ color: 'rgba(255,255,255,0.85)' }}>{children}</div>}
    />
  ),

  // 强调文本
  strong: ({ children }) => (
    <Text strong style={{ color: 'rgba(255,255,255,0.95)' }}>
      {children}
    </Text>
  ),

  // 斜体文本
  em: ({ children }) => (
    <Text style={{ color: 'rgba(255,255,255,0.85)', fontStyle: 'italic' }}>
      {children}
    </Text>
  ),
});

// 将 markdown 内容分割为文本片段和表格片段
const splitMarkdownByTables = (markdown: string): Array<{ type: 'text' | 'table'; content: string; tableData?: { headers: string[]; rows: string[][] } }> => {
  const segments: Array<{ type: 'text' | 'table'; content: string; tableData?: { headers: string[]; rows: string[][] } }> = [];

  // 匹配表格块
  const tableRegex = /^(\|[^|]+\|[^\n]*\n(?:\|[-:| ]+\|[^\n]*\n)?(?:\|[^|]+\|[^\n]*\n?)+)/gm;

  let lastIndex = 0;
  let match;

  while ((match = tableRegex.exec(markdown)) !== null) {
    // 添加表格之前的文本
    if (match.index > lastIndex) {
      const textContent = markdown.slice(lastIndex, match.index).trim();
      if (textContent) {
        segments.push({ type: 'text', content: textContent });
      }
    }

    // 解析表格数据
    const tableBlock = match[1];
    const lines = tableBlock.trim().split('\n');

    if (lines.length >= 2) {
      const headerLine = lines[0];
      const headers = headerLine.split('|').filter(h => h.trim()).map(h => h.trim());

      const dataLines = lines.slice(2);
      const rows: string[][] = [];

      for (const line of dataLines) {
        if (!line.trim() || line.match(/^\|[-:| ]+\|$/)) continue;
        const cells = line.split('|').filter(c => c.trim()).map(c => c.trim());
        if (cells.length > 0) {
          rows.push(cells);
        }
      }

      if (headers.length > 0 && rows.length > 0) {
        segments.push({
          type: 'table',
          content: tableBlock,
          tableData: { headers, rows }
        });
      }
    }

    lastIndex = match.index + match[1].length;
  }

  // 添加最后的文本
  if (lastIndex < markdown.length) {
    const textContent = markdown.slice(lastIndex).trim();
    if (textContent) {
      segments.push({ type: 'text', content: textContent });
    }
  }

  // 如果没有找到任何片段，返回整个内容作为文本
  if (segments.length === 0) {
    segments.push({ type: 'text', content: markdown });
  }

  return segments;
};

const AiReportRenderer: React.FC<AiReportRendererProps> = ({ content, style }) => {
  const components = useMemo(() => createComponents(), []);

  // 分割内容为文本和表格片段
  const segments = useMemo(() => splitMarkdownByTables(content), [content]);

  return (
    <div
      className="ai-report-container"
      style={{
        ...style,
        padding: 16,
        background: 'rgba(255,255,255,0.02)',
        borderRadius: 8,
      }}
    >
      {segments.map((segment, index) => {
        if (segment.type === 'table' && segment.tableData) {
          // 使用 SmartTable 渲染表格
          return (
            <SmartTable
              key={`table-${index}`}
              headers={segment.tableData.headers}
              rows={segment.tableData.rows}
            />
          );
        } else {
          // 使用 ReactMarkdown 渲染文本
          return (
            <ReactMarkdown key={`text-${index}`} components={components}>
              {segment.content}
            </ReactMarkdown>
          );
        }
      })}
    </div>
  );
};

export default AiReportRenderer;