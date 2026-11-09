INSERT INTO webdav_access_budget(access_group,day_key) SELECT @{group},@{day} WHERE NOT EXISTS(SELECT 1 FROM webdav_access_budget WHERE access_group=@{group})
