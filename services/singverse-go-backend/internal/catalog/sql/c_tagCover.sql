SELECT c.*,t.active FROM classification_tag_cover c JOIN classification_tag t ON t.id=c.tag_id WHERE c.tag_id=?;
