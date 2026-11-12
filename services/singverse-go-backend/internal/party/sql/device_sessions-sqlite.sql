SELECT id FROM tv_record WHERE kind='session' AND json_extract(document,'$.deviceId')=? ORDER BY id
