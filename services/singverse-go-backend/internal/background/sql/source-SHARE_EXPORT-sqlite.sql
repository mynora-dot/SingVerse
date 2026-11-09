SELECT t.id,t.source_id,s.name AS subject_name,t.state AS raw_status,t.total_count,t.processed_count,
          t.part_count,t.error,t.created_at,CASE WHEN t.base_id IS NULL THEN 0 ELSE 1 END AS incremental FROM webdav_share_job t
        LEFT JOIN media_source s ON s.id=t.source_id WHERE t.id=@{businessId}
