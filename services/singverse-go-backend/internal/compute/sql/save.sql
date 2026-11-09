UPDATE compute_node_settings SET mode = ?, endpoint = ?, secret_encrypted = ?, mappings_json = ?, revision = revision + 1 WHERE id = 1 AND revision = ?
