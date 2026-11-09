INSERT INTO background_task(id,type,business_id,parent_id,name,subject_name,source_id,song_id,status,business_status,
      stage,completed,total,progress_unit,statistics_json,error,created_at,updated_at,started_at,finished_at,expires_at,
      items_available,time_incomplete,fingerprint)
    VALUES(@{id},@{type},@{business_id},@{parent_id},@{name},@{subject_name},@{source_id},@{song_id},@{status},@{business_status},
      @{stage},@{completed},@{total},@{progress_unit},@{statistics_json},@{error},@{created_at},@{updated_at},
      @{started_at},@{finished_at},@{expires_at},@{items_available},@{time_incomplete},@{fingerprint})

    ON CONFLICT(id) DO NOTHING
