SELECT COUNT(*) AS total,COUNT(DISTINCT v.parent_id) AS songs,COUNT(DISTINCT m.parent_id) AS variants,
 COUNT(DISTINCT CASE WHEN d.identity_complete=1 THEN d.id ELSE NULL END) AS identity_complete,
 COALESCE(SUM(CASE WHEN l.state='PENDING' THEN 1 ELSE 0 END),0) AS pending_verification,
 COALESCE(SUM(CASE WHEN l.state IN ('MISSING','MISMATCH','UNREACHABLE','UNBOUND') THEN 1 ELSE 0 END),0) AS media_issues
 FROM metadata_location l JOIN metadata_document m ON m.id=l.media_id JOIN metadata_document v ON v.id=m.parent_id JOIN metadata_document d ON d.id=v.parent_id WHERE l.source_id=?
