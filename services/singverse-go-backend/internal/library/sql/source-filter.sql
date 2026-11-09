 WHERE (?='' OR LOWER(name) LIKE LOWER(?) ESCAPE '!' OR LOWER(location) LIKE LOWER(?) ESCAPE '!') AND (?='' OR source_type=?) AND (?='' OR parser_profile_id=?)
