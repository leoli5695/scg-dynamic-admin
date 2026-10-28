-- Apply once to the admin MySQL database before deploying the new admin/starter.
-- Existing report_id values remain NULL, so historical rows are not merged.
ALTER TABLE distributed_trace
  ADD COLUMN report_id VARCHAR(36) NULL,
  ADD COLUMN capture_mode VARCHAR(20) NULL,
  ADD COLUMN dropped_span_count INT NULL,
  ADD COLUMN open_span_count INT NULL,
  ADD COLUMN start_time_ms BIGINT NULL,
  MODIFY COLUMN spans MEDIUMTEXT,
  MODIFY COLUMN downstream_services MEDIUMTEXT,
  ADD UNIQUE KEY uk_distributed_trace_report_id (report_id);
