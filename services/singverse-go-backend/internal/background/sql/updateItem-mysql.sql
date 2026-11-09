UPDATE background_task_item SET song_id=@{song_id},title=@{title},status=@{status},message=@{message},
      execution_json=@{execution_json},source_updated_at=@{source_updated_at},execution_updated_at=@{execution_updated_at}
    WHERE task_id=@{task_id} AND id=@{id}
