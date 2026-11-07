package com.mynora.singverse.media;

/** Reuse local filesystem/HTTP indexing assertions against the configured disposable MySQL schema. */
@org.springframework.test.context.ActiveProfiles(value = "mysql", inheritProfiles = false)
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class MySqlMediaScanIT extends MediaScanHttpTest {
    @Override
    @org.junit.jupiter.api.Test
    void deepPagingIdsUseCoveringFilterOrderIndexes() {
        // A selective fixture avoids empty-table optimizer shortcuts; the production query stays unchanged.
        db.update("INSERT INTO media_source(id,name,source_type,location,location_hash,username,created_at,updated_at) VALUES('source','Plan fixture','LOCAL','/plan-fixture','plan-fixture','','','')");
        var rows = new java.util.ArrayList<Object[]>();
        String parsed = json.valueToTree(new com.mynora.singverse.media.filename.KtvFilenameParser().parse("Song-Artist.mp3")).toString();
        for (int index = 0; index < 5_000; index++) {
            String id = "plan-" + index;
            rows.add(new Object[] {id, id + ".mp3", id, parsed, index % 100 == 0 ? 1 : 0});
        }
        db.batchUpdate("INSERT INTO media_song(id,source_id,relative_path,path_hash,scanned_at,parser_profile_id,parser_revision,result_json,needs_review) VALUES(?,'source',?,?,'2026-11-07T00:00:00Z','default',0,?,?)", rows);
        db.execute("ANALYZE TABLE media_song");
        db.execute("ANALYZE TABLE media_source");
        var songPlan = db.queryForList("EXPLAIN SELECT id, scanned_at FROM media_song WHERE source_id=? AND needs_review=? ORDER BY scanned_at DESC,id ASC LIMIT 20 OFFSET 90000", "source", 1).getFirst();
        org.assertj.core.api.Assertions.assertThat(songPlan.get("key")).isEqualTo("idx_song_source_review_page");
        org.assertj.core.api.Assertions.assertThat(songPlan.get("Extra").toString()).contains("Using index").doesNotContain("filesort");
        var sourcePlan = db.queryForList("EXPLAIN SELECT id, created_at FROM media_source WHERE source_type=? ORDER BY created_at DESC,id ASC LIMIT 20 OFFSET 10000", "LOCAL").getFirst();
        org.assertj.core.api.Assertions.assertThat(sourcePlan.get("key")).isEqualTo("idx_source_type_page");
        org.assertj.core.api.Assertions.assertThat(sourcePlan.get("Extra").toString()).contains("Using index").doesNotContain("filesort");
    }
}
