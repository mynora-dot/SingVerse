SELECT kind,secret_hash,document,revision FROM tv_record WHERE kind=? ORDER BY id LIMIT 1000;
