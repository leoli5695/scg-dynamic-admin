-- 幂等种子: intent_configs（从被删的 db/migration 恢复；INSERT 已转 INSERT IGNORE）
-- 由 ReferenceDataSeeder 在表为空时执行；请勿手工编辑。

INSERT IGNORE INTO intent_configs (config_type, keyword, intent, weight, enabled, language) VALUES
('KEYWORD_WEIGHT', 'SQL注入', 'strategyTest', 8, true, 'zh'),
('KEYWORD_WEIGHT', 'XSS攻击', 'strategyTest', 8, true, 'zh'),
('KEYWORD_WEIGHT', 'sql injection', 'strategyTest', 8, true, 'en'),
('KEYWORD_WEIGHT', 'xss', 'strategyTest', 8, true, 'en'),
('KEYWORD_WEIGHT', '安全防护', 'strategyTest', 6, true, 'zh'),
('KEYWORD_WEIGHT', 'security', 'strategyTest', 6, true, 'en'),
('KEYWORD_WEIGHT', '请求验证', 'strategyTest', 6, true, 'zh'),
('KEYWORD_WEIGHT', 'request validation', 'strategyTest', 6, true, 'en'),
('KEYWORD_WEIGHT', 'Mock响应', 'strategyTest', 6, true, 'zh'),
('KEYWORD_WEIGHT', 'mock', 'strategyTest', 6, true, 'en');

INSERT IGNORE INTO intent_configs (config_type, keyword, intent, weight, enabled, language) VALUES
('COMBO_RULE', '测试|限流', 'strategyTest', 25, true, 'zh'),
('COMBO_RULE', '测试|熔断', 'strategyTest', 25, true, 'zh'),
('COMBO_RULE', '测试|超时', 'strategyTest', 25, true, 'zh'),
('COMBO_RULE', '测试|重试', 'strategyTest', 25, true, 'zh'),
('COMBO_RULE', '测试|认证', 'strategyTest', 25, true, 'zh'),
('COMBO_RULE', '测试|安全', 'strategyTest', 25, true, 'zh'),
('COMBO_RULE', '测试|跨域', 'strategyTest', 25, true, 'zh'),
('COMBO_RULE', '测试|缓存', 'strategyTest', 25, true, 'zh'),
('COMBO_RULE', '测试|IP', 'strategyTest', 25, true, 'zh'),
('COMBO_RULE', '测试|Mock', 'strategyTest', 25, true, 'zh'),
('COMBO_RULE', '测试|策略', 'strategyTest', 20, true, 'zh');

INSERT IGNORE INTO intent_configs (config_type, keyword, intent, weight, enabled, language) VALUES
('COMBO_RULE', '验证|限流', 'strategyTest', 25, true, 'zh'),
('COMBO_RULE', '验证|熔断', 'strategyTest', 25, true, 'zh'),
('COMBO_RULE', '验证|认证', 'strategyTest', 25, true, 'zh'),
('COMBO_RULE', '验证|安全', 'strategyTest', 25, true, 'zh'),
('COMBO_RULE', '验证|策略', 'strategyTest', 20, true, 'zh');

INSERT IGNORE INTO intent_configs (config_type, keyword, intent, weight, enabled, language) VALUES
('COMBO_RULE', 'test|rate limit', 'strategyTest', 25, true, 'en'),
('COMBO_RULE', 'test|circuit breaker', 'strategyTest', 25, true, 'en'),
('COMBO_RULE', 'test|timeout', 'strategyTest', 25, true, 'en'),
('COMBO_RULE', 'test|retry', 'strategyTest', 25, true, 'en'),
('COMBO_RULE', 'test|auth', 'strategyTest', 25, true, 'en'),
('COMBO_RULE', 'test|security', 'strategyTest', 25, true, 'en'),
('COMBO_RULE', 'test|cors', 'strategyTest', 25, true, 'en'),
('COMBO_RULE', 'test|cache', 'strategyTest', 25, true, 'en'),
('COMBO_RULE', 'test|strategy', 'strategyTest', 20, true, 'en');

