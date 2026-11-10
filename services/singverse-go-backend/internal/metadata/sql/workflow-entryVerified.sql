UPDATE media_import_entry SET verification=@{verification},verification_error=@{verificationError} WHERE job_id=@{id} AND ordinal=@{ordinal}
