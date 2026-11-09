UPDATE webdav_share_job SET total_count=(SELECT COUNT(*) FROM webdav_share_entry WHERE job_id=@{id}) WHERE id=@{id}
