package com.mynora.singverse.media.service;

import com.mynora.singverse.media.dto.MediaSongDtos.Page;
import com.mynora.singverse.media.repository.MediaSongRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MediaSongService {
    private final MediaSongRepository repository;
    @org.springframework.beans.factory.annotation.Autowired private com.mynora.singverse.media.classification.ClassificationMapper classifications;
    public MediaSongService(MediaSongRepository repository) { this.repository = repository; }

    @org.springframework.transaction.annotation.Transactional
    public Page list(String sourceId, String keyword, String status, int page, int size) {
        return list(sourceId, keyword, status, page, size, java.util.List.of(), false);
    }
    @org.springframework.transaction.annotation.Transactional
    public Page list(String sourceId, String keyword, String status, int page, int size, java.util.List<String> tagIds, boolean unclassified) {
        if (page < 0 || page > 1000000 || size < 1 || size > 100) throw bad("分页参数超出范围");
        if (sourceId != null && (!sourceId.matches("[A-Za-z0-9-]{1,36}"))) throw bad("媒体来源参数无效");
        if (keyword != null && (keyword.length() > 200 || keyword.chars().anyMatch(Character::isISOControl)))
            throw bad("搜索文本不能超过 200 个字符或包含控制字符");
        Integer review = switch (status) {
            case "ALL" -> null;
            case "REVIEW" -> 1;
            case "MATCHED" -> 0;
            default -> throw bad("请选择有效的解析状态");
        };
        String pattern = keyword == null || keyword.isBlank() ? null
                : "%" + keyword.trim().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        java.util.Map<String,java.util.List<String>> groups=new java.util.TreeMap<>();
        if(tagIds==null || tagIds.size()>40) throw bad("分类筛选最多选择 40 个标签");
        if(!tagIds.isEmpty()) {
            var tags=classifications.tags();
            for(String id:tagIds) {
                var tag=tags.stream().filter(t->id.equals(t.get("id")) && ((Number)t.get("active")).intValue()==1).findFirst().orElseThrow(()->bad("标签不存在或已停用"));
                groups.computeIfAbsent(tag.get("dimension").toString(),k->new java.util.ArrayList<>()).add(id);
            }
        }
        return repository.search(sourceId, pattern, review, page, size, groups, unclassified);
    }
    private static ResponseStatusException bad(String detail) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, detail);
    }
}
