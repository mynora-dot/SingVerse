UPDATE media_metadata_file SET error='衍生文件写入失败，后台将重试',updated_at=@{now} WHERE song_id=@{songId} AND revision=@{revision}
