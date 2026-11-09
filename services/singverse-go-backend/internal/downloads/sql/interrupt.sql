UPDATE lx_download_item SET status='INTERRUPTED',active_key=NULL,resolved_active_key=NULL,message='服务重启，重新尝试将从头下载',updated_at=@{now} WHERE status IN ('RESOLVING','DOWNLOADING')
