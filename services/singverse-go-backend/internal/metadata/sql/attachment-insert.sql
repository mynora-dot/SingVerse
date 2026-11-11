INSERT INTO metadata_attachment_task(media_id,cover_url,cover_revision,local_decision,origin_json,status,updated_at) VALUES(@{songId},@{url},@{revision},@{localDecision},@{origin},'PENDING',@{now})
