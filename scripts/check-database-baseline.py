"""Exercise the fresh SQLite baseline using dbmate, without touching local data."""

import os
import json
from pathlib import Path
import re
import sqlite3
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[1]
BASELINE = "202611070001_initial_schema.sql"
VERSION = BASELINE.split("_", 1)[0]
MIGRATIONS = (BASELINE, "202611080001_background_tasks.sql", "202611090001_background_task_batches.sql", "202611090002_audio_separation_profiles.sql", "202611090003_lx_downloads.sql", "202611100001_artist_artwork_batches.sql", "202611100002_music_platform_defaults.sql")
VERSIONS = sorted((name.split("_", 1)[0],) for name in MIGRATIONS)


def require(condition, message):
  if not condition:
    raise RuntimeError(message)


def snapshot(path):
  with sqlite3.connect(path) as db:
    db.execute("PRAGMA foreign_keys=ON")
    require(db.execute("PRAGMA integrity_check").fetchall() == [("ok",)], "SQLite integrity check failed")
    require(not db.execute("PRAGMA foreign_key_check").fetchall(), "Foreign key check failed")
    schema = db.execute("SELECT type,name,tbl_name,sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY type,name").fetchall()
    tables = [row[1] for row in schema if row[0] == "table"]
    data = {}
    for table in tables:
      # Table names come from sqlite_master; quoting also handles reserved names.
      quoted = '"' + table.replace('"', '""') + '"'
      data[table] = sorted(db.execute(f"SELECT * FROM {quoted}").fetchall(), key=repr)
    return schema, data


def verify_fresh(path):
  schema, data = snapshot(path)
  require(data["schema_migrations"] == VERSIONS, "Unexpected migration versions")
  with sqlite3.connect(path) as db:
    db.execute("PRAGMA foreign_keys=ON")
    require(db.execute("SELECT policy,revision,high_watermark_percent,low_watermark_percent FROM song_cache_settings WHERE id=1").fetchone()
            == ("HYBRID", 0, 90, 75), "Wrong fresh cache defaults")
    require(db.execute("SELECT ready,phase,cursor_started_at,cursor_id FROM playback_activity_state WHERE id=1").fetchone()
            == (1, "READY", 0, ""), "Fresh playback statistics must be ready")
    music = db.execute("SELECT enabled,providers_json,auto_apply,threshold,min_interval_seconds,max_interval_seconds FROM metadata_settings WHERE id=1").fetchone()
    require(music[0] == 1 and json.loads(music[1]) == ["NETEASE", "QQ", "KUGOU", "KUWO", "QISHUI"] and music[2:] == (0, .95, 3, 8), "Wrong fresh music platform defaults")
    require(data["lx_download_settings"] == [(1, None, None, 0)], "Wrong download binding defaults")
    for table in ("artist_artwork_batch", "artist_artwork_item", "lx_script", "lx_catalog_track", "lx_download_batch", "lx_download_item", "lx_download_asset", "auth_account", "media_source", "media_song", "playback_history", "playback_attempt", "song_cache_entry", "song_playback_profile", "background_task", "background_task_item", "background_task_relation", "background_scan_scope"):
      require(not data[table], f"Fresh database unexpectedly contains {table} data")
    for table in ("media_song", "media_scan_song"):
      columns = {row[1]: row for row in db.execute(f"PRAGMA table_info({table})")}
      require(all(columns[name][3] == 0 for name in ("file_size", "modified_at")), f"{table} must accept unknown WebDAV attributes")
    probes = (
      "INSERT INTO song_playback_profile(song_id,heat) VALUES('invalid-heat',1.01)",
      "INSERT INTO song_playback_profile(song_id,pinned) VALUES('invalid-pin',2)",
      "INSERT INTO song_cache_lease(session_id,cache_id,renewed_at) VALUES('invalid-lease','missing-cache',0)",
    )
    for sql in probes:
      db.execute("SAVEPOINT baseline_constraint")
      try:
        db.execute(sql)
      except sqlite3.IntegrityError:
        pass
      else:
        raise RuntimeError("Expected a cache protection constraint to reject invalid data")
      finally:
        db.execute("ROLLBACK TO baseline_constraint")
        db.execute("RELEASE baseline_constraint")
  return schema, data


