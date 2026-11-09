UPDATE metadata_item SET status = @{status}, candidates_json = @{candidates}, error_message = @{error}, updated_at = @{now}
    WHERE id = @{id} AND status = @{expected}
