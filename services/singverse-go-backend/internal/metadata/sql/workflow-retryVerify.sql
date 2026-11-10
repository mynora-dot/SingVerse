UPDATE media_import_entry SET verification=NULL WHERE job_id=@{id} AND verification IN ('MISSING','MISMATCH','UNREACHABLE')
