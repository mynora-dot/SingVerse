package library

import (
	"encoding/json"
	"strconv"
)

// projectSong avoids JSON serialization for the common filename-only bulk read.
// Rich metadata retains the established normalization path for cross-module consumers.
func projectSong(song Song) (map[string]any, error) {
	if song.Metadata != nil {
		out := map[string]any{}
		e := decodeSongMap(encode(song), &out)
		return out, e
	}
	r := song.Result
	diagnostics := any(nil)
	if r.Diagnostics != nil {
		a := make([]any, len(r.Diagnostics))
		for i, d := range r.Diagnostics {
			a[i] = map[string]any{"code": d.Code, "detail": d.Detail, "confidenceDelta": json.Number(strconv.FormatFloat(d.ConfidenceDelta, 'f', -1, 64))}
		}
		diagnostics = a
	}
	result := map[string]any{"originalFilename": r.OriginalFilename, "normalizedFilename": r.NormalizedFilename, "title": optional(r.Title), "artists": stringArray(r.Artists), "language": r.Language, "versionType": r.VersionType, "songCode": optional(r.SongCode), "extension": optional(r.Extension), "qualityTags": stringArray(r.QualityTags), "confidence": json.Number(strconv.FormatFloat(r.Confidence, 'f', -1, 64)), "parseStatus": r.ParseStatus, "parserRule": r.ParserRule, "unparsedTokens": stringArray(r.UnparsedTokens), "needsReview": r.NeedsReview, "diagnostics": diagnostics}
	var size any
	if song.FileSize != nil {
		size = json.Number(strconv.FormatInt(*song.FileSize, 10))
	}
	classification := song.Classification
	if current, ok := classification.(map[string]any); ok {
		m := make(map[string]any, len(current))
		for k, v := range current {
			m[k] = v
		}
		if revision, ok := current["revision"].(int64); ok {
			m["revision"] = json.Number(strconv.FormatInt(revision, 10))
		}
		classification = m
	}
	out := map[string]any{"id": song.ID, "sourceId": song.SourceID, "sourceName": song.SourceName, "relativePath": song.RelativePath, "fileSize": size, "modifiedAt": optional(song.ModifiedAt), "scannedAt": song.ScannedAt, "parserProfileId": song.ParserProfileID, "parserRevision": json.Number(strconv.FormatInt(song.ParserRevision, 10)), "selectedRuleId": optional(song.SelectedRuleID), "result": result, "metadata": nil, "classification": classification}
	if song.MediaVerification != nil {
		out["mediaVerification"] = *song.MediaVerification
	}
	return out, nil
}
func optional(v *string) any {
	if v == nil {
		return nil
	}
	return *v
}
func stringArray(v []string) any {
	if v == nil {
		return nil
	}
	a := make([]any, len(v))
	for i, s := range v {
		a[i] = s
	}
	return a
}
