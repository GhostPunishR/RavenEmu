package com.ravenemu.core.gb.memory

/**
 * Bus 8 bits vu par le CPU : adresses 0x0000–0xFFFF, valeurs 0x00–0xFF.
 * Le CPU ne dépend que de cette interface, ce qui permet de le tester sur une
 * mémoire plate sans instancier la machine complète.
 */
interface Bus {
    fun read(address: Int): Int
    fun write(address: Int, value: Int)

    fun readWord(address: Int): Int =
        read(address) or (read((address + 1) and 0xFFFF) shl 8)

    fun writeWord(address: Int, value: Int) {
        write(address, value and 0xFF)
        write((address + 1) and 0xFFFF, (value shr 8) and 0xFF)
    }
}
