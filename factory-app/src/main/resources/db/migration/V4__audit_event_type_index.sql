-- Serves the paged audit feed: event_type filter with ORDER BY id DESC.
CREATE INDEX idx_audit_event_type ON audit_event (event_type, id);
-- Serves the dashboard's windowed aggregations: event_type IN (...) AND occurred_at >= :since.
CREATE INDEX idx_audit_event_type_time ON audit_event (event_type, occurred_at);
