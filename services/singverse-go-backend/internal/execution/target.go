package execution

import "math"

func defaultText(v map[string]any, key, fallback string) string {
	if v[key] == nil {
		return fallback
	}
	return str(v, key)
}
func defaultNumber(v map[string]any, key string, fallback float64) float64 {
	if v[key] == nil {
		return fallback
	}
	return number(v[key])
}
func bounded(value, low, high float64) float64 { return max(low, min(high, value)) }
func target(body map[string]any, purpose string) (map[string]any, error) {
	quality := defaultText(body, "quality", "auto")
	width, height, bitrate := float64(1920), float64(1080), float64(8000000)
	switch quality {
	case "auto", "1080p":
	case "720p":
		width, height, bitrate = 1280, 720, 4000000
	case "480p":
		width, height, bitrate = 854, 480, 1500000
	default:
		return nil, fail(400, "未知画质")
	}
	start := defaultNumber(body, "start", 0)
	if math.IsNaN(start) || math.IsInf(start, 0) || start < 0 || start > 86400 || (purpose == "OFFLINE" && start != 0) {
		return nil, fail(400, "播放位置无效")
	}
	client := obj(body["client"])
	if quality == "auto" && purpose == "REALTIME" {
		width = bounded(defaultNumber(client, "max_width", width), 2, 7680)
		height = bounded(defaultNumber(client, "max_height", height), 2, 4320)
		bitrate = bounded(defaultNumber(client, "max_bitrate", bitrate), 64000, 100000000)
	}
	codec := defaultText(body, "codec", "h264")
	if !oneOf(codec, "h264", "hevc", "av1") {
		return nil, fail(400, "未知视频编码")
	}
	if purpose == "REALTIME" && codec != "h264" && (!contains(client["video"], codec) || !boolv(client["fmp4"])) {
		return nil, fail(422, "该终端未报告此编码和 fMP4 播放能力")
	}
	fps := float64(30)
	if quality == "auto" && purpose == "REALTIME" {
		fps = bounded(defaultNumber(client, "max_fps", 30), 1, 120)
	}
	if body["client"] != nil {
		width = bounded(defaultNumber(client, "max_width", width), 2, width)
		height = bounded(defaultNumber(client, "max_height", height), 2, height)
		bitrate = bounded(defaultNumber(client, "max_bitrate", bitrate), 64000, bitrate)
		fps = bounded(defaultNumber(client, "max_fps", 30), 1, fps)
	}
	v := map[string]any{"purpose": purpose, "mode": "TRANSCODE", "codec": codec, "width": int(width), "height": int(height), "bitrate": int(bitrate), "audio_bitrate": 192000, "fps": fps, "start": start, "fmp4": boolv(client["fmp4"]), "burn_subtitle": boolv(body["burn_subtitle"])}
	if codec == "h264" {
		constrainAVC(v, client)
	}
	for _, key := range []string{"audio_id", "subtitle_id"} {
		if body[key] != nil {
			if !audioPattern.MatchString(str(body, key)) {
				return nil, fail(400, "无效音轨或字幕")
			}
			v[key] = str(body, key)
		}
	}
	return v, nil
}

var avcLimits = map[int][3]int{
	9: {99, 1485, 128000}, 10: {99, 1485, 64000}, 11: {396, 3000, 192000}, 12: {396, 6000, 384000}, 13: {396, 11880, 768000}, 20: {396, 11880, 2000000}, 21: {792, 19800, 4000000}, 22: {1620, 20250, 4000000}, 30: {1620, 40500, 10000000}, 31: {3600, 108000, 14000000}, 32: {5120, 216000, 20000000}, 40: {8192, 245760, 20000000}, 41: {8192, 245760, 50000000}, 42: {8704, 522240, 50000000}, 50: {22080, 589824, 135000000}, 51: {36864, 983040, 240000000}, 52: {36864, 2073600, 240000000}, 60: {139264, 4177920, 240000000}, 61: {139264, 8355840, 480000000}, 62: {139264, 16711680, 800000000},
}

func constrainAVC(target, client map[string]any) {
	profiles := obj(client["profiles"])["h264"]
	profile := ""
	if contains(profiles, "High") {
		profile = "high"
	} else if contains(profiles, "Main") {
		profile = "main"
	} else if contains(profiles, "Baseline") || contains(profiles, "Constrained Baseline") {
		profile = "baseline"
	}
	if profile != "" {
		target["profile"] = profile
	}
	level := int(integer(obj(client["levels"])["h264"]))
	limits, ok := avcLimits[level]
	if !ok {
		return
	}
	target["level"] = level
	width, height := int(integer(target["width"])), int(integer(target["height"]))
	if ((width+15)/16)*((height+15)/16) > limits[0] {
		width = max(2, min(width, min(limits[0]*16, int(math.Sqrt(float64(limits[0])*float64(width)/float64(height)))*16)))
		height = max(2, min(height, limits[0]/((width+15)/16)*16))
		target["width"], target["height"] = width, height
	}
	macroblocks := ((width + 15) / 16) * ((height + 15) / 16)
	target["fps"] = min(number(target["fps"]), float64(limits[1])/float64(macroblocks))
	bitrate := float64(limits[2])
	if profile == "high" {
		bitrate *= 1.25
	}
	target["bitrate"] = min(number(target["bitrate"]), math.Round(bitrate))
}
func versionVideoCompatible(target, result, client, selected map[string]any) bool {
	codec := defaultText(target, "codec", "h264")
	measured := obj(client["profiles"])[codec] != nil || obj(client["levels"])[codec] != nil || client["max_fps"] != nil || client["max_bit_depth"] != nil
	var video map[string]any
	for _, stream := range array(obj(obj(result["output"])["media"])["streams"]) {
		v := obj(stream)
		if str(v, "kind") == "video" {
			video = v
			break
		}
	}
	if video == nil {
		return !measured && defaultNumber(target, "fps", 30) <= number(selected["fps"])
	}
	if measured && (number(video["fps"]) <= 0 || integer(video["bit_depth"]) <= 0) {
		return false
	}
	profiles := obj(client["profiles"])[str(video, "codec")]
	level := integer(obj(client["levels"])[str(video, "codec")])
	profile := str(video, "profile")
	supported := len(array(profiles)) == 0 || contains(profiles, profile) || (profile == "Constrained Baseline" && contains(profiles, "Baseline")) || (profile == "Constrained High" && contains(profiles, "High"))
	rank := func(v int64) float64 {
		if v == 9 {
			return 10.5
		}
		return float64(v)
	}
	return supported && (level == 0 || (integer(video["level"]) > 0 && rank(integer(video["level"])) <= rank(level))) && number(video["fps"]) <= number(selected["fps"]) && defaultNumber(video, "bit_depth", 8) <= defaultNumber(client, "max_bit_depth", 8)
}
