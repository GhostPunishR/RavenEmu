#pragma once

#include "interrupt/interrupt_controller.hpp"

namespace ravenemu::cgb {

class Ppu {
public:
    static constexpr int width = 160;
    static constexpr int height = 144;
    static constexpr int frame_pixels = width * height;
    static constexpr int mode_hblank = 0;
    static constexpr int mode_vblank = 1;
    static constexpr int mode_oam = 2;
    static constexpr int mode_transfer = 3;

    Ppu(InterruptController& interrupts, bool cgb_mode)
        : interrupts_(interrupts), cgb_mode_(cgb_mode) {}

    [[nodiscard]] bool lcd_enabled() const noexcept { return (lcdc_ & 0x80) != 0; }
    [[nodiscard]] int read_vram(int address) const noexcept {
        if (lcd_enabled() && mode_ == mode_transfer) return 0xff;
        return vram[static_cast<std::size_t>(vram_bank_ * 0x2000 + (address & 0x1fff))];
    }
    void write_vram(int address, int value) noexcept {
        if (lcd_enabled() && mode_ == mode_transfer) return;
        vram[static_cast<std::size_t>(vram_bank_ * 0x2000 + (address & 0x1fff))] = static_cast<std::uint8_t>(value);
    }
    void write_vram_bank(int value) noexcept { if (cgb_mode_) vram_bank_ = value & 1; }
    [[nodiscard]] int read_vram_bank() const noexcept { return cgb_mode_ ? vram_bank_ | 0xfe : 0xff; }
    [[nodiscard]] int vram_bank() const noexcept { return vram_bank_; }

    [[nodiscard]] int read_oam(int address) const noexcept {
        if (lcd_enabled() && (mode_ == mode_oam || mode_ == mode_transfer)) return 0xff;
        return oam[static_cast<std::size_t>(address & 0xff)];
    }
    void write_oam(int address, int value) noexcept {
        if (lcd_enabled() && (mode_ == mode_oam || mode_ == mode_transfer)) return;
        oam[static_cast<std::size_t>(address & 0xff)] = static_cast<std::uint8_t>(value);
    }
    void write_oam_direct(int index, int value) noexcept { oam[static_cast<std::size_t>(index)] = static_cast<std::uint8_t>(value); }

    void write_bcps(int value) noexcept { bcps_index_ = value & 0x3f; bcps_auto_ = (value & 0x80) != 0; }
    [[nodiscard]] int read_bcps() const noexcept { return bcps_index_ | (bcps_auto_ ? 0x80 : 0) | 0x40; }
    void write_bcpd(int value) noexcept {
        bg_cram[static_cast<std::size_t>(bcps_index_)] = static_cast<std::uint8_t>(value);
        recompute_argb(bg_cram, bg_argb_, bcps_index_);
        if (bcps_auto_) bcps_index_ = (bcps_index_ + 1) & 0x3f;
    }
    [[nodiscard]] int read_bcpd() const noexcept { return bg_cram[static_cast<std::size_t>(bcps_index_)]; }
    void write_ocps(int value) noexcept { ocps_index_ = value & 0x3f; ocps_auto_ = (value & 0x80) != 0; }
    [[nodiscard]] int read_ocps() const noexcept { return ocps_index_ | (ocps_auto_ ? 0x80 : 0) | 0x40; }
    void write_ocpd(int value) noexcept {
        obj_cram[static_cast<std::size_t>(ocps_index_)] = static_cast<std::uint8_t>(value);
        recompute_argb(obj_cram, obj_argb_, ocps_index_);
        if (ocps_auto_) ocps_index_ = (ocps_index_ + 1) & 0x3f;
    }
    [[nodiscard]] int read_ocpd() const noexcept { return obj_cram[static_cast<std::size_t>(ocps_index_)]; }

    void write_lcdc(int value) noexcept {
        const bool was_enabled = lcd_enabled();
        lcdc_ = byte(value);
        if (was_enabled && !lcd_enabled()) {
            ly_ = 0; line_dot_ = 0; mode_ = mode_hblank; window_line_ = 0;
            completed_frame.fill(0); frame_ready_ = true; stat_line_ = false;
        } else if (!was_enabled && lcd_enabled()) {
            ly_ = 0; line_dot_ = 0; window_line_ = 0; set_mode(mode_oam); check_stat();
        }
    }
    [[nodiscard]] int read_stat() const noexcept {
        return 0x80 | stat_enable_ | (ly_ == lyc_ ? 4 : 0) | (lcd_enabled() ? mode_ : 0);
    }
    void write_stat(int value) noexcept { stat_enable_ = value & 0x78; check_stat(); }
    void write_lyc(int value) noexcept { lyc_ = byte(value); check_stat(); }

