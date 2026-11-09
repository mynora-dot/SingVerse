UPDATE background_task SET batch_status=@{batch_status},batch_stage=@{batch_stage},batch_counts_json=@{batch_counts_json},
      batch_finished_at=@{batch_finished_at},batch_expires_at=@{batch_expires_at},batch_updated_at=@{batch_updated_at}
    WHERE id=@{id}
