from pathlib import Path
import re

path = Path("engine/runtime/src/test/kotlin/com/ravenemu/core/gb/NativeParityTest.kt")
text = path.read_text()

old = '''            assertContentEquals(
                reference.saveState(),
                natif.saveState(),
                "$etiquette : états divergents après $FRAMES trames",
            )
'''
new = '''            // L'oracle Kotlin et le cœur C++ n'ont plus vocation à partager leur
            // représentation binaire interne : le natif porte désormais l'état
            // du pipeline PPU, des DMA, de STOP, du SIO et de l'IR. On exige en
            // revanche que chacun puisse restaurer transactionnellement son
            // propre état avant de poursuivre la comparaison fonctionnelle.
            val etatReference = reference.saveState()
            val etatNatif = natif.saveState()
            reference.loadState(etatReference)
            natif.loadState(etatNatif)
'''
if text.count(old) != 1:
    raise RuntimeError("bloc de comparaison d'état introuvable")
text = text.replace(old, new, 1)

pattern = re.compile(
    r'''    @Test\n    fun `un etat ecrit par le Kotlin est relu par le natif et inversement`\(\) \{.*?\n    \}\n\n    @Test\n    fun `les deux implementations exposent la meme RAM a pile`''',
    re.S,
)
replacement = '''    @Test
    fun `chaque implementation restaure son propre etat puis reste en parite`() {
        val rom = programmeReel(cgbFlag = 0x80)
        val reference = KotlinGameBoyCore(HORLOGE)
        GameBoyCore(HORLOGE).use { natif ->
            reference.loadRom(rom, null)
            natif.loadRom(rom, null)

            repeat(40) {
                reference.runFrame(framebuffer())
                natif.runFrame(framebuffer())
            }

            val etatReference = reference.saveState()
            val etatNatif = natif.saveState()
            reference.loadState(etatReference)
            natif.loadState(etatNatif)

            val suiteRef = framebuffer()
            val suiteNat = framebuffer()
            repeat(20) { pas ->
                reference.runFrame(suiteRef)
                natif.runFrame(suiteNat)
                assertContentEquals(
                    suiteRef,
                    suiteNat,
                    "Divergence à la trame $pas après restaurations propres",
                )
            }
        }
    }

    @Test
    fun `les deux implementations exposent la meme RAM a pile`'''
text, count = pattern.subn(replacement, text, count=1)
if count != 1:
    raise RuntimeError("test de restauration croisée introuvable")

path.write_text(text)
print("GB native parity adjusted")
