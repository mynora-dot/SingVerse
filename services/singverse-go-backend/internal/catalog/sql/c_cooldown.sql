UPDATE classification_settings SET next_request_at=CASE WHEN next_request_at > ? THEN next_request_at ELSE ? END WHERE id=1;
