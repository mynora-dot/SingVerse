UPDATE artist SET fetch_state='PENDING',fetch_attempts=0,fetch_after='',fetch_generation=fetch_generation+1 WHERE id=?;
