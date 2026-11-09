UPDATE lx_download_batch SET status=@{status},updated_at=@{now},started_at=COALESCE(started_at,@{started}),finished_at=@{finished} WHERE id=@{id}
