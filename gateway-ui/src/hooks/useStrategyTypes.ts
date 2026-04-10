import { useState, useEffect, useCallback } from 'react';
import api from '../utils/api';

interface StrategyTypeField {
  name: string;
  type: string;
  label?: string;
  labelEn?: string;
  default?: any;
  min?: number;
  max?: number;
  unit?: string;
  placeholder?: string;
  required?: boolean;
  options?: Array<{ value: string; label: string }>;
}

interface StrategyTypeSchema {
  fields: StrategyTypeField[];
  subSchemas?: Record<string, { fields: StrategyTypeField[] }>;
  hasSubSchemas?: boolean;
}

interface StrategyType {
  typeCode: string;
  typeName: string;
  typeNameEn: string;
  icon: string;
  color: string;
  category: string;
  description?: string;
  configSchema: StrategyTypeSchema;
  filterClass?: string;
  enabled: boolean;
  sortOrder: number;
}

interface UseStrategyTypesResult {
  strategyTypes: StrategyType[];
  loading: boolean;
  error: string | null;
  getStrategyType: (typeCode: string) => StrategyType | undefined;
  getSchema: (typeCode: string) => StrategyTypeSchema | undefined;
  refresh: () => void;
}

export function useStrategyTypes(): UseStrategyTypesResult {
  const [strategyTypes, setStrategyTypes] = useState<StrategyType[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const loadStrategyTypes = useCallback(async () => {
    try {
      setLoading(true);
      setError(null);
      const response = await api.get('/api/strategy-types/enabled');
      if (response.data.code === 200) {
        setStrategyTypes(response.data.data || []);
      } else {
        setError(response.data.message);
      }
    } catch (err: any) {
      setError(err.message || 'Failed to load strategy types');
      console.error('Load strategy types error:', err);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadStrategyTypes();
  }, [loadStrategyTypes]);

  const getStrategyType = useCallback((typeCode: string) => {
    return strategyTypes.find(t => t.typeCode === typeCode);
  }, [strategyTypes]);

  const getSchema = useCallback((typeCode: string) => {
    const type = getStrategyType(typeCode);
    return type?.configSchema;
  }, [getStrategyType]);

  return {
    strategyTypes,
    loading,
    error,
    getStrategyType,
    getSchema,
    refresh: loadStrategyTypes,
  };
}

export type { StrategyType, StrategyTypeSchema, StrategyTypeField };