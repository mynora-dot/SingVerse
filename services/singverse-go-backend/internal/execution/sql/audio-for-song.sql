SELECT * FROM media_audio_job WHERE song_id=@{song} AND fingerprint=@{fingerprint} ORDER BY created_at DESC,id DESC LIMIT 20
