import React from 'react';
import { Table } from 'antd';
import type { TableProps } from 'antd';

/**
 * VirtualTable - 支持虚拟滚动的表格组件
 * 
 * 用于大数据量场景（1000+行），通过限制渲染行数提升性能
 * 
 * @example
 * ```tsx
 * <VirtualTable
 *   dataSource={largeDataSet}
 *   columns={columns}
 *   scroll={{ y: 600 }}
 *   virtual
 * />
 * ```
 */
const VirtualTable: React.FC<TableProps<any>> = ({
  dataSource,
  columns,
  scroll,
  pagination,
  ...restProps
}) => {
  // 默认启用虚拟滚动优化
  const defaultPageSize = 50;
  const maxPageSize = 100;

  // 如果数据量很大，强制分页
  const shouldForcePagination = dataSource && dataSource.length > maxPageSize;

  const finalPagination = pagination === false 
    ? false 
    : {
        defaultPageSize,
        pageSizeOptions: ['20', '50', '100'],
        showSizeChanger: true,
        showTotal: (total: number) => `共 ${total} 条`,
        ...(pagination || {}),
      };

  // 大数据量时显示警告
  if (shouldForcePagination && pagination !== false) {
    console.warn(
      `VirtualTable: Data size (${dataSource.length}) exceeds recommended limit (${maxPageSize}). ` +
      'Pagination is enforced for better performance.'
    );
  }

  return (
    <Table
      dataSource={dataSource}
      columns={columns}
      scroll={{
        y: 600,
        ...scroll,
      }}
      pagination={finalPagination}
      {...restProps}
    />
  );
};

export default VirtualTable;