    void tick(int cycles) {
        if (!lcd_enabled()) return;
        int remaining = cycles;
        while (remaining > 0) {
            const int step = std::min(remaining, next_event_delay());
            line_dot_ += step; remaining -= step; process_line_position();
        }
    }

    [[nodiscard]] int lcdc() const noexcept { return lcdc_; }
    [[nodiscard]] int scy() const noexcept { return scy_; }
    [[nodiscard]] int scx() const noexcept { return scx_; }
    [[nodiscard]] int ly() const noexcept { return ly_; }
    [[nodiscard]] int lyc() const noexcept { return lyc_; }
    [[nodiscard]] int bgp() const noexcept { return bgp_; }
    [[nodiscard]] int obp0() const noexcept { return obp0_; }
    [[nodiscard]] int obp1() const noexcept { return obp1_; }
    [[nodiscard]] int wy() const noexcept { return wy_; }
    [[nodiscard]] int wx() const noexcept { return wx_; }
    void set_scy(int v) noexcept { scy_ = byte(v); } void set_scx(int v) noexcept { scx_ = byte(v); }
    void set_bgp(int v) noexcept { bgp_ = byte(v); } void set_obp0(int v) noexcept { obp0_ = byte(v); }
    void set_obp1(int v) noexcept { obp1_ = byte(v); } void set_wy(int v) noexcept { wy_ = byte(v); }
    void set_wx(int v) noexcept { wx_ = byte(v); }
    [[nodiscard]] bool take_hblank_entry() noexcept { const bool value = entered_hblank_; entered_hblank_ = false; return value; }

    void save(BinaryWriter& out) const {
        constexpr int field_count = 20;
        out.i32(field_count);
        const std::array fields{
            lcdc_, stat_enable_, scy_, scx_, ly_, lyc_, bgp_, obp0_, obp1_, wy_, wx_,
            mode_, line_dot_, window_line_, stat_line_ ? 1 : 0, vram_bank_, bcps_index_,
            bcps_auto_ ? 1 : 0, ocps_index_, ocps_auto_ ? 1 : 0,
        };
        for (const int field : fields) out.i32(field);
        out.raw(vram); out.raw(oam); out.raw(bg_cram); out.raw(obj_cram);
    }
    void load(BinaryReader& in) {
        if (in.i32() != 20) throw SaveStateError("État instantané corrompu (PPU)");
        lcdc_ = in.i32(); stat_enable_ = in.i32(); scy_ = in.i32(); scx_ = in.i32();
        ly_ = in.i32(); lyc_ = in.i32(); bgp_ = in.i32(); obp0_ = in.i32(); obp1_ = in.i32();
        wy_ = in.i32(); wx_ = in.i32(); mode_ = in.i32(); line_dot_ = in.i32();
        window_line_ = in.i32(); stat_line_ = in.i32() != 0; vram_bank_ = in.i32();
        bcps_index_ = in.i32(); bcps_auto_ = in.i32() != 0; ocps_index_ = in.i32();
        ocps_auto_ = in.i32() != 0;
        in.raw(vram); in.raw(oam); in.raw(bg_cram); in.raw(obj_cram); rebuild_color_cache();
    }

