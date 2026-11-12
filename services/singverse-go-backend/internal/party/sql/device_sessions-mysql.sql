SELECT id FROM tv_record WHERE kind='session' AND JSON_UNQUOTE(JSON_EXTRACT(document,'$.deviceId'))=? ORDER BY id
