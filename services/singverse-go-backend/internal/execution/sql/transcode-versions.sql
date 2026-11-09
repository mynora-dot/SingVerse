SELECT * FROM media_transcode WHERE song_id=@{song} AND fingerprint=@{fingerprint} AND purpose='OFFLINE' AND state='SUCCEEDED' AND pipeline_version='2' ORDER BY created_at DESC
