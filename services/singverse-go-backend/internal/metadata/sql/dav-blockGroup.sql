UPDATE webdav_access_budget SET blocked_until=CASE WHEN blocked_until IS NULL OR blocked_until<@{until} THEN @{until} ELSE blocked_until END WHERE access_group=@{group}
