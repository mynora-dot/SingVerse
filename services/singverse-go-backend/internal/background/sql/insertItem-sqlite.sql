INSERT INTO background_task_item(task_id,id,song_id,title,status,message,execution_json,source_updated_at,execution_updated_at)
    VALUES(@{task_id},@{id},@{song_id},@{title},@{status},@{message},@{execution_json},@{source_updated_at},@{execution_updated_at})
