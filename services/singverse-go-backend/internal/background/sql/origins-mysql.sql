SELECT s.* FROM background_scan_scope s JOIN background_task b ON b.id=s.batch_id
    WHERE s.song_id=@{songId} AND s.fingerprint=@{fingerprint} AND s.state IN ('PENDING','PROBING','FAST')
    ORDER BY b.created_at,s.batch_id
