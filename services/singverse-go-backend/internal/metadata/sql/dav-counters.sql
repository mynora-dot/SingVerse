UPDATE media_scan_task SET added_songs=@{added},reused_songs=@{reused},missing_songs=@{missing},removed_songs=@{removed} WHERE id=@{taskId}
