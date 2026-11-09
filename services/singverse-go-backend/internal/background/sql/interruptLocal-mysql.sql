UPDATE background_task SET status='INTERRUPTED',business_status='INTERRUPTED',stage='INTERRUPTED',
      scope_sealed=1,error='服务已重启，扫描中断；请重新扫描',updated_at=@{now},finished_at=@{now},expires_at=@{expires}
    WHERE type='LOCAL_SCAN' AND status IN ('QUEUED','RUNNING')
