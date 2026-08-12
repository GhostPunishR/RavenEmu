#pragma once

#include "interrupt/interrupt_controller.hpp"
#include <ravenemu/gb/hardware_mode.hpp>

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

    Ppu(InterruptController& interrupts, gb::HardwareMode hardware_mode)
        : interrupts_(interrupts), hardware_mode_(hardware_mode),
          opri_dmg_priority_(hardware_mode != gb::HardwareMode::cgb_native) {
        rebuild_color_cache();
    }
    Ppu(InterruptController& interrupts, bool cgb_mode)
        : Ppu(interrupts, cgb_mode ? gb::HardwareMode::cgb_native : gb::HardwareMode::dmg) {}

    void set_hardware_mode(gb::HardwareMode mode) noexcept {
        hardware_mode_ = mode;
        if (!gb::cgb_features_enabled(mode)) vram_bank_ = 0;
    }

    [[nodiscard]] bool lcd_enabled() const noexcept { return (lcdc_ & 0x80) != 0; }
    [[nodiscard]] int read_vram(int address) const noexcept {
        const bool first_line_opening = lcd_startup_line_ && mode_ == mode_transfer &&
            reported_mode_ == mode_hblank;
        if (lcd_enabled() &&
            (reported_mode_ == mode_transfer || (mode_ == mode_transfer && !first_line_opening))) {
            return 0xff;
        }
        return vram[static_cast<std::size_t>(vram_bank_ * 0x2000 + (address & 0x1fff))];
    }
    void write_vram(int address, int value) noexcept {
        if (lcd_enabled() && reported_mode_ == mode_transfer) return;
        vram[static_cast<std::size_t>(vram_bank_ * 0x2000 + (address & 0x1fff))] = static_cast<std::uint8_t>(value);
    }
    void write_vram_bank(int value) noexcept { if (gb::cgb_features_enabled(hardware_mode_)) vram_bank_ = value & 1; }
    [[nodiscard]] int read_vram_bank() const noexcept {
        return gb::is_cgb_hardware(hardware_mode_) ? vram_bank_ | 0xfe : 0xff;
    }
    [[nodiscard]] int vram_bank() const noexcept { return vram_bank_; }

    [[nodiscard]] int read_oam(int address) const noexcept {
        // Les phases de lecture et d'écriture OAM ne voient pas exactement
        // le même front lors des transitions DMG. Le scan interne bloque les
        // lectures dès son départ, tandis que le blocage mode 3 suit les bits
        // de mode exposés au CPU.
        if (lcd_enabled() &&
            (mode_ == mode_oam || reported_mode_ == mode_oam || reported_mode_ == mode_transfer)) {
            return 0xff;
        }
        return oam[static_cast<std::size_t>(address & 0xff)];
    }
    void write_oam(int address, int value) noexcept {
        const bool blocked = reported_mode_ == mode_transfer ||
            (reported_mode_ == mode_oam && mode_ == mode_oam);
        if (lcd_enabled() && blocked) return;
        oam[static_cast<std::size_t>(address & 0xff)] = static_cast<std::uint8_t>(value);
    }
    void write_oam_direct(int index, int value) noexcept { oam[static_cast<std::size_t>(index)] = static_cast<std::uint8_t>(value); }

    void write_bcps(int value) noexcept { bcps_index_ = value & 0x3f; bcps_auto_ = (value & 0x80) != 0; }
    [[nodiscard]] int read_bcps() const noexcept { return bcps_index_ | (bcps_auto_ ? 0x80 : 0) | 0x40; }
    void write_bcpd(int value) noexcept {
        if (lcd_enabled() && reported_mode_ == mode_transfer) return;
        bg_cram[static_cast<std::size_t>(bcps_index_)] = static_cast<std::uint8_t>(value);
        recompute_argb(bg_cram, bg_argb_, bcps_index_);
        if (bcps_auto_) bcps_index_ = (bcps_index_ + 1) & 0x3f;
    }
    [[nodiscard]] int read_bcpd() const noexcept {
        if (lcd_enabled() && reported_mode_ == mode_transfer) return 0xff;
        return bg_cram[static_cast<std::size_t>(bcps_index_)];
    }
    void write_ocps(int value) noexcept { ocps_index_ = value & 0x3f; ocps_auto_ = (value & 0x80) != 0; }
    [[nodiscard]] int read_ocps() const noexcept { return ocps_index_ | (ocps_auto_ ? 0x80 : 0) | 0x40; }
    void write_ocpd(int value) noexcept {
        if (lcd_enabled() && reported_mode_ == mode_transfer) return;
        obj_cram[static_cast<std::size_t>(ocps_index_)] = static_cast<std::uint8_t>(value);
        recompute_argb(obj_cram, obj_argb_, ocps_index_);
        if (ocps_auto_) ocps_index_ = (ocps_index_ + 1) & 0x3f;
    }
    [[nodiscard]] int read_ocpd() const noexcept {
        if (lcd_enabled() && reported_mode_ == mode_transfer) return 0xff;
        return obj_cram[static_cast<std::size_t>(ocps_index_)];
    }

    [[nodiscard]] int read_opri() const noexcept {
        return gb::cgb_features_enabled(hardware_mode_) ? 0xfe | (opri_dmg_priority_ ? 1 : 0) : 0xff;
    }
    void write_opri(int value) noexcept {
        if (gb::cgb_features_enabled(hardware_mode_)) opri_dmg_priority_ = (value & 1) != 0;
    }

    /** État HLE déterministe lorsque le firmware CGB n'est pas exécuté. */
    void initialize_hle_post_boot() noexcept {
        if (!gb::is_cgb_hardware(hardware_mode_)) return;
        bcps_index_ = 0x08;
        bcps_auto_ = true;
        ocps_index_ = 0x10;
        ocps_auto_ = true;
    }

    /** Palettes HLE déterministes du mode de compatibilité sans firmware. */
    void initialize_hle_compatibility_palettes() noexcept {
        if (hardware_mode_ != gb::HardwareMode::cgb_compatibility) return;
        constexpr std::array<int, 4> grayscale{0x7fff, 0x56b5, 0x294a, 0x0000};
        for (int palette = 0; palette < 2; ++palette) {
            for (int color = 0; color < 4; ++color) {
                const int value = grayscale[static_cast<std::size_t>(color)];
                const int bg_index = color * 2;
                bg_cram[static_cast<std::size_t>(bg_index)] = static_cast<std::uint8_t>(value);
                bg_cram[static_cast<std::size_t>(bg_index + 1)] = static_cast<std::uint8_t>(value >> 8);
                const int obj_index = (palette * 4 + color) * 2;
                obj_cram[static_cast<std::size_t>(obj_index)] = static_cast<std::uint8_t>(value);
                obj_cram[static_cast<std::size_t>(obj_index + 1)] = static_cast<std::uint8_t>(value >> 8);
            }
        }
        rebuild_color_cache();
    }

    void reset_for_boot_rom() noexcept {
        lcdc_ = 0;
        stat_enable_ = 0;
        scy_ = 0; scx_ = 0; ly_ = 0; lyc_ = 0;
        bgp_ = 0; obp0_ = 0; obp1_ = 0; wy_ = 0; wx_ = 0;
        mode_ = mode_hblank; reported_mode_ = mode_hblank; reported_mode_target_ = mode_hblank;
        reported_mode_delay_ = 0; lcd_startup_line_ = false; lcd_startup_lines_remaining_ = 0;
        lyc_compare_delay_ = 0; frame_mode2_delay_ = false;
        line_dot_ = 0; window_line_ = 0; transfer_x_ = 0;
        lyc_equal_ = true;
        window_started_line_ = false; window_y_condition_ = false;
        oam_scan_index_ = 0; line_sprite_count_ = 0;
        stat_line_ = false; entered_hblank_ = false; frame_ready_ = false;
        completed_frame.fill(0); working_frame_.fill(0);
    }

    void write_lcdc(int value) noexcept {
        const bool was_enabled = lcd_enabled();
        const bool window_was_enabled = (lcdc_ & 0x20) != 0;
        lcdc_ = byte(value);
        if (gb::is_cgb_hardware(hardware_mode_) && window_was_enabled && (lcdc_ & 0x20) == 0) {
            window_y_condition_ = false;
        }
        if (was_enabled && !lcd_enabled()) {
            ly_ = 0; line_dot_ = 0; mode_ = mode_hblank; reported_mode_ = mode_hblank;
            reported_mode_target_ = mode_hblank; reported_mode_delay_ = 0; lcd_startup_line_ = false;
            lcd_startup_lines_remaining_ = 0; frame_mode2_delay_ = false;
            window_line_ = 0; window_y_condition_ = false;
            completed_frame.fill(0); frame_ready_ = true;
            stat_line_ = (stat_enable_ & 0x40) != 0 && lyc_equal_;
        } else if (!was_enabled && lcd_enabled()) {
            ly_ = 0; line_dot_ = 0; window_line_ = 0; window_y_condition_ = wy_ == 0;
            lyc_compare_delay_ = 0;
            update_lyc_compare();
            // Toutes les révisions commencent par un mode 0 partiel et
            // sautent le scan OAM de la première ligne. Les particularités
            // de publication des trois premiers modes restent propres au DMG.
            lcd_startup_line_ = true;
            lcd_startup_lines_remaining_ = hardware_mode_ == gb::HardwareMode::dmg ? 3 : 1;
            mode_ = mode_hblank;
            reported_mode_ = mode_hblank;
            reported_mode_target_ = mode_hblank;
            reported_mode_delay_ = 0;
            check_stat();
        }
    }
    [[nodiscard]] int read_stat() const noexcept {
        return 0x80 | stat_enable_ | (lyc_equal_ ? 4 : 0) |
            (lcd_enabled() ? reported_mode_ : 0);
    }
    void write_stat(int value) noexcept {
        // Sur DMG, le bus interne présente brièvement tous les bits de source
        // à 1 lors d'une écriture STAT. Cela peut créer un front d'IRQ en
        // modes 0/1/2 (ou avec LY=LYC), mais pas sur CGB.
        if (hardware_mode_ == gb::HardwareMode::dmg && lcd_enabled() && !stat_line_ &&
            (mode_ != mode_transfer || lyc_equal_)) {
            interrupts_.request(Interrupt::stat);
        }
        stat_enable_ = value & 0x78;
        check_stat();
    }
    void write_lyc(int value) noexcept {
        lyc_ = byte(value);
        if (lcd_enabled()) {
            lyc_compare_delay_ = 0;
            update_lyc_compare();
        }
        check_stat();
    }

    void tick(int cycles) {
        if (!lcd_enabled() || cycles <= 0) return;
        for (int dot = 0; dot < cycles; ++dot) advance_dot();
    }

    [[nodiscard]] int lcdc() const noexcept { return lcdc_; }
    [[nodiscard]] int scy() const noexcept { return scy_; }
    [[nodiscard]] int scx() const noexcept { return scx_; }
    [[nodiscard]] int ly() const noexcept { return visible_ly(); }
    [[nodiscard]] int lyc() const noexcept { return lyc_; }
    [[nodiscard]] int bgp() const noexcept { return bgp_; }
    [[nodiscard]] int obp0() const noexcept { return obp0_; }
    [[nodiscard]] int obp1() const noexcept { return obp1_; }
    [[nodiscard]] int wy() const noexcept { return wy_; }
    [[nodiscard]] int wx() const noexcept { return wx_; }
    [[nodiscard]] int mode() const noexcept { return lcd_enabled() ? reported_mode_ : mode_hblank; }
    [[nodiscard]] int line_dot() const noexcept { return line_dot_; }
    [[nodiscard]] int transfer_x() const noexcept { return transfer_x_; }
    [[nodiscard]] int window_line() const noexcept { return window_line_; }
    void set_scy(int v) noexcept { scy_ = byte(v); } void set_scx(int v) noexcept { scx_ = byte(v); }
    void set_bgp(int v) noexcept { bgp_ = byte(v); } void set_obp0(int v) noexcept { obp0_ = byte(v); }
    void set_obp1(int v) noexcept { obp1_ = byte(v); } void set_wy(int v) noexcept { wy_ = byte(v); }
    void set_wx(int v) noexcept { wx_ = byte(v); }
    [[nodiscard]] bool take_hblank_entry() noexcept { const bool value = entered_hblank_; entered_hblank_ = false; return value; }

    void save(BinaryWriter& out) const {
        constexpr int field_count = 55;
        out.i32(field_count);
        const std::array fields{
            lcdc_, stat_enable_, scy_, scx_, ly_, lyc_, bgp_, obp0_, obp1_, wy_, wx_,
            mode_, reported_mode_, reported_mode_target_, reported_mode_delay_,
            lcd_startup_line_ ? 1 : 0, lcd_startup_lines_remaining_, lyc_compare_delay_,
            frame_mode2_delay_ ? 1 : 0, line_dot_, window_line_,
            lyc_equal_ ? 1 : 0,
            stat_line_ ? 1 : 0, vram_bank_, bcps_index_,
            bcps_auto_ ? 1 : 0, ocps_index_, ocps_auto_ ? 1 : 0,
            transfer_x_, window_started_line_ ? 1 : 0, window_y_condition_ ? 1 : 0,
            opri_dmg_priority_ ? 1 : 0, scx_latched_, startup_dots_remaining_,
            discard_pixels_remaining_, window_restart_dots_remaining_, window_initial_skip_,
            bg_fifo_head_, bg_fifo_size_, fetcher_window_ ? 1 : 0, fetcher_tile_x_,
            fetcher_phase_, fetcher_phase_dots_, fetcher_tile_, fetcher_attributes_,
            fetcher_row_, fetcher_tile_data_address_, fetcher_data_low_, fetcher_data_high_,
            sprite_stall_remaining_, line_sprite_count_, considered_sprite_tile_count_,
            oam_scan_index_, entered_hblank_ ? 1 : 0, frame_ready_ ? 1 : 0,
        };
        for (const int field : fields) out.i32(field);
        for (const auto& pixel : bg_fifo_) {
            out.u8(pixel.color); out.u8(pixel.palette); out.boolean(pixel.priority);
        }
        for (const bool used : sprite_stall_used_) out.boolean(used);
        for (const int tile : considered_sprite_tiles_) out.i32(tile);
        for (const int sprite : sprite_indices_) out.i32(sprite);
        for (const int y : sprite_y_) out.i32(y);
        for (const int x : sprite_x_) out.i32(x);
        for (const auto pixel : working_frame_) out.i32(pixel);
        for (const auto pixel : completed_frame) out.i32(pixel);
        out.raw(vram); out.raw(oam); out.raw(bg_cram); out.raw(obj_cram);
    }
    void load(BinaryReader& in) {
        if (in.i32() != 55) throw SaveStateError("État instantané corrompu (PPU)");
        lcdc_ = in.i32(); stat_enable_ = in.i32(); scy_ = in.i32(); scx_ = in.i32();
        ly_ = in.i32(); lyc_ = in.i32(); bgp_ = in.i32(); obp0_ = in.i32(); obp1_ = in.i32();
        wy_ = in.i32(); wx_ = in.i32(); mode_ = in.i32(); reported_mode_ = in.i32();
        reported_mode_target_ = in.i32(); reported_mode_delay_ = std::clamp(in.i32(), 0, 4);
        lcd_startup_line_ = in.i32() != 0;
        lcd_startup_lines_remaining_ = std::clamp(in.i32(), 0, 3);
        lyc_compare_delay_ = std::clamp(in.i32(), 0, 4);
        frame_mode2_delay_ = in.i32() != 0; line_dot_ = in.i32();
        window_line_ = in.i32(); lyc_equal_ = in.i32() != 0; stat_line_ = in.i32() != 0;
        vram_bank_ = in.i32();
        bcps_index_ = in.i32(); bcps_auto_ = in.i32() != 0; ocps_index_ = in.i32();
        ocps_auto_ = in.i32() != 0; transfer_x_ = in.i32(); window_started_line_ = in.i32() != 0;
        window_y_condition_ = in.i32() != 0; opri_dmg_priority_ = in.i32() != 0;
        scx_latched_ = in.i32(); startup_dots_remaining_ = in.i32(); discard_pixels_remaining_ = in.i32();
        window_restart_dots_remaining_ = in.i32(); window_initial_skip_ = in.i32();
        bg_fifo_head_ = std::clamp(in.i32(), 0, 15); bg_fifo_size_ = std::clamp(in.i32(), 0, 16);
        fetcher_window_ = in.i32() != 0; fetcher_tile_x_ = in.i32(); fetcher_phase_ = std::clamp(in.i32(), 0, 3);
        fetcher_phase_dots_ = std::max(0, in.i32()); fetcher_tile_ = in.i32(); fetcher_attributes_ = in.i32();
        fetcher_row_ = in.i32(); fetcher_tile_data_address_ = in.i32(); fetcher_data_low_ = in.i32();
        fetcher_data_high_ = in.i32(); sprite_stall_remaining_ = std::max(0, in.i32());
        line_sprite_count_ = std::clamp(in.i32(), 0, 10);
        considered_sprite_tile_count_ = std::clamp(in.i32(), 0, 10);
        oam_scan_index_ = std::clamp(in.i32(), 0, 40);
        entered_hblank_ = in.i32() != 0; frame_ready_ = in.i32() != 0;
        for (auto& pixel : bg_fifo_) {
            pixel.color = in.u8(); pixel.palette = in.u8(); pixel.priority = in.boolean();
        }
        for (auto&& used : sprite_stall_used_) used = in.boolean();
        for (auto& tile : considered_sprite_tiles_) tile = in.i32();
        for (auto& sprite : sprite_indices_) sprite = in.i32();
        for (auto& y : sprite_y_) y = in.i32();
        for (auto& x : sprite_x_) x = in.i32();
        for (auto& pixel : working_frame_) pixel = in.i32();
        for (auto& pixel : completed_frame) pixel = in.i32();
        in.raw(vram); in.raw(oam); in.raw(bg_cram); in.raw(obj_cram); rebuild_color_cache();
    }

    std::array<std::uint8_t, 0x4000> vram{};
    std::array<std::uint8_t, 0xa0> oam{};
    std::array<std::uint8_t, 64> bg_cram{};
    std::array<std::uint8_t, 64> obj_cram{};
    std::array<std::int32_t, frame_pixels> completed_frame{};

