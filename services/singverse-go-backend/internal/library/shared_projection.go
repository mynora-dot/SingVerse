package library

import "math"

// Shared fields are a separate overlay. Receiving a package must never relabel
// a locally read tag or an administrator's confirmation as shared information.
func sharedMetadata(song Song, value any, raw *string) (any, error) {
 if raw == nil { return value, nil }
 shared, err := metadataObject(raw)
 if err != nil { return nil, err }
 m, _ := value.(map[string]any)
 if m == nil || m["stale"] == true {
  m = map[string]any{"track": map[string]any{}, "fields": []string{}, "origins": map[string]any{}, "stale":false, "appliedAt":song.ScannedAt}
 }
 track, _ := m["track"].(map[string]any)
 origins, _ := m["origins"].(map[string]any)
 if track == nil { track=map[string]any{}; m["track"]=track }
 if origins == nil { origins=map[string]any{}; m["origins"]=origins }
 fields, _ := m["fields"].([]string)
 if fields == nil { if a,ok:=m["fields"].([]any); ok { for _,v:=range a { if s,ok:=v.(string);ok { fields=append(fields,s) } } } }
 tags,_:=shared["tags"].(map[string]any)
 supplied:=map[string]any{}
 for _, f:=range []string{"title","artists","album","releaseDate"} { if v:=tags[f]; !projectionEmpty(v) { supplied[f]=v } }
 if v:=shared["aliases"]; !projectionEmpty(v) { supplied["aliases"]=v }
 if tech,ok:=shared["technical"].(map[string]any);ok { if d,ok:=tech["duration"].(float64);ok && d>0 && d<604800 && !math.IsNaN(d) { supplied["durationMs"]=int64(math.Round(d*1000)) } }
 if key,ok:=shared["cover"].(string);ok && len(key)==64 { supplied["cover"]="embedded:"+song.ID }
 for f,v:=range supplied {
  if hasField(fields,f) && originKind(origins[f])!="SHARED" { continue }
  name:=f; if f=="cover" {name="coverUrl"}
  track[name]=v; fields=addField(fields,f)
  origins[f]=map[string]any{"kind":"SHARED","provider":nil,"externalId":shared["collectionId"],"appliedAt":song.ScannedAt}
 }
 m["fields"]=fields
 if len(fields)==0 { return value,nil }
 return m,nil
}
