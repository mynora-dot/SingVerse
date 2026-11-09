UPDATE background_scan_scope SET reason=@{reason},updated_at=@{now} WHERE state='PENDING' AND reason <> @{reason}
