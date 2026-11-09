UPDATE background_task SET parent_id=@{parent_id},name=@{name},subject_name=@{subject_name},source_id=@{source_id},
      song_id=@{song_id},status=@{status},business_status=@{business_status},stage=@{stage},completed=@{completed},
      total=@{total},progress_unit=@{progress_unit},statistics_json=@{statistics_json},error=@{error},
      updated_at=@{updated_at},started_at=@{started_at},finished_at=@{finished_at},expires_at=@{expires_at},
      items_available=@{items_available},time_incomplete=@{time_incomplete},fingerprint=@{fingerprint} WHERE id=@{id}