INSERT IGNORE INTO intent_configs (config_type, keyword, intent, weight, enabled, language) VALUES
('COMBO_RULE', 'verify|rate limit', 'strategyTest', 25, true, 'en'),
('COMBO_RULE', 'verify|circuit breaker', 'strategyTest', 25, true, 'en'),
('COMBO_RULE', 'verify|auth', 'strategyTest', 25, true, 'en'),
('COMBO_RULE', 'verify|security', 'strategyTest', 25, true, 'en'),
('COMBO_RULE', 'verify|strategy', 'strategyTest', 20, true, 'en');

INSERT IGNORE INTO intent_configs (config_type, keyword, intent, weight, enabled, language) VALUES
('COMBO_RULE', '如何测试|限流', 'strategyTest', 30, true, 'zh'),
('COMBO_RULE', '如何测试|熔断', 'strategyTest', 30, true, 'zh'),
('COMBO_RULE', '如何测试|安全防护', 'strategyTest', 30, true, 'zh'),
('COMBO_RULE', '如何验证|策略', 'strategyTest', 25, true, 'zh'),
('COMBO_RULE', '怎么测试|策略', 'strategyTest', 25, true, 'zh');

INSERT IGNORE INTO intent_configs (config_type, keyword, intent, weight, enabled, language) VALUES
('COMBO_RULE', 'SQL注入|测试', 'strategyTest', 30, true, 'zh'),
('COMBO_RULE', 'XSS|测试', 'strategyTest', 30, true, 'zh'),
('COMBO_RULE', '注入测试', 'strategyTest', 30, true, 'zh');

INSERT IGNORE INTO intent_configs (config_type, keyword, intent, weight, enabled, language) VALUES
('KEYWORD_WEIGHT', 'Filter链', 'performance', 15, true, 'zh'),
('KEYWORD_WEIGHT', '过滤器链', 'performance', 15, true, 'zh'),
('KEYWORD_WEIGHT', 'filter chain', 'performance', 15, true, 'en'),
('KEYWORD_WEIGHT', 'Filter耗时', 'performance', 15, true, 'zh'),
('KEYWORD_WEIGHT', '过滤器耗时', 'performance', 15, true, 'zh'),
('KEYWORD_WEIGHT', 'filter duration', 'performance', 15, true, 'en'),
('KEYWORD_WEIGHT', '哪个Filter', 'performance', 12, true, 'zh'),
('KEYWORD_WEIGHT', '最慢的Filter', 'performance', 15, true, 'zh'),
('KEYWORD_WEIGHT', 'slowest filter', 'performance', 15, true, 'en'),
('KEYWORD_WEIGHT', 'Filter性能', 'performance', 12, true, 'zh'),
('KEYWORD_WEIGHT', '过滤器性能', 'performance', 12, true, 'zh'),
('KEYWORD_WEIGHT', 'filter performance', 'performance', 12, true, 'en');

INSERT IGNORE INTO intent_configs (config_type, keyword, intent, weight, enabled, language) VALUES
('KEYWORD_WEIGHT', '耗时分析', 'performance', 8, true, 'zh'),
('KEYWORD_WEIGHT', '性能分析', 'performance', 8, true, 'zh'),
('KEYWORD_WEIGHT', '响应慢', 'performance', 8, true, 'zh'),
('KEYWORD_WEIGHT', '请求慢', 'performance', 8, true, 'zh'),
('KEYWORD_WEIGHT', '慢请求', 'performance', 8, true, 'zh'),
('KEYWORD_WEIGHT', 'performance analysis', 'performance', 8, true, 'en'),
('KEYWORD_WEIGHT', 'slow response', 'performance', 8, true, 'en'),
('KEYWORD_WEIGHT', 'slow request', 'performance', 8, true, 'en'),
('KEYWORD_WEIGHT', 'latency', 'performance', 6, true, 'en'),
('KEYWORD_WEIGHT', '响应时间', 'performance', 6, true, 'zh'),
('KEYWORD_WEIGHT', '执行时间', 'performance', 6, true, 'zh');

