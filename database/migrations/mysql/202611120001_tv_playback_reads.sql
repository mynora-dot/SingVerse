-- migrate:up
CREATE INDEX playback_history_device_recent ON playback_history(client_type,session_id,started_at,id);

-- migrate:down
DROP INDEX playback_history_device_recent ON playback_history;
