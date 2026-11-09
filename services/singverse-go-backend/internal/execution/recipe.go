package execution

import "strings"

const legacyProfile = "LEGACY_DEMUCS"

type audioRecipe struct {
	profile, model, format, source string
	preview                        bool
}

func recipe(value map[string]any) (audioRecipe, error) {
	r := audioRecipe{profile: str(value, "separationProfile"), model: str(value, "modelId"), format: str(value, "outputFormat"), source: str(value, "sourceAudioId")}
	if strings.TrimSpace(r.profile) == "" {
		r.profile = legacyProfile
	}
	if strings.TrimSpace(r.format) == "" {
		r.format = "aac"
	}
	expected := ""
	switch r.profile {
	case legacyProfile:
		expected = "htdemucs"
	case "DSP_FAST":
		expected = "center-cancel-v1"
	case "AI_CLEAN":
		expected = "uvr-mdxnet-9482"
	default:
		return r, fail(400, "未知伴奏方案")
	}
	if r.model != "" && r.model != expected {
		return r, fail(400, "模型与伴奏方案不匹配")
	}
	r.model = expected
	if !oneOf(r.format, "aac", "flac") {
		return r, fail(400, "未知伴奏输出格式")
	}
	if r.source != "" && !sourceAudioPattern.MatchString(r.source) {
		return r, fail(400, "源伴奏音轨标识无效")
	}
	if value["preview"] != nil {
		var ok bool
		r.preview, ok = value["preview"].(bool)
		if !ok {
			return r, fail(400, "试听参数必须是布尔值")
		}
	}
	return r, nil
}
func storedRecipe(row map[string]any) audioRecipe {
	r, err := recipe(decoded(row["recipe_json"]))
	if err != nil {
		return audioRecipe{profile: legacyProfile, model: "htdemucs", format: "aac"}
	}
	return r
}
func (r audioRecipe) legacy() bool {
	return r.profile == legacyProfile && r.format == "aac" && !r.preview && r.source == ""
}
func (r audioRecipe) playable() bool { return !r.preview && r.format == "aac" }
func (r audioRecipe) extension() string {
	if r.format == "flac" {
		return "flac"
	}
	return "m4a"
}
func (r audioRecipe) view() map[string]any {
	v := map[string]any{"separationProfile": r.profile, "modelId": r.model, "outputFormat": r.format, "preview": r.preview, "recipeVersion": 1, "precisionPolicy": "backend-default-v1"}
	if r.profile == "AI_CLEAN" {
		v["modelDigest"] = "9d78f8566fa8198065214ab628be1de966a500c57786695aa4b13e2b27a7727d"
	}
	if r.profile == "DSP_FAST" {
		v["dspFilterVersion"] = 1
	}
	if r.source != "" {
		v["sourceAudioId"] = r.source
	}
	return v
}
func (r audioRecipe) validateBackend(backend string) error {
	if !oneOf(backend, "", "auto", "cpu", "cuda", "rocm", "mps", "openvino", "openvino-cpu", "rknn", "tensorrt", "migraphx", "coreml", "directml", "mlx") {
		return fail(400, "未知推理后端")
	}
	if r.profile == "DSP_FAST" && !oneOf(backend, "", "auto", "cpu") {
		return fail(400, "极速档使用 CPU，无需选择 GPU")
	}
	if r.profile == "AI_CLEAN" && oneOf(backend, "rocm", "mps") {
		return fail(400, "轻量模型请选择对应 ONNX 后端或 CPU")
	}
	return nil
}
func (r audioRecipe) ready(node map[string]any, backend string) bool {
	separation := obj(node["separation"])
	if r.profile == legacyProfile && r.source == "" {
		if !r.legacy() && integer(separation["version"]) < 1 {
			return false
		}
		if backend == "" || backend == "auto" {
			return str(node, "selected") != ""
		}
		for _, item := range array(node["backends"]) {
			v := obj(item)
			if str(v, "id") == backend && str(v, "state") == "AVAILABLE" {
				return true
			}
		}
		return false
	}
	if integer(separation["version"]) < 1 {
		return false
	}
	profile := r.profile
	if r.source != "" {
		profile = "DSP_FAST"
	}
	for _, item := range array(separation["profiles"]) {
		v := obj(item)
		if str(v, "id") != profile || !boolv(v["ready"]) {
			continue
		}
		if r.profile != "AI_CLEAN" || r.source != "" || backend == "" || backend == "auto" {
			return true
		}
		for _, candidate := range array(separation["backends"]) {
			b := obj(candidate)
			if str(b, "state") == "AVAILABLE" && (str(b, "id") == backend || (node["cpuFallback"] != false && str(b, "id") == "cpu")) {
				return true
			}
		}
	}
	return false
}
func modelMaximum(model string) (int64, error) {
	switch model {
	case "panns-cnn6":
		return 32 << 20, nil
	case "htdemucs":
		return 100 << 20, nil
	case "htdemucs-openvino":
		return 128 << 20, nil
	case "uvr-mdxnet-9482":
		return 40 << 20, nil
	default:
		return 0, fail(400, "未知音频模型")
	}
}