INSERT IGNORE INTO intent_configs (config_type, keyword, intent, weight, enabled, language) VALUES
('COMBO_RULE', 'Filter|分析', 'performance', 20, true, 'zh'),
('COMBO_RULE', 'Filter|耗时', 'performance', 20, true, 'zh'),
('COMBO_RULE', 'Filter|慢', 'performance', 20, true, 'zh'),
('COMBO_RULE', 'Filter|时间', 'performance', 18, true, 'zh'),
('COMBO_RULE', '过滤器|分析', 'performance', 20, true, 'zh'),
('COMBO_RULE', '过滤器|耗时', 'performance', 20, true, 'zh'),
('COMBO_RULE', 'filter|analysis', 'performance', 20, true, 'en'),
('COMBO_RULE', 'filter|duration', 'performance', 20, true, 'en'),
('COMBO_RULE', 'filter|slow', 'performance', 20, true, 'en'),
('COMBO_RULE', 'filter|time', 'performance', 18, true, 'en');

INSERT IGNORE INTO intent_configs (config_type, keyword, intent, weight, enabled, language) VALUES
('COMBO_RULE', '分析|数据', 'performance', 15, true, 'zh'),
('COMBO_RULE', '分析|统计', 'performance', 15, true, 'zh'),
('COMBO_RULE', '分析|指标', 'performance', 15, true, 'zh'),
('COMBO_RULE', 'analyze|data', 'performance', 15, true, 'en'),
('COMBO_RULE', 'analyze|metrics', 'performance', 15, true, 'en');

INSERT IGNORE INTO intent_configs (config_type, keyword, intent, weight, enabled, language) VALUES
('KEYWORD_WEIGHT', 'Pod维度', 'podAnalysis', 15, true, 'zh'),
('KEYWORD_WEIGHT', '各Pod', 'podAnalysis', 15, true, 'zh'),
('KEYWORD_WEIGHT', 'Pod对比', 'podAnalysis', 15, true, 'zh'),
('KEYWORD_WEIGHT', 'Pod性能', 'podAnalysis', 15, true, 'zh'),
('KEYWORD_WEIGHT', 'Pod分析', 'podAnalysis', 15, true, 'zh');

INSERT IGNORE INTO intent_configs (config_type, keyword, intent, weight, enabled, language) VALUES
('KEYWORD_WEIGHT', 'Pod负载', 'podAnalysis', 12, true, 'zh'),
('KEYWORD_WEIGHT', '哪个Pod', 'podAnalysis', 12, true, 'zh'),
('KEYWORD_WEIGHT', 'pod分析', 'podAnalysis', 10, true, 'zh'),
('KEYWORD_WEIGHT', 'pod对比', 'podAnalysis', 10, true, 'zh'),
('KEYWORD_WEIGHT', 'pod performance', 'podAnalysis', 10, true, 'en'),
('KEYWORD_WEIGHT', 'pod comparison', 'podAnalysis', 10, true, 'en'),
('KEYWORD_WEIGHT', '负载均衡分析', 'podAnalysis', 8, true, 'zh'),
('KEYWORD_WEIGHT', '负载不均衡', 'podAnalysis', 10, true, 'zh');

INSERT IGNORE INTO intent_configs (config_type, keyword, intent, weight, enabled, language) VALUES
('COMBO_RULE', 'Pod|性能', 'podAnalysis', 30, true, 'zh'),
('COMBO_RULE', 'Pod|分析', 'podAnalysis', 30, true, 'zh'),
('COMBO_RULE', 'Pod|对比', 'podAnalysis', 30, true, 'zh'),
('COMBO_RULE', '各Pod|性能', 'podAnalysis', 30, true, 'zh'),
('COMBO_RULE', '压测|Pod', 'podAnalysis', 30, true, 'zh'),
('COMBO_RULE', 'Pod|负载', 'podAnalysis', 25, true, 'zh'),
('COMBO_RULE', 'pod|performance', 'podAnalysis', 30, true, 'en'),
('COMBO_RULE', 'pod|analysis', 'podAnalysis', 30, true, 'en'),
('COMBO_RULE', 'pod|comparison', 'podAnalysis', 30, true, 'en');