    std::array<std::uint8_t, 0x4000> vram{};
    std::array<std::uint8_t, 0xa0> oam{};
    std::array<std::uint8_t, 64> bg_cram{};
    std::array<std::uint8_t, 64> obj_cram{};
    std::array<std::int32_t, frame_pixels> completed_frame{};

private:
    [[nodiscard]] int next_event_delay() const noexcept {
        if (ly_ >= height) return 456 - line_dot_;
        if (line_dot_ < 80) return 80 - line_dot_;
        if (line_dot_ < 252) return 252 - line_dot_;
        return 456 - line_dot_;
    }
    void process_line_position() {
        if (line_dot_ >= 456) {
            line_dot_ -= 456; ++ly_;
            if (ly_ == height) enter_vblank();
            else if (ly_ > 153) { ly_ = 0; window_line_ = 0; set_mode(mode_oam); }
            else if (ly_ < height) set_mode(mode_oam);
            check_stat(); return;
        }
        if (ly_ < height) {
            const int expected = line_dot_ < 80 ? mode_oam : (line_dot_ < 252 ? mode_transfer : mode_hblank);
            if (expected != mode_) {
                set_mode(expected);
                if (expected == mode_transfer) render_line();
                else if (expected == mode_hblank) entered_hblank_ = true;
            }
        }
    }
    void enter_vblank() {
        set_mode(mode_vblank); interrupts_.request(Interrupt::vblank);
        completed_frame = working_frame_; frame_ready_ = true;
    }
    void set_mode(int mode) noexcept { mode_ = mode; check_stat(); }
    void check_stat() noexcept {
        if (!lcd_enabled()) { stat_line_ = false; return; }
        const bool line = ((stat_enable_ & 8) != 0 && mode_ == mode_hblank) ||
            ((stat_enable_ & 0x10) != 0 && mode_ == mode_vblank) ||
            ((stat_enable_ & 0x20) != 0 && mode_ == mode_oam) ||
            ((stat_enable_ & 0x40) != 0 && ly_ == lyc_);
        if (line && !stat_line_) interrupts_.request(Interrupt::stat);
        stat_line_ = line;
    }

