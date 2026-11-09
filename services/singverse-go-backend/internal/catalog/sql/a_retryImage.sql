UPDATE artist_image_fetch SET state='PENDING',attempts=0,fetch_after='',generation=generation+1,candidates_json=NULL WHERE artist_id=? AND image_kind=?;
