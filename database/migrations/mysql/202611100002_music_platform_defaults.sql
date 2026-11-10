-- migrate:up
-- Only replace the untouched installation seed; saved preferences remain intact.
UPDATE metadata_settings
SET enabled = 1, providers_json = '["NETEASE","QQ","KUGOU","KUWO","QISHUI"]'
WHERE id = 1 AND enabled = 0 AND providers_json = '["NETEASE"]'
  AND auto_apply = 0 AND threshold = 0.95
  AND min_interval_seconds = 3 AND max_interval_seconds = 8
  AND updated_at = '2026-10-28T00:00:00Z';

-- migrate:down
UPDATE metadata_settings
SET enabled = 0, providers_json = '["NETEASE"]'
WHERE id = 1 AND enabled = 1
  AND providers_json = '["NETEASE","QQ","KUGOU","KUWO","QISHUI"]'
  AND auto_apply = 0 AND threshold = 0.95
  AND min_interval_seconds = 3 AND max_interval_seconds = 8
  AND updated_at = '2026-10-28T00:00:00Z';
