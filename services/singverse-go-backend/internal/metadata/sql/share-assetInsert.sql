INSERT INTO webdav_share_asset(job_id,hash) SELECT @{id},@{hash} WHERE NOT EXISTS(SELECT 1 FROM webdav_share_asset WHERE job_id=@{id} AND hash=@{hash})
