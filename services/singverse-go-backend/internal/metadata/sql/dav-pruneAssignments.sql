DELETE FROM classification_assignment WHERE song_id IN (SELECT song_id FROM webdav_resource WHERE archived_at<@{before})