def main():
  for dialect in ("sqlite", "mysql"):
    files = sorted(path.name for path in (ROOT / "database/migrations" / dialect).glob("*.sql"))
    require(files == list(MIGRATIONS), f"{dialect}: unexpected migration sequence: {files}")
    for name in MIGRATIONS:
      migration = (ROOT / "database/migrations" / dialect / name).read_text()
      require(migration.count("-- migrate:up") == 1 and migration.count("-- migrate:down") == 1, f"{dialect}: invalid sections in {name}")
    source = (ROOT / "database/migrations" / dialect / BASELINE).read_text()
    require(source.count("-- migrate:up") == 1 and source.count("-- migrate:down") == 1, f"{dialect}: invalid dbmate sections")
    up = source.split("-- migrate:down", 1)[0]
    up = re.sub(r"--[^\n]*", "", up)
    require(not re.search(r"\b(?:ALTER\s+TABLE|UPDATE|DELETE\s+FROM|DROP\s+TABLE)\b", up, re.I),
            f"{dialect}: baseline still contains historical transformations")

  binary = Path(os.environ.get("DBMATE_BIN", ROOT / ".tools/dbmate")).resolve()
  require(binary.is_file(), "dbmate missing; run make dbmate-setup")
  with tempfile.TemporaryDirectory(prefix="singverse-fresh-db-") as directory:
    path = Path(directory) / "fresh.sqlite"
    environment = os.environ.copy()
    environment["DATABASE_URL"] = "sqlite:" + str(path)
    environment.pop("DBMATE_DRIVER", None)
    environment.pop("DBMATE_WAIT", None)
    command = [str(binary), "--env-file", os.devnull, "--no-dump-schema",
               "--migrations-dir", str(ROOT / "database/migrations/sqlite"),
               "--migrations-table", "schema_migrations"]

    def migrate(action):
      result = subprocess.run(command + [action], env=environment, capture_output=True, text=True, timeout=60)
      require(result.returncode == 0, f"dbmate {action} failed: {result.stderr.strip()}")

    migrate("up")
    initial = verify_fresh(path)
    migrate("migrate")
    require(snapshot(path) == initial, "Second migrate changed schema or seeds")
    migrate("down")
    with sqlite3.connect(path) as db:
      require(sorted(db.execute("SELECT version FROM schema_migrations").fetchall()) == VERSIONS[:-1], "Music default rollback must retain features")
      require(db.execute("SELECT enabled,providers_json,auto_apply FROM metadata_settings WHERE id=1").fetchone() == (0, '["NETEASE"]', 0), "Music default rollback changed the wrong seed")
    migrate("down")
    with sqlite3.connect(path) as db:
      require(sorted(db.execute("SELECT version FROM schema_migrations").fetchall()) == VERSIONS[:-2], "Artwork rollback must retain prior features")
      require(not db.execute("SELECT name FROM sqlite_master WHERE name IN ('artist_artwork_batch','artist_artwork_item')").fetchall(), "Artwork rollback left queue tables")
    migrate("down")
    with sqlite3.connect(path) as db:
      require(sorted(db.execute("SELECT version FROM schema_migrations").fetchall()) == VERSIONS[:-3], "LX rollback must retain prior features")
      require(not db.execute("SELECT name FROM sqlite_master WHERE name LIKE 'lx_%'").fetchall(), "LX rollback left download tables")
    migrate("down")
    with sqlite3.connect(path) as db:
      require(sorted(db.execute("SELECT version FROM schema_migrations").fetchall()) == VERSIONS[:-4], "Audio rollback must retain background tasks")
      require(not db.execute("SELECT name FROM sqlite_master WHERE name='media_audio_selection'").fetchall(), "Audio rollback left selection table")
      for table in ("media_audio_job", "audio_batch"):
        require("recipe_json" not in {row[1] for row in db.execute(f"PRAGMA table_info({table})")}, "Audio rollback left recipe column")
    migrate("down")
    with sqlite3.connect(path) as db:
      require(sorted(db.execute("SELECT version FROM schema_migrations").fetchall()) == VERSIONS[:-5], "Batch rollback must retain prior schema")
      require(not db.execute("SELECT name FROM sqlite_master WHERE name IN ('background_task_relation','background_scan_scope')").fetchall(), "Batch rollback left relation tables")
      columns = {row[1] for row in db.execute("PRAGMA table_info(background_task)")}
      require("is_batch" not in columns, "Batch rollback left columns")
      for task_id, parent in (("kept-batch", None), ("kept-child", "kept-batch")):
        db.execute("INSERT INTO background_task(id,type,business_id,parent_id,name,subject_name,status,business_status,stage,progress_unit,statistics_json,error,created_at,updated_at,items_available,time_incomplete,fingerprint) VALUES(?,'AUDIO_JOB',?,?,'既有记录','歌曲','QUEUED','QUEUED','QUEUED','ITEMS','{}','',1,1,0,1,'fp')", (task_id,task_id,parent))
      db.execute("INSERT INTO song_playback_profile(song_id,pinned,pin_revision) VALUES('upgrade-preserved',1,3)")
      db.execute("UPDATE song_cache_settings SET max_bytes=67108864,revision=7 WHERE id=1")
      db.execute("UPDATE metadata_settings SET enabled=0, providers_json=?, auto_apply=1, threshold=.98, min_interval_seconds=0, max_interval_seconds=20, updated_at='2026-11-10T00:00:00Z' WHERE id=1", ('["QQ"]',))
    migrate("migrate")
    with sqlite3.connect(path) as db:
      require(db.execute("SELECT enabled,providers_json,auto_apply,threshold,min_interval_seconds,max_interval_seconds FROM metadata_settings WHERE id=1").fetchone() == (0, '["QQ"]', 1, .98, 0, 20), "Music defaults changed saved preferences")
      require(db.execute("SELECT pinned,pin_revision FROM song_playback_profile WHERE song_id='upgrade-preserved'").fetchone() == (1,3), "Feature upgrade changed song data")
      require(db.execute("SELECT max_bytes,revision FROM song_cache_settings WHERE id=1").fetchone() == (67108864,7), "Feature upgrade changed settings")
      require(db.execute("SELECT id,is_batch FROM background_task ORDER BY id").fetchall() == [("kept-batch",1),("kept-child",0)], "Batch upgrade lost original tasks")
      require(db.execute("SELECT batch_id,task_id FROM background_task_relation").fetchall() == [("kept-batch","kept-child")], "Batch upgrade lost confirmed ancestry")
    migrate("down")
    migrate("down")
    migrate("down")
    migrate("down")
    migrate("down")
    with sqlite3.connect(path) as db:
      require(db.execute("SELECT enabled,providers_json,auto_apply,threshold,min_interval_seconds,max_interval_seconds FROM metadata_settings WHERE id=1").fetchone() == (0, '["QQ"]', 1, .98, 0, 20), "Music defaults changed saved preferences")
      require(db.execute("SELECT pinned,pin_revision FROM song_playback_profile WHERE song_id='upgrade-preserved'").fetchone() == (1,3), "Feature rollback changed song data")
      require(db.execute("SELECT COUNT(*) FROM background_task").fetchone() == (2,), "Batch rollback changed task records")
    migrate("down")
    migrate("down")
    with sqlite3.connect(path) as db:
      remaining = db.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'").fetchall()
      require(remaining == [("schema_migrations",)], f"Rollback left business tables: {remaining}")
      require(not db.execute("SELECT * FROM schema_migrations").fetchall(), "Rollback left a migration version")
    migrate("migrate")
    require(verify_fresh(path) == initial, "Recreating baseline changed schema or seeds")
    tables = sum(row[0] == "table" and row[1] != "schema_migrations" for row in initial[0])
    indexes = sum(row[0] == "index" and row[3] is not None for row in initial[0])
    print(f"通过：SQLite 从零建库、重复迁移、增量升级保留数据、逐级回退、重建及约束；{tables} 张业务表，{indexes} 个显式索引。MySQL 需单独运行真实集成检查。")


if __name__ == "__main__":
  main()
