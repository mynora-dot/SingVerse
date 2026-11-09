UPDATE background_task SET batch_kind=@{kind},scope_sealed=@{sealed},
      is_batch=CASE WHEN parent_id IS NULL THEN 1 ELSE 0 END
    WHERE id=@{id} AND batch_kind='UNCLASSIFIED'
