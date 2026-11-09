SELECT ranked.* FROM (
 SELECT job.*,ROW_NUMBER() OVER(PARTITION BY song_id ORDER BY created_at DESC,id DESC) AS ordinal
 FROM media_audio_job job
) ranked WHERE ordinal<=20 ORDER BY song_id,created_at DESC,id DESC