private:
    struct BgPixel {
        std::uint8_t color{};
        std::uint8_t palette{};
        bool priority{};
    };

    void advance_dot() {
        if (reported_mode_delay_ > 0 && --reported_mode_delay_ == 0) {
            reported_mode_ = reported_mode_target_;
        }
        if (lyc_compare_delay_ > 0 && --lyc_compare_delay_ == 0) {
            update_lyc_compare();
            check_stat();
        }
        if (ly_ < height && mode_ == mode_oam) advance_oam_scan();
        if (ly_ < height && mode_ == mode_transfer) advance_transfer();

        ++line_dot_;
        if (ly_ == 153 && line_dot_ == 4) {
            update_lyc_compare();
            check_stat();
        }
        // Le signal STAT de mode 2 précède de quatre dots le changement
        // visible de mode/LY. C'est observable depuis le CPU et explique les
        // seuils des tests mode 2 -> modes 0/3.
        const int stat_mode2_lead_dot = lcd_startup_line_ ? 448 : 452;
        if (line_dot_ == stat_mode2_lead_dot &&
            (ly_ < 143 || (gb::is_cgb_hardware(hardware_mode_) && ly_ == 143))) {
            check_stat();
        }
        if (frame_mode2_delay_ && line_dot_ == 4) {
            frame_mode2_delay_ = false;
            check_stat();
        }
        // Sur DMG, la source mode 2 produit aussi une impulsion au début de
        // VBlank. Elle ne doit pas rester haute pendant toute la ligne 144.
        if (hardware_mode_ == gb::HardwareMode::dmg && ly_ == 144 && line_dot_ == 1) check_stat();
        if (ly_ < height && ((lcd_startup_line_ && line_dot_ == 76 && mode_ == mode_hblank) ||
                            (!lcd_startup_line_ && line_dot_ == 80 && mode_ == mode_oam))) {
            begin_transfer();
        }

        const int line_length = lcd_startup_line_ ? 452 : 456;
        if (line_dot_ < line_length) return;
        const bool completed_startup_line = lcd_startup_line_;
        line_dot_ = 0;
        lcd_startup_line_ = false;
        if (lcd_startup_lines_remaining_ > 0) --lcd_startup_lines_remaining_;
        ++ly_;
        if (completed_startup_line) {
            // Le premier front LY après LCDC.7 est déphasé dans le cycle
            // CPU. Le comparateur présente d'abord faux, puis publie le
            // nouveau résultat au M-cycle suivant.
            lyc_equal_ = false;
            lyc_compare_delay_ = 4;
        } else {
            update_lyc_compare();
        }
        if (ly_ == height) {
            enter_vblank();
        } else if (ly_ > 153) {
            ly_ = 0;
            update_lyc_compare();
            window_line_ = 0;
            if (wy_ == 0) window_y_condition_ = true;
            frame_mode2_delay_ = true;
            set_mode(mode_oam);
        } else if (ly_ < height) {
            if (ly_ == wy_) window_y_condition_ = true;
            set_mode(mode_oam);
        }
        check_stat();
    }

    void begin_transfer() noexcept {
        transfer_x_ = 0;
        scx_latched_ = scx_;
        startup_dots_remaining_ = 12;
        discard_pixels_remaining_ = scx_latched_ & 7;
        window_restart_dots_remaining_ = 0;
        window_initial_skip_ = 0;
        sprite_stall_remaining_ = 0;
        window_started_line_ = false;
        sprite_stall_used_.fill(false);
        considered_sprite_tiles_.fill(-1);
        considered_sprite_tile_count_ = 0;
        reset_bg_fetcher(false);
        set_mode(mode_transfer);
    }

    void advance_transfer() {
        if (sprite_stall_remaining_ > 0) {
            --sprite_stall_remaining_;
            return;
        }

        if (window_should_start()) {
            start_window_fetch();
        }

        for (int i = 0; i < line_sprite_count_; ++i) {
            if (sprite_stall_used_[static_cast<std::size_t>(i)]) continue;
            const int sprite_x = sprite_x_[static_cast<std::size_t>(i)] - 8;
            if (std::max(0, sprite_x) == transfer_x_) {
                sprite_stall_used_[static_cast<std::size_t>(i)] = true;
                const bool timing_enabled = gb::is_cgb_hardware(hardware_mode_) || (lcdc_ & 2) != 0;
                if (timing_enabled) {
                    sprite_stall_remaining_ = sprite_penalty(i, sprite_x) - 1;
                    return;
                }
            }
        }

        tick_bg_fetcher();
        if (startup_dots_remaining_ > 0) {
            --startup_dots_remaining_;
            return;
        }
        if (window_restart_dots_remaining_ > 0) {
            --window_restart_dots_remaining_;
            if (window_restart_dots_remaining_ == 0) discard_window_prefix();
            return;
        }
        if (window_initial_skip_ > 0) {
            discard_window_prefix();
            if (window_initial_skip_ > 0) return;
        }
        if (discard_pixels_remaining_ > 0) {
            if (bg_fifo_size_ > 0) {
                static_cast<void>(pop_bg_pixel());
                --discard_pixels_remaining_;
            }
            return;
        }
        if (bg_fifo_size_ <= 0) return;

        render_fifo_pixel(transfer_x_, pop_bg_pixel());
        ++transfer_x_;
        if (transfer_x_ >= width) {
            if (window_started_line_) ++window_line_;
            set_mode(mode_hblank);
            entered_hblank_ = true;
        }
    }

    [[nodiscard]] bool window_enabled_for_rendering() const noexcept {
        if ((lcdc_ & 0x20) == 0) return false;
        return gb::cgb_features_enabled(hardware_mode_) || (lcdc_ & 1) != 0;
    }

    [[nodiscard]] bool window_should_start() const noexcept {
        if (window_started_line_ || !window_y_condition_ || !window_enabled_for_rendering() || wx_ > 166) return false;
        return transfer_x_ >= std::max(0, wx_ - 7);
    }

    void start_window_fetch() noexcept {
        window_started_line_ = true;
        reset_bg_fetcher(true);
        const bool wx_zero_shortening = wx_ == 0 && (scx_latched_ & 7) != 0;
        window_restart_dots_remaining_ = wx_zero_shortening ? 5 : 6;
        window_initial_skip_ = std::max(0, 7 - wx_);
        if (wx_ == 0) window_initial_skip_ += scx_latched_ & 7;
    }

    void reset_bg_fetcher(bool window) noexcept {
        bg_fifo_head_ = 0;
        bg_fifo_size_ = 0;
        fetcher_window_ = window;
        fetcher_tile_x_ = 0;
        fetcher_phase_ = 0;
        fetcher_phase_dots_ = 2;
        fetcher_tile_ = 0;
        fetcher_attributes_ = 0;
        fetcher_row_ = 0;
        fetcher_data_low_ = 0;
        fetcher_data_high_ = 0;
    }

    void tick_bg_fetcher() noexcept {
        if (fetcher_phase_ == 3) {
            if (bg_fifo_size_ <= 8) push_fetched_tile();
            return;
        }
        if (--fetcher_phase_dots_ > 0) return;

        switch (fetcher_phase_) {
        case 0: fetch_tile_index(); fetcher_phase_ = 1; fetcher_phase_dots_ = 2; break;
        case 1: fetch_tile_low(); fetcher_phase_ = 2; fetcher_phase_dots_ = 2; break;
        default:
            fetcher_data_high_ = vram_byte(fetcher_bank(), fetcher_tile_data_address_ + 1);
            if (bg_fifo_size_ <= 8) push_fetched_tile(); else fetcher_phase_ = 3;
            break;
        }
    }

    void fetch_tile_index() noexcept {
        const int y = fetcher_window_ ? window_line_ : ((ly_ + scy_) & 0xff);
        const int tile_x = fetcher_window_ ? fetcher_tile_x_ : ((scx_latched_ >> 3) + fetcher_tile_x_) & 31;
        const int map = fetcher_window_ ? ((lcdc_ & 0x40) != 0 ? 0x1c00 : 0x1800)
                                        : ((lcdc_ & 8) != 0 ? 0x1c00 : 0x1800);
        const int map_address = map + ((y >> 3) & 31) * 32 + tile_x;
        fetcher_tile_ = vram_byte(0, map_address);
        fetcher_attributes_ = gb::cgb_features_enabled(hardware_mode_) ? vram_byte(1, map_address) : 0;
        fetcher_row_ = y & 7;
        if ((fetcher_attributes_ & 0x40) != 0) fetcher_row_ = 7 - fetcher_row_;
    }

    [[nodiscard]] int fetcher_bank() const noexcept {
        return gb::cgb_features_enabled(hardware_mode_) ? (fetcher_attributes_ >> 3) & 1 : 0;
    }

    void fetch_tile_low() noexcept {
        const int base = (lcdc_ & 0x10) != 0
            ? fetcher_tile_ * 16
            : 0x1000 + static_cast<std::int8_t>(fetcher_tile_) * 16;
        fetcher_tile_data_address_ = base + fetcher_row_ * 2;
        fetcher_data_low_ = vram_byte(fetcher_bank(), fetcher_tile_data_address_);
    }

    void push_fetched_tile() noexcept {
        const bool flipped = gb::cgb_features_enabled(hardware_mode_) && (fetcher_attributes_ & 0x20) != 0;
        for (int pixel = 0; pixel < 8 && bg_fifo_size_ < 16; ++pixel) {
            const int bit = flipped ? pixel : 7 - pixel;
            const int color = (((fetcher_data_high_ >> bit) & 1) << 1) | ((fetcher_data_low_ >> bit) & 1);
            const int tail = (bg_fifo_head_ + bg_fifo_size_) & 15;
            bg_fifo_[static_cast<std::size_t>(tail)] = BgPixel{
                static_cast<std::uint8_t>(color),
                static_cast<std::uint8_t>(fetcher_attributes_ & 7),
                (fetcher_attributes_ & 0x80) != 0,
            };
            ++bg_fifo_size_;
        }
        ++fetcher_tile_x_;
        fetcher_phase_ = 0;
        fetcher_phase_dots_ = 2;
    }

    [[nodiscard]] BgPixel pop_bg_pixel() noexcept {
        const auto result = bg_fifo_[static_cast<std::size_t>(bg_fifo_head_)];
        bg_fifo_head_ = (bg_fifo_head_ + 1) & 15;
        --bg_fifo_size_;
        return result;
    }

    void discard_window_prefix() noexcept {
        while (window_initial_skip_ > 0 && bg_fifo_size_ > 0) {
            static_cast<void>(pop_bg_pixel());
            --window_initial_skip_;
        }
    }

    int sprite_penalty(int sprite_slot, int sprite_x) noexcept {
        const int raw_x = sprite_x_[static_cast<std::size_t>(sprite_slot)];
        const int coordinate = fetcher_window_ ? sprite_x - (wx_ - 7) : sprite_x + scx_latched_;
        const int wrapped = coordinate & 0xff;
        const int tile_key = (fetcher_window_ ? 0x100 : 0) | (wrapped >> 3);
        bool first_for_tile = true;
        for (int i = 0; i < considered_sprite_tile_count_; ++i) {
            if (considered_sprite_tiles_[static_cast<std::size_t>(i)] == tile_key) {
                first_for_tile = false;
                break;
            }
        }
        if (first_for_tile && considered_sprite_tile_count_ < static_cast<int>(considered_sprite_tiles_.size())) {
            considered_sprite_tiles_[static_cast<std::size_t>(considered_sprite_tile_count_++)] = tile_key;
        }
        if (raw_x == 0) {
            // Le premier fetch X=0 recouvre quatre dots du pipeline initial;
            // les suivants, sur la même tuile, ne paient plus que les six
            // dots du fetch OBJ.
            if (!first_for_tile) return 6;
            return considered_sprite_tile_count_ > 1 ? 11 : 7;
        }
        if (!first_for_tile) return 6;
        const int fine = wrapped & 7;
        const int fetch_wait = std::max(0, 7 - fine - 2);
        if (considered_sprite_tile_count_ > 1) return 6 + fetch_wait;
        // Au moment où le premier OBJ d'une tuile atteint le bord du FIFO,
        // trois dots de son attente (quatre exactement à la frontière de
        // tuile) recouvrent le fetch BG déjà en cours.
        return 6 + fetch_wait - (fine == 0 ? 4 : 3);
    }

    void render_fifo_pixel(int x, const BgPixel& pixel) {
        const int base = ly_ * width;
        if (hardware_mode_ == gb::HardwareMode::cgb_native) {
            bg_color_line_[static_cast<std::size_t>(x)] = pixel.color;
            bg_priority_line_[static_cast<std::size_t>(x)] = pixel.priority;
            working_frame_[static_cast<std::size_t>(base + x)] =
                bg_argb_[static_cast<std::size_t>(pixel.palette * 4 + pixel.color)];
            render_timed_sprite_cgb(base, x);
            return;
        }

        const int color = (lcdc_ & 1) != 0 ? pixel.color : 0;
        bg_color_line_[static_cast<std::size_t>(x)] = color;
        bg_priority_line_[static_cast<std::size_t>(x)] = false;
        const int shade = (bgp_ >> (color * 2)) & 3;
        working_frame_[static_cast<std::size_t>(base + x)] =
            hardware_mode_ == gb::HardwareMode::cgb_compatibility
                ? bg_argb_[static_cast<std::size_t>(shade)]
                : shade;
        render_timed_sprite_dmg(base, x);
    }

    void enter_vblank() {
        window_y_condition_ = false;
        set_mode(mode_vblank); interrupts_.request(Interrupt::vblank);
        completed_frame = working_frame_; frame_ready_ = true;
    }
    void set_mode(int mode) noexcept {
        mode_ = mode;
        if (mode == mode_vblank) {
            reported_mode_ = mode;
            reported_mode_target_ = mode;
            reported_mode_delay_ = 0;
        } else if (reported_mode_ != mode) {
            reported_mode_target_ = mode;
            if (hardware_mode_ == gb::HardwareMode::dmg && lcd_startup_lines_remaining_ > 0) {
                reported_mode_delay_ = 4;
            } else {
                reported_mode_ = mode;
                reported_mode_delay_ = 0;
            }
        }
        if (mode == mode_oam) {
            oam_scan_index_ = 0;
            line_sprite_count_ = 0;
        }
        check_stat();
    }
    void check_stat() noexcept {
        if (!lcd_enabled()) return;
        const bool line = ((stat_enable_ & 8) != 0 && mode_ == mode_hblank) ||
            ((stat_enable_ & 0x10) != 0 && mode_ == mode_vblank) ||
            ((stat_enable_ & 0x20) != 0 && mode2_stat_condition()) ||
            ((stat_enable_ & 0x40) != 0 && lyc_equal_);
        if (line && !stat_line_) interrupts_.request(Interrupt::stat);
        stat_line_ = line;
    }

    [[nodiscard]] bool mode2_stat_condition() const noexcept {
        if (mode_ == mode_oam) return !frame_mode2_delay_;
        const int lead_dot = lcd_startup_line_ ? 448 : 452;
        if (line_dot_ >= lead_dot &&
            (ly_ < 143 || (gb::is_cgb_hardware(hardware_mode_) && ly_ == 143))) {
            return true;
        }
        return hardware_mode_ == gb::HardwareMode::dmg &&
            mode_ == mode_vblank && ly_ == 144 && line_dot_ == 0;
    }

    void update_lyc_compare() noexcept { lyc_equal_ = visible_ly() == lyc_; }

    [[nodiscard]] int vram_byte(int bank, int address) const noexcept {
        return vram[static_cast<std::size_t>(bank * 0x2000 + (address & 0x1fff))];
    }
    [[nodiscard]] int visible_ly() const noexcept {
        return ly_ == 153 && line_dot_ >= 4 ? 0 : ly_;
    }

    void render_timed_sprite_dmg(int base, int x) {
        if ((lcdc_ & 2) == 0) return;
        const int sprite_height = (lcdc_ & 4) != 0 ? 16 : 8;
        int best = -1;
        int best_x = 256;
        int best_color{};
        for (int i = 0; i < line_sprite_count_; ++i) {
            const int sprite = sprite_indices_[static_cast<std::size_t>(i)];
            const int index = sprite * 4;
            const int sy = sprite_y_[static_cast<std::size_t>(i)] - 16;
            const int sx = sprite_x_[static_cast<std::size_t>(i)] - 8;
            if (x < sx || x >= sx + 8) continue;
            int tile = oam[static_cast<std::size_t>(index + 2)];
            const int attr = oam[static_cast<std::size_t>(index + 3)];
            if (sprite_height == 16) tile &= 0xfe;
            int row = ly_ - sy;
            if ((attr & 0x40) != 0) row = sprite_height - 1 - row;
            const int px = x - sx;
            const int bit_index = (attr & 0x20) != 0 ? px : 7 - px;
            const int lo = vram_byte(0, tile * 16 + row * 2);
            const int hi = vram_byte(0, tile * 16 + row * 2 + 1);
            const int color = (((hi >> bit_index) & 1) << 1) | ((lo >> bit_index) & 1);
            if (color == 0) continue;
            const int raw_x = sprite_x_[static_cast<std::size_t>(i)];
            const bool better = best < 0 || (!opri_dmg_priority_ ? sprite < best
                : raw_x < best_x || (raw_x == best_x && sprite < best));
            if (better) {
                best = sprite;
                best_x = raw_x;
                best_color = color;
            }
        }
        if (best < 0) return;
        const int index = best * 4;
        const int attr = oam[static_cast<std::size_t>(index + 3)];
        if ((attr & 0x80) != 0 && bg_color_line_[static_cast<std::size_t>(x)] != 0) return;
        const int palette = (attr & 0x10) != 0 ? obp1_ : obp0_;
        const int shade = (palette >> (best_color * 2)) & 3;
        if (hardware_mode_ == gb::HardwareMode::cgb_compatibility) {
            const int color_palette = (attr & 0x10) != 0 ? 1 : 0;
            working_frame_[static_cast<std::size_t>(base + x)] =
                obj_argb_[static_cast<std::size_t>(color_palette * 4 + shade)];
        } else {
            working_frame_[static_cast<std::size_t>(base + x)] = shade;
        }
    }

    void render_timed_sprite_cgb(int base, int x) {
        if ((lcdc_ & 2) == 0) return;
        const int sprite_height = (lcdc_ & 4) != 0 ? 16 : 8;
        int best = -1;
        int best_x = 256;
        int best_color{};
        for (int i = 0; i < line_sprite_count_; ++i) {
            const int sprite = sprite_indices_[static_cast<std::size_t>(i)];
            const int index = sprite * 4;
            const int sy = sprite_y_[static_cast<std::size_t>(i)] - 16;
            const int sx = sprite_x_[static_cast<std::size_t>(i)] - 8;
            if (x < sx || x >= sx + 8) continue;
            int tile = oam[static_cast<std::size_t>(index + 2)];
            const int attr = oam[static_cast<std::size_t>(index + 3)];
            if (sprite_height == 16) tile &= 0xfe;
            int row = ly_ - sy;
            if ((attr & 0x40) != 0) row = sprite_height - 1 - row;
            const int px = x - sx;
            const int bit_index = (attr & 0x20) != 0 ? px : 7 - px;
            const int bank = (attr >> 3) & 1;
            const int lo = vram_byte(bank, tile * 16 + row * 2);
            const int hi = vram_byte(bank, tile * 16 + row * 2 + 1);
            const int obj_color = (((hi >> bit_index) & 1) << 1) | ((lo >> bit_index) & 1);
            if (obj_color == 0) continue;
            const int raw_x = sprite_x_[static_cast<std::size_t>(i)];
            const bool better = best < 0 || (!opri_dmg_priority_ ? sprite < best
                : raw_x < best_x || (raw_x == best_x && sprite < best));
            if (better) {
                best = sprite;
                best_x = raw_x;
                best_color = obj_color;
            }
        }
        if (best < 0) return;
        const int index = best * 4;
        const int attr = oam[static_cast<std::size_t>(index + 3)];
        const int palette = attr & 7;
        const bool bg_visible = bg_color_line_[static_cast<std::size_t>(x)] != 0;
        const bool master_priority = (lcdc_ & 1) != 0;
        if (bg_visible && master_priority &&
            (bg_priority_line_[static_cast<std::size_t>(x)] || (attr & 0x80) != 0)) return;
        working_frame_[static_cast<std::size_t>(base + x)] =
            obj_argb_[static_cast<std::size_t>(palette * 4 + best_color)];
    }

    void advance_oam_scan() noexcept {
        // Mode 2 inspecte quarante entrées sur 80 dots. Les coordonnées Y/X
        // des dix premières entrées retenues sont latched dans le tampon
        // interne ; un OAM DMA ultérieur ne peut donc plus les réécrire.
        if ((line_dot_ & 1) == 0 || oam_scan_index_ >= 40) return;
        const int sprite = oam_scan_index_++;
        const int raw_y = oam[static_cast<std::size_t>(sprite * 4)];
        const int raw_x = oam[static_cast<std::size_t>(sprite * 4 + 1)];
        const int y = raw_y - 16;
        const int sprite_height = (lcdc_ & 4) != 0 ? 16 : 8;
        if (line_sprite_count_ >= 10 || ly_ < y || ly_ >= y + sprite_height) return;
        const auto slot = static_cast<std::size_t>(line_sprite_count_++);
        sprite_indices_[slot] = sprite;
        sprite_y_[slot] = raw_y;
        sprite_x_[slot] = raw_x;
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
    gb::HardwareMode hardware_mode_{gb::HardwareMode::dmg};
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
    int reported_mode_{mode_oam};
    int reported_mode_target_{mode_oam};
    int reported_mode_delay_{};
    bool lcd_startup_line_{};
    int lcd_startup_lines_remaining_{};
    int lyc_compare_delay_{};
    bool frame_mode2_delay_{};
    int line_dot_{};
    int window_line_{};
    bool lyc_equal_{true};
    int transfer_x_{};
    bool window_started_line_{};
    // The HLE/post-boot state starts at LY=0 with WY=0 and the LCD already on.
    // Consequently the WY condition has already been observed for the first
    // scanline.  LCD enable/disable and frame transitions maintain it from
    // this point onward.
    bool window_y_condition_{true};
    bool opri_dmg_priority_{};
    int scx_latched_{};
    int startup_dots_remaining_{};
    int discard_pixels_remaining_{};
    int window_restart_dots_remaining_{};
    int window_initial_skip_{};
    std::array<BgPixel, 16> bg_fifo_{};
    int bg_fifo_head_{};
    int bg_fifo_size_{};
    bool fetcher_window_{};
    int fetcher_tile_x_{};
    int fetcher_phase_{};
    int fetcher_phase_dots_{2};
    int fetcher_tile_{};
    int fetcher_attributes_{};
    int fetcher_row_{};
    int fetcher_tile_data_address_{};
    int fetcher_data_low_{};
    int fetcher_data_high_{};
    int sprite_stall_remaining_{};
    int line_sprite_count_{};
    int oam_scan_index_{};
    std::array<bool, 10> sprite_stall_used_{};
    std::array<int, 10> considered_sprite_tiles_{};
    int considered_sprite_tile_count_{};
    bool stat_line_{};
    bool entered_hblank_{};
    bool frame_ready_{};
    std::array<std::int32_t, frame_pixels> working_frame_{};
    std::array<int, width> bg_color_line_{};
    std::array<bool, width> bg_priority_line_{};
    std::array<int, 10> sprite_indices_{};
    std::array<int, 10> sprite_y_{};
    std::array<int, 10> sprite_x_{};
};

} // namespace ravenemu::cgb
