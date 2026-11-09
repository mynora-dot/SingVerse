SELECT i.image_bytes,a.active FROM artist_image i JOIN artist a ON a.id=i.artist_id WHERE i.artist_id=? AND i.image_kind=?;
