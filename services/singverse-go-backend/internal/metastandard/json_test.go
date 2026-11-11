package metastandard

import (
	"math"
	"strconv"
	"testing"
)

// RFC 8785 Appendix B exercises subnormal numbers and rounding boundaries, not
// just ordinary decimal spellings. Hex values denote the exact binary64 input.
func TestJCSAppendixBNumbers(t *testing.T) {
	vectors := []struct {
		bits uint64
		want string
	}{
		{0x0000000000000000, "0"}, {0x8000000000000000, "0"}, {0x0000000000000001, "5e-324"}, {0x8000000000000001, "-5e-324"},
		{0x7fefffffffffffff, "1.7976931348623157e+308"}, {0xffefffffffffffff, "-1.7976931348623157e+308"},
		{0x4340000000000000, "9007199254740992"}, {0xc340000000000000, "-9007199254740992"}, {0x4430000000000000, "295147905179352830000"},
		{0x44b52d02c7e14af5, "9.999999999999997e+22"}, {0x44b52d02c7e14af6, "1e+23"}, {0x44b52d02c7e14af7, "1.0000000000000001e+23"},
		{0x444b1ae4d6e2ef4e, "999999999999999700000"}, {0x444b1ae4d6e2ef4f, "999999999999999900000"}, {0x444b1ae4d6e2ef50, "1e+21"},
		{0x3eb0c6f7a0b5ed8c, "9.999999999999997e-7"}, {0x3eb0c6f7a0b5ed8d, "0.000001"},
		{0x41b3de4355555553, "333333333.3333332"}, {0x41b3de4355555554, "333333333.33333325"}, {0x41b3de4355555555, "333333333.3333333"},
		{0x41b3de4355555556, "333333333.3333334"}, {0x41b3de4355555557, "333333333.33333343"}, {0xbecbf647612f3696, "-0.0000033333333333333333"},
	}
	for _, v := range vectors {
		input := strconv.FormatFloat(math.Float64frombits(v.bits), 'e', -1, 64)
		got, e := Canonical([]byte(input))
		if e != nil || string(got) != v.want {
			t.Fatalf("%x: %s want %s: %v", v.bits, got, v.want, e)
		}
	}
}