    [[nodiscard]] int vram_byte(int bank, int address) const noexcept {
        return vram[static_cast<std::size_t>(bank * 0x2000 + (address & 0x1fff))];
    }
    [[nodiscard]] int tile_pixel_dmg(int tile, int row, int column) const noexcept {
        const int address = (lcdc_ & 0x10) != 0 ? tile * 16 : 0x1000 + static_cast<std::int8_t>(tile) * 16;
        const int lo = vram_byte(0, address + row * 2); const int hi = vram_byte(0, address + row * 2 + 1);
        const int bit_index = 7 - column;
        return (((hi >> bit_index) & 1) << 1) | ((lo >> bit_index) & 1);
    }
    void render_line() {
        const int base = ly_ * width;
        if (cgb_mode_) { render_background_cgb(base); render_sprites_cgb(base); }
        else { render_background_dmg(base); render_sprites_dmg(base); }
    }
    void render_background_dmg(int base) {
        const bool enabled = (lcdc_ & 1) != 0;
        if (!enabled) {
            for (int x = 0; x < width; ++x) { bg_color_line_[static_cast<std::size_t>(x)] = 0; working_frame_[static_cast<std::size_t>(base + x)] = 0; }
        } else {
            const int map = (lcdc_ & 8) != 0 ? 0x1c00 : 0x1800;
            const int y = (ly_ + scy_) & 0xff;
            for (int x = 0; x < width; ++x) {
                const int bg_x = (x + scx_) & 0xff;
                const int tile = vram_byte(0, map + (y >> 3) * 32 + (bg_x >> 3));
                const int color = tile_pixel_dmg(tile, y & 7, bg_x & 7);
                bg_color_line_[static_cast<std::size_t>(x)] = color;
                working_frame_[static_cast<std::size_t>(base + x)] = (bgp_ >> (color * 2)) & 3;
            }
        }
        if (enabled && (lcdc_ & 0x20) != 0 && ly_ >= wy_ && wx_ <= 166) {
            const int start = std::max(0, wx_ - 7);
            if (start < width) {
                const int map = (lcdc_ & 0x40) != 0 ? 0x1c00 : 0x1800;
                for (int x = start; x < width; ++x) {
                    const int win_x = x - (wx_ - 7);
                    const int tile = vram_byte(0, map + (window_line_ >> 3) * 32 + (win_x >> 3));
                    const int color = tile_pixel_dmg(tile, window_line_ & 7, win_x & 7);
                    bg_color_line_[static_cast<std::size_t>(x)] = color;
                    working_frame_[static_cast<std::size_t>(base + x)] = (bgp_ >> (color * 2)) & 3;
                }
                ++window_line_;
            }
        }
    }
    int scan_sprites(int sprite_height) noexcept {
        int count{};
        for (int i = 0; i < 40 && count < 10; ++i) {
            const int y = oam[static_cast<std::size_t>(i * 4)] - 16;
            if (ly_ >= y && ly_ < y + sprite_height) sprite_indices_[static_cast<std::size_t>(count++)] = i;
        }
        return count;
    }
    template <typename Priority>
    void sort_sprites(int count, Priority priority) noexcept {
        for (int a = 1; a < count; ++a) {
            const int key = sprite_indices_[static_cast<std::size_t>(a)]; int b = a - 1;
            while (b >= 0 && !priority(sprite_indices_[static_cast<std::size_t>(b)], key)) {
                sprite_indices_[static_cast<std::size_t>(b + 1)] = sprite_indices_[static_cast<std::size_t>(b)]; --b;
            }
            sprite_indices_[static_cast<std::size_t>(b + 1)] = key;
        }
    }
    void render_sprites_dmg(int base) {
        if ((lcdc_ & 2) == 0) return;
        const int sprite_height = (lcdc_ & 4) != 0 ? 16 : 8; const int count = scan_sprites(sprite_height);
        sort_sprites(count, [&](int a, int b) {
            const int ax = oam[static_cast<std::size_t>(a * 4 + 1)]; const int bx = oam[static_cast<std::size_t>(b * 4 + 1)];
            return ax != bx ? ax > bx : a > b;
        });
        for (int s = 0; s < count; ++s) {
            const int index = sprite_indices_[static_cast<std::size_t>(s)] * 4;
            const int sy = oam[static_cast<std::size_t>(index)] - 16; const int sx = oam[static_cast<std::size_t>(index + 1)] - 8;
            int tile = oam[static_cast<std::size_t>(index + 2)]; const int attr = oam[static_cast<std::size_t>(index + 3)];
            if (sprite_height == 16) tile &= 0xfe;
            int row = ly_ - sy; if ((attr & 0x40) != 0) row = sprite_height - 1 - row;
            const int lo = vram_byte(0, tile * 16 + row * 2); const int hi = vram_byte(0, tile * 16 + row * 2 + 1);
            const int palette = (attr & 0x10) != 0 ? obp1_ : obp0_;
            for (int px = 0; px < 8; ++px) {
                const int x = sx + px; if (x < 0 || x >= width) continue;
                const int bit_index = (attr & 0x20) != 0 ? px : 7 - px;
                const int color = (((hi >> bit_index) & 1) << 1) | ((lo >> bit_index) & 1);
                if (color == 0 || ((attr & 0x80) != 0 && bg_color_line_[static_cast<std::size_t>(x)] != 0)) continue;
                working_frame_[static_cast<std::size_t>(base + x)] = (palette >> (color * 2)) & 3;
            }
        }
    }
    void draw_bg_pixel_cgb(int base, int x, int map, int tile_row, int pixel_row, int tile_col, int pixel_col) {
        const int map_address = map + tile_row * 32 + tile_col;
        const int tile = vram_byte(0, map_address); const int attr = vram_byte(1, map_address);
        const int palette = attr & 7; const int bank = (attr >> 3) & 1;
        const int address = (lcdc_ & 0x10) != 0 ? tile * 16 : 0x1000 + static_cast<std::int8_t>(tile) * 16;
        const int row = (attr & 0x40) != 0 ? 7 - pixel_row : pixel_row;
        const int lo = vram_byte(bank, address + row * 2); const int hi = vram_byte(bank, address + row * 2 + 1);
        const int bit_index = (attr & 0x20) != 0 ? pixel_col : 7 - pixel_col;
        const int color = (((hi >> bit_index) & 1) << 1) | ((lo >> bit_index) & 1);
        bg_color_line_[static_cast<std::size_t>(x)] = color; bg_priority_line_[static_cast<std::size_t>(x)] = (attr & 0x80) != 0;
        working_frame_[static_cast<std::size_t>(base + x)] = bg_argb_[static_cast<std::size_t>(palette * 4 + color)];
    }
    void render_background_cgb(int base) {
        const int map = (lcdc_ & 8) != 0 ? 0x1c00 : 0x1800; const int y = (ly_ + scy_) & 0xff;
        for (int x = 0; x < width; ++x) {
            const int bg_x = (x + scx_) & 0xff;
            draw_bg_pixel_cgb(base, x, map, y >> 3, y & 7, bg_x >> 3, bg_x & 7);
        }
        if ((lcdc_ & 0x20) != 0 && ly_ >= wy_ && wx_ <= 166) {
            const int start = std::max(0, wx_ - 7);
            if (start < width) {
                const int win_map = (lcdc_ & 0x40) != 0 ? 0x1c00 : 0x1800;
                for (int x = start; x < width; ++x) {
                    const int win_x = x - (wx_ - 7);
                    draw_bg_pixel_cgb(base, x, win_map, window_line_ >> 3, window_line_ & 7, win_x >> 3, win_x & 7);
                }
                ++window_line_;
            }
        }
    }
    void render_sprites_cgb(int base) {
        if ((lcdc_ & 2) == 0) return;
        const int sprite_height = (lcdc_ & 4) != 0 ? 16 : 8; const int count = scan_sprites(sprite_height);
        sort_sprites(count, [](int a, int b) { return a > b; });
        const bool master_priority = (lcdc_ & 1) != 0;
        for (int s = 0; s < count; ++s) {
            const int index = sprite_indices_[static_cast<std::size_t>(s)] * 4;
            const int sy = oam[static_cast<std::size_t>(index)] - 16; const int sx = oam[static_cast<std::size_t>(index + 1)] - 8;
            int tile = oam[static_cast<std::size_t>(index + 2)]; const int attr = oam[static_cast<std::size_t>(index + 3)];
            if (sprite_height == 16) tile &= 0xfe;
            int row = ly_ - sy; if ((attr & 0x40) != 0) row = sprite_height - 1 - row;
            const int bank = (attr >> 3) & 1; const int palette = attr & 7;
            const int lo = vram_byte(bank, tile * 16 + row * 2); const int hi = vram_byte(bank, tile * 16 + row * 2 + 1);
            for (int px = 0; px < 8; ++px) {
                const int x = sx + px; if (x < 0 || x >= width) continue;
                const int bit_index = (attr & 0x20) != 0 ? px : 7 - px;
                const int color = (((hi >> bit_index) & 1) << 1) | ((lo >> bit_index) & 1);
                if (color == 0) continue;
                const bool bg_visible = bg_color_line_[static_cast<std::size_t>(x)] != 0;
                if (bg_visible && master_priority && (bg_priority_line_[static_cast<std::size_t>(x)] || (attr & 0x80) != 0)) continue;
                working_frame_[static_cast<std::size_t>(base + x)] = obj_argb_[static_cast<std::size_t>(palette * 4 + color)];
            }
        }
    }
    static std::int32_t bgr555_to_argb(int value) noexcept {
        const int r5 = value & 0x1f; const int g5 = (value >> 5) & 0x1f; const int b5 = (value >> 10) & 0x1f;
        const auto argb = 0xff000000U | static_cast<std::uint32_t>(((r5 << 3) | (r5 >> 2)) << 16) |
            static_cast<std::uint32_t>(((g5 << 3) | (g5 >> 2)) << 8) | static_cast<std::uint32_t>((b5 << 3) | (b5 >> 2));
        return static_cast<std::int32_t>(argb);
    }
    static void recompute_argb(const std::array<std::uint8_t, 64>& cram,
                               std::array<std::int32_t, 32>& colors, int byte_index) noexcept {
        const int color = byte_index / 2;
        colors[static_cast<std::size_t>(color)] = bgr555_to_argb(
            cram[static_cast<std::size_t>(color * 2)] | (cram[static_cast<std::size_t>(color * 2 + 1)] << 8)
        );
    }
    void rebuild_color_cache() noexcept {
        for (int i = 0; i < 32; ++i) { recompute_argb(bg_cram, bg_argb_, i * 2); recompute_argb(obj_cram, obj_argb_, i * 2); }
    }

    InterruptController& interrupts_;
    bool cgb_mode_{};
    int lcdc_{0x91};
    int stat_enable_{};
    int scy_{};
    int scx_{};
    int ly_{};
    int lyc_{};
    int bgp_{0xfc};
    int obp0_{};
    int obp1_{};
    int wy_{};
    int wx_{};
    int vram_bank_{};
    int bcps_index_{};
    bool bcps_auto_{};
    int ocps_index_{};
    bool ocps_auto_{};
    std::array<std::int32_t, 32> bg_argb_{};
    std::array<std::int32_t, 32> obj_argb_{};
    int mode_{mode_oam};
    int line_dot_{};
    int window_line_{};
    bool stat_line_{};
    bool entered_hblank_{};
    bool frame_ready_{};
    std::array<std::int32_t, frame_pixels> working_frame_{};
    std::array<int, width> bg_color_line_{};
    std::array<bool, width> bg_priority_line_{};
    std::array<int, 10> sprite_indices_{};
};

} // namespace ravenemu::cgb
