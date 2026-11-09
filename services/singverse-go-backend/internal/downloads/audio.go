package downloads

import (
	"context"
	"encoding/binary"
	"io"
	"os"
)

type audioReader struct {
	*os.File
	size, pos int64
	err       error
	ctx       context.Context
}

func (r *audioReader) read(n int) []byte {
	if r.err != nil {
		return make([]byte, n)
	}
	if r.ctx.Err() != nil {
		r.err = r.ctx.Err()
		return make([]byte, n)
	}
	b := make([]byte, n)
	_, r.err = io.ReadFull(r.File, b)
	r.pos += int64(n)
	return b
}
func (r *audioReader) require(v bool) {
	if !v && r.err == nil {
		r.err = errIncompleteAudio
	}
}
func (r *audioReader) seek(pos int64) {
	if r.err != nil {
		return
	}
	if pos < 0 || pos > r.size {
		r.err = errIncompleteAudio
		return
	}
	_, r.err = r.File.Seek(pos, 0)
	r.pos = pos
}
func (r *audioReader) u8() byte     { return r.read(1)[0] }
func (r *audioReader) le32() uint32 { return binary.LittleEndian.Uint32(r.read(4)) }
func (r *audioReader) be32() uint32 { return binary.BigEndian.Uint32(r.read(4)) }
func validateAudio(ctx context.Context, path, extension string) error {
	f, e := os.Open(path)
	if e != nil {
		return e
	}
	defer f.Close()
	info, e := f.Stat()
	if e != nil {
		return e
	}
	r := &audioReader{File: f, size: info.Size(), ctx: ctx}
	switch extension {
	case "wav":
		r.wav()
	case "m4a":
		r.mp4()
	case "ogg":
		r.ogg()
	case "flac":
		r.flac()
	case "mp3":
		r.frames(false)
	case "aac":
		r.frames(true)
	default:
		return errIncompleteAudio
	}
	return r.err
}
func (r *audioReader) wav() {
	r.require(string(r.read(4)) == "RIFF")
	end := int64(r.le32()) + 8
	r.require(string(r.read(4)) == "WAVE" && end == r.size)
	format, audio := false, false
	for r.err == nil && r.pos < end {
		r.require(end-r.pos >= 8)
		kind := string(r.read(4))
		size := int64(r.le32())
		start := r.pos
		next := start + size + size&1
		r.require(next <= end)
		if kind == "fmt " {
			r.require(size >= 16)
			encoding := binary.LittleEndian.Uint16(r.read(2))
			channels := binary.LittleEndian.Uint16(r.read(2))
			rate := r.le32()
			r.require(encoding > 0 && channels > 0 && channels <= 64 && rate > 0 && rate <= 768000)
			format = true
		}
		if kind == "data" {
			r.require(size > 0)
			audio = true
		}
		r.seek(next)
	}
	r.require(format && audio)
}
func (r *audioReader) mp4() {
	kind, metadata, audio := false, false, false
	for r.err == nil && r.pos < r.size {
		start := r.pos
		r.require(r.size-start >= 8)
		size := uint64(r.be32())
		box := string(r.read(4))
		header := uint64(8)
		if size == 1 {
			size = binary.BigEndian.Uint64(r.read(8))
			header = 16
		}
		if size == 0 {
			size = uint64(r.size - start)
		}
		r.require(size >= header && size <= uint64(r.size-start))
		switch box {
		case "ftyp":
			r.require(size >= 16)
			kind = true
		case "moov":
			r.require(size > header)
			metadata = true
		case "mdat":
			audio = audio || size > header
		}
		if r.err == nil {
			r.seek(start + int64(size))
		}
	}
	r.require(kind && metadata && audio)
}
func (r *audioReader) ogg() {
	audio, eos := false, false
	for r.err == nil && r.pos < r.size {
		r.require(r.size-r.pos >= 27 && string(r.read(4)) == "OggS" && r.u8() == 0)
		flags := r.u8()
		r.read(20)
		count := int(r.u8())
		r.require(count > 0 && r.pos+int64(count) <= r.size)
		size := 0
		for range count {
			size += int(r.u8())
		}
		r.require(r.pos+int64(size) <= r.size)
		audio = audio || size > 0
		eos = flags&4 != 0
		r.seek(r.pos + int64(size))
	}
	r.require(audio && eos)
}
func (r *audioReader) flac() {
	r.require(string(r.read(4)) == "fLaC")
	last, stream := false, false
	for blocks := 0; !last && r.err == nil; blocks++ {
		r.require(blocks < 4096 && r.size-r.pos >= 4)
		header := r.u8()
		last = header&128 != 0
		kind := header & 127
		size := int64(r.u8())<<16 | int64(r.u8())<<8 | int64(r.u8())
		next := r.pos + size
		r.require(next <= r.size)
		if blocks == 0 {
			r.require(kind == 0 && size == 34)
			r.read(10)
			info := binary.BigEndian.Uint64(r.read(8))
			r.require(info>>44 > 0 && info&0xfffffffff > 0)
			stream = true
		}
		r.seek(next)
	}
	r.require(stream && r.size-r.pos >= 8)
	start := r.pos
	r.require(r.u8() == 255 && r.u8()&0xfe == 0xf8)
	r.seek(start)
	var crc uint16
	for r.err == nil && r.pos < r.size {
		chunk := r.read(int(min(int64(65536), r.size-r.pos)))
		for _, b := range chunk {
			crc ^= uint16(b) << 8
			for range 8 {
				if crc&0x8000 != 0 {
					crc = crc<<1 ^ 0x8005
				} else {
					crc <<= 1
				}
			}
		}
	}
	r.require(crc == 0)
}
func (r *audioReader) frames(aac bool) {
	end := r.size
	r.require(end >= 7)
	marker := string(r.read(3))
	r.seek(0)
	if marker == "ID3" {
		r.require(end >= 10)
		r.read(3)
		version := r.u8()
		r.u8()
		flags := r.u8()
		size := int64(0)
		for range 4 {
			part := r.u8()
			r.require(part < 128)
			size = size<<7 | int64(part)
		}
		next := 10 + size
		if version == 4 && flags&16 != 0 {
			next += 10
		}
		r.require(next < end)
		r.seek(next)
	}
	frames := 0
	for r.err == nil && r.pos < end {
		start := r.pos
		left := end - start
		if !aac && left == 128 && string(r.read(3)) == "TAG" {
			r.seek(end)
			break
		}
		r.seek(start)
		if !aac && left >= 32 && string(r.read(8)) == "APETAGEX" {
			r.read(4)
			size := int64(r.le32())
			r.require(size >= 32 && (size == left || size+32 == left))
			r.seek(end)
			break
		}
		r.seek(start)
		r.require(left >= 7)
		a, b, c, d := r.u8(), r.u8(), r.u8(), r.u8()
		r.require(a == 255)
		size := int64(0)
		if aac {
			r.require(b&0xf6 == 0xf0 && (c>>2)&15 < 13)
			e, g := r.u8(), r.u8()
			r.u8()
			size = int64(d&3)<<11 | int64(e)<<3 | int64(g>>5)
			header := int64(9)
			if b&1 == 1 {
				header = 7
			}
			r.require(size >= header)
		} else {
			version, layer, rateIndex, bitrateIndex := int(b>>3&3), int(b>>1&3), int(c>>2&3), int(c>>4)
			r.require(b&0xe0 == 0xe0 && version != 1 && layer != 0 && rateIndex < 3 && bitrateIndex > 0 && bitrateIndex < 15)
			if r.err != nil {
				break
			}
			rates := [3]int{44100, 48000, 32000}
			rate := rates[rateIndex]
			if version == 2 {
				rate /= 2
			} else if version != 3 {
				rate /= 4
			}
			padding := int(c >> 1 & 1)
			mpeg1 := [3][15]int{{0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320}, {0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384}, {0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448}}
			low := [15]int{0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160}
			low1 := [15]int{0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256}
			bitrate := low[bitrateIndex]
			if version == 3 {
				bitrate = mpeg1[layer-1][bitrateIndex]
			} else if layer == 3 {
				bitrate = low1[bitrateIndex]
			}
			if layer == 3 {
				size = int64((12*bitrate*1000/rate + padding) * 4)
			} else {
				factor := 144
				if layer == 1 && version != 3 {
					factor = 72
				}
				size = int64(factor*bitrate*1000/rate + padding)
			}
		}
		r.require(size >= 7 && size <= left)
		r.seek(start + size)
		frames++
	}
	r.require(frames > 0)
}
