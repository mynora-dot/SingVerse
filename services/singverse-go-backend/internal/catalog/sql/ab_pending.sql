SELECT i.*,b.provider FROM artist_artwork_item i JOIN artist_artwork_batch b ON b.id=i.batch_id WHERE i.status='PENDING' ORDER BY b.created_at,b.id,i.ordinal LIMIT 1;
