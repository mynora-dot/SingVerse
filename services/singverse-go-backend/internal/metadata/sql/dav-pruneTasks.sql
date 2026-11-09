DELETE FROM webdav_metadata_task WHERE song_id IN(SELECT song_id FROM webdav_resource WHERE archived_at<@{before})
