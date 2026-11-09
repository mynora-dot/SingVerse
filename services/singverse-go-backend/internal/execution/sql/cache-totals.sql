SELECT COUNT(*) AS entry_count,COALESCE(SUM(bytes+residue_bytes),0) AS media_bytes FROM song_cache_entry
