#pragma once

#include "support/bits.hpp"

namespace ravenemu::cgb {

class Bus {
public:
    virtual ~Bus() = default;
    [[nodiscard]] virtual int read(int address) = 0;
    virtual void write(int address, int value) = 0;
    virtual void on_stop() {}
    [[nodiscard]] int read_word(int address) {
        return read(address) | (read(word(address + 1)) << 8);
    }
    void write_word(int address, int value) {
        write(address, value); write(word(address + 1), value >> 8);
    }
};

} // namespace ravenemu::cgb
