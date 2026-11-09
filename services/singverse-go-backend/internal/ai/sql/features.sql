SELECT * FROM ai_feature ORDER BY CASE id WHEN 'classification' THEN 0 WHEN 'default-search' THEN 1 ELSE 2 END,id;
