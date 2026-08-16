#include "cpu/arm9.hpp"

#include "check.hpp"

#include <array>
#include <cstdint>
#include <initializer_list>
#include <string>

/**
 * Coprocesseur système CP15, et ce qu'il change au processeur.
 *
 * Deux sortes de vérifications se côtoient ici, et elles n'ont pas la même
 * valeur. Les premières éprouvent le banc de registres : ce qui s'écrit, ce qui
 * se relit, ce qui est figé. Les secondes éprouvent **ce que le processeur fait
 * différemment** une fois le coprocesseur configuré — où il cherche ses
 * instructions, où il trouve ses vecteurs, quand il s'arrête. Ce sont
 * celles-là qui disent si le lot sert à quelque chose.
 */
namespace ravenemu::nds::testing {

using ravenemu::testing::check;

namespace {

/** Mémoire plate de 64 Kio, en petit-boutiste comme la console. */
class TestBus final : public Bus {
public:
    static constexpr std::uint32_t size = 0x1'0000;

    [[nodiscard]] std::uint8_t read8(std::uint32_t address) override {
        ++reads;
        return memory_[address % size];
    }

    [[nodiscard]] std::uint16_t read16(std::uint32_t address) override {
        return static_cast<std::uint16_t>(
            static_cast<std::uint32_t>(read8(address)) |
            (static_cast<std::uint32_t>(read8(address + 1U)) << 8U)
        );
    }

    [[nodiscard]] std::uint32_t read32(std::uint32_t address) override {
        return static_cast<std::uint32_t>(read16(address)) |
            (static_cast<std::uint32_t>(read16(address + 2U)) << 16U);
    }

    void write8(std::uint32_t address, std::uint8_t value) override {
        ++writes;
        memory_[address % size] = value;
    }

    void write16(std::uint32_t address, std::uint16_t value) override {
        write8(address, static_cast<std::uint8_t>(value & 0xffU));
        write8(address + 1U, static_cast<std::uint8_t>((value >> 8U) & 0xffU));
    }

    void write32(std::uint32_t address, std::uint32_t value) override {
        write16(address, static_cast<std::uint16_t>(value & 0xffffU));
        write16(address + 2U, static_cast<std::uint16_t>((value >> 16U) & 0xffffU));
    }

    /** Compteurs d'accès : ils disent si le bus a été sollicité, ou court-circuité. */
    std::uint32_t reads{};
    std::uint32_t writes{};

    /** Écriture directe, sans compter, pour préparer un décor. */
    void poke32(std::uint32_t address, std::uint32_t value) {
        for (std::uint32_t index = 0; index < 4U; ++index) {
            memory_[(address + index) % size] =
                static_cast<std::uint8_t>((value >> (index * 8U)) & 0xffU);
        }
    }

private:
    std::array<std::uint8_t, size> memory_{};
};

constexpr std::uint32_t program_base = 0x0000'0100;
constexpr std::uint32_t always = 0xeU;

/** `cond 1110 op1 L CRn Rd cp op2 1 CRm`. */
constexpr std::uint32_t coprocessor_transfer(
    bool read,
    std::uint32_t operation,
    std::uint32_t rd,
    std::uint32_t primary,
    std::uint32_t secondary,
    std::uint32_t sub_operation,
    std::uint32_t coprocessor = 15U
) noexcept {
    return (always << 28U) | (0xeU << 24U) | (operation << 21U) | (read ? (1U << 20U) : 0U) |
        (primary << 16U) | (rd << 12U) | (coprocessor << 8U) | (sub_operation << 5U) |
        (1U << 4U) | secondary;
}

/** `MOV Rd, #value`, pour amener une valeur avant de l'écrire au coprocesseur. */
constexpr std::uint32_t mov_immediate(std::uint32_t rd, std::uint32_t value, std::uint32_t rotation = 0U) noexcept {
    return (always << 28U) | (1U << 25U) | (0xdU << 21U) | (rd << 12U) |
        (((rotation / 2U) << 8U) | value);
}

/** `cond 01 I P U B W L Rn Rd offset`, forme pré-indexée sans réécriture. */
constexpr std::uint32_t transfer(bool load, bool byte_access, std::uint32_t rn, std::uint32_t rd) noexcept {
    return (always << 28U) | (1U << 26U) | (1U << 24U) | (1U << 23U) |
        (byte_access ? (1U << 22U) : 0U) | (load ? (1U << 20U) : 0U) | (rn << 16U) | (rd << 12U);
}

constexpr std::uint32_t software_interrupt = (always << 28U) | (0xfU << 24U);
/** Instruction de coprocesseur sans transfert : sans emploi sur ce cœur. */
constexpr std::uint32_t coprocessor_data_operation = 0xee00'0f00U;
/** Transfert mémoire de coprocesseur : sans emploi non plus. */
constexpr std::uint32_t coprocessor_load = 0xec10'0f00U;

struct Machine {
    TestBus bus{};
    Arm9 cpu{bus};

    Machine() {
        cpu.reset();
        cpu.state().cpsr = static_cast<std::uint32_t>(CpuMode::system);
    }

    void load(std::uint32_t address, std::initializer_list<std::uint32_t> program) {
        auto cursor = address;
        for (const auto word : program) {
            bus.poke32(cursor, word);
            cursor += 4U;
        }
    }

    void run(std::initializer_list<std::uint32_t> program) {
        load(program_base, program);
        cpu.state().registers[15] = program_base;
        for (std::size_t index = 0; index < program.size(); ++index) cpu.step();
    }

    [[nodiscard]] std::uint32_t& reg(std::uint32_t index) noexcept {
        return cpu.state().registers[index];
    }

    [[nodiscard]] Cp15& cp15() noexcept { return cpu.cp15(); }

    /** Configure une mémoire locale sans passer par des instructions. */
    void configure_tcm(std::uint32_t control, std::uint32_t itcm, std::uint32_t dtcm) {
        cp15().write(0U, 9U, 1U, 1U, itcm);
        cp15().write(0U, 9U, 1U, 0U, dtcm);
        cp15().write(0U, 1U, 0U, 0U, control);
    }
};

/** Taille de fenêtre codée dans un registre de région. */
constexpr std::uint32_t window_of(std::uint32_t base, std::uint32_t size_code) noexcept {
    return base | (size_code << 1U);
}

// --------------------------------------------------------------------------

void les_registres_d_identite_sont_figes() {
    Machine machine;
    machine.run({
        coprocessor_transfer(true, 0U, 0U, 0U, 0U, 0U),
        coprocessor_transfer(true, 0U, 1U, 0U, 0U, 1U),
        coprocessor_transfer(true, 0U, 2U, 0U, 0U, 2U),
        coprocessor_transfer(true, 0U, 3U, 0U, 0U, 5U),
    });
    check(machine.reg(0) == Cp15::main_id, "identifiant principal");
    check(machine.reg(1) == Cp15::cache_type, "registre de type de cache");
    check(machine.reg(2) == Cp15::tcm_size, "registre de taille des mémoires locales");
    // Un numéro sans emploi de ce registre rend l'identifiant principal plutôt
    // que zéro : un logiciel ne doit pas en conclure qu'il n'y a pas de cœur.
    check(machine.reg(3) == Cp15::main_id, "un numéro sans emploi retombe sur l'identifiant");

    // Comparer le registre à la constante qui le définit ne prouverait rien.
    // Ce qui compte est ce qu'il *dit* : constructeur ARM, architecture v5TE,
    // pièce 946. Un cœur qui s'annoncerait autrement ferait prendre au logiciel
    // des chemins qui ne sont pas les siens.
    check((Cp15::main_id >> 24U) == 0x41U, "le constructeur annoncé est ARM");
    check(((Cp15::main_id >> 16U) & 0xfU) == 0x5U, "l'architecture annoncée est la cinquième");
    check(((Cp15::main_id >> 4U) & 0xfffU) == 0x946U, "la pièce annoncée est le 946");
    check(((Cp15::main_id >> 20U) & 0xfU) == 0x0U, "la variante annoncée est la première");
    check((Cp15::main_id & 0xfU) == 0x1U, "la révision annoncée est la première");

    // Le registre de type de cache décrit les caches de la console : huit
    // kilooctets d'instructions, quatre de données, quatre voies, lignes de
    // trente-deux octets.
    const auto instruction_cache = Cp15::cache_type & 0xfffU;
    const auto data_cache = (Cp15::cache_type >> 12U) & 0xfffU;
    check((Cp15::cache_type >> 24U & 1U) == 1U, "les deux caches sont séparés");
    check((512U << ((instruction_cache >> 6U) & 0x7U)) == 8U * 1024U, "cache d'instructions de huit kilooctets");
    check((512U << ((data_cache >> 6U) & 0x7U)) == 4U * 1024U, "cache de données de quatre kilooctets");
    check((1U << ((instruction_cache >> 3U) & 0x7U)) == 4U, "quatre voies pour les instructions");
    check((1U << ((data_cache >> 3U) & 0x7U)) == 4U, "quatre voies pour les données");
    check((instruction_cache & 0x3U) == 2U, "lignes de huit mots");

    // Les tailles annoncées sont bien celles de la console, codées en `512 << n`.
    const auto itcm_code = (Cp15::tcm_size >> 6U) & 0xfU;
    const auto dtcm_code = (Cp15::tcm_size >> 18U) & 0xfU;
    check(512U << itcm_code == Cp15::itcm_bytes, "l'ITCM annoncée fait trente-deux kilooctets");
    check(512U << dtcm_code == Cp15::dtcm_bytes, "la DTCM annoncée fait seize kilooctets");

    // Écrire un registre d'identité ne le change pas.
    machine.reg(4) = 0xffff'ffffU;
    machine.run({
        coprocessor_transfer(false, 0U, 4U, 0U, 0U, 0U),
        coprocessor_transfer(true, 0U, 5U, 0U, 0U, 0U),
    });
    check(machine.reg(5) == Cp15::main_id, "l'identifiant reste ce qu'il est");
}

void le_registre_de_controle_a_ses_bits_figes() {
    {
        Machine machine;
        machine.run({coprocessor_transfer(true, 0U, 0U, 1U, 0U, 0U)});
        check(machine.reg(0) == Cp15::control_read_as_one, "à la mise sous tension, seuls les bits câblés sont posés");
        // Les bits câblés sont ceux de rang trois à six ; les nommer plutôt que
        // les recopier depuis la constante qui les définit.
        check(Cp15::control_read_as_one == 0x78U, "quatre bits sont câblés à un");
        for (std::uint32_t rank = 3U; rank <= 6U; ++rank) {
            check(
                (Cp15::control_read_as_one & (1U << rank)) != 0U,
                "le bit de rang " + std::to_string(rank) + " est câblé"
            );
        }
    }
    {   // Tout écrire ne pose que les bits modifiables, et ne peut pas effacer
        // ceux qui sont câblés à un.
        Machine machine;
        machine.cp15().write(0U, 1U, 0U, 0U, 0xffff'ffffU);
        const auto control = machine.cp15().control();
        check(control == (Cp15::control_writable | Cp15::control_read_as_one), "masque d'écriture du contrôle");
        check((control & Cp15::control_read_as_one) == Cp15::control_read_as_one, "les bits câblés restent posés");
    }
    {   // Tout effacer laisse les bits câblés.
        Machine machine;
        machine.cp15().write(0U, 1U, 0U, 0U, 0xffff'ffffU);
        machine.cp15().write(0U, 1U, 0U, 0U, 0U);
        check(machine.cp15().control() == Cp15::control_read_as_one, "un effacement ne descelle pas les bits câblés");
    }
    {   // Chaque bit nommé est bien modifiable.
        constexpr std::uint32_t bits[] = {
            Cp15::protection_enable, Cp15::data_cache_enable, Cp15::big_endian,
            Cp15::instruction_cache_enable, Cp15::high_vectors, Cp15::round_robin,
            Cp15::legacy_thumb, Cp15::dtcm_enable, Cp15::dtcm_load_mode,
            Cp15::itcm_enable, Cp15::itcm_load_mode,
        };
        for (const auto bit : bits) {
            Machine machine;
            machine.cp15().write(0U, 1U, 0U, 0U, bit);
            check(
                (machine.cp15().control() & bit) != 0U,
                "le bit " + std::to_string(bit) + " du contrôle doit être modifiable"
            );
        }
    }
    {   // Le passage par une instruction donne le même résultat.
        Machine machine;
        machine.run({
            // 0x2000 se code comme 0x20 tourné de vingt-quatre positions.
            mov_immediate(0U, Cp15::high_vectors >> 8U, 24U),
            coprocessor_transfer(false, 0U, 0U, 1U, 0U, 0U),
            coprocessor_transfer(true, 0U, 1U, 1U, 0U, 0U),
        });
        check(
            machine.reg(1) == (Cp15::high_vectors | Cp15::control_read_as_one),
            "le contrôle écrit par MCR se relit par MRC"
        );
    }
}

void les_instructions_de_coprocesseur_sont_filtrees() {
    {   // Le cœur n'a que le coprocesseur quinze.
        Machine machine;
        machine.run({coprocessor_transfer(true, 0U, 0U, 0U, 0U, 0U, 14U)});
        check(machine.cpu.unimplemented_count() == 1U, "un autre coprocesseur est refusé");
        check(machine.reg(15) == Arm9::undefined_vector, "par l'exception prévue à cet effet");
    }
    {   // Une opération de coprocesseur sans transfert n'a pas d'emploi ici.
        Machine machine;
        machine.run({coprocessor_data_operation});
        check(machine.cpu.unimplemented_count() == 1U, "l'opération sans transfert est refusée");
    }
    {   // Les transferts mémoire de coprocesseur non plus.
        Machine machine;
        machine.run({coprocessor_load});
        check(machine.cpu.unimplemented_count() == 1U, "le transfert mémoire de coprocesseur est refusé");
    }
    {   // L'appel superviseur partage le même espace et ne doit pas être happé.
        Machine machine;
        machine.run({software_interrupt});
        check(machine.cpu.unimplemented_count() == 0U, "l'appel superviseur n'est pas une instruction de coprocesseur");
        check(machine.cpu.state().mode() == CpuMode::supervisor, "et fait bien basculer en mode superviseur");
    }
    {   // Seule l'opération primaire zéro existe : les autres sont comptées.
        Machine machine;
        machine.run({coprocessor_transfer(true, 3U, 0U, 0U, 0U, 0U)});
        check(machine.cpu.unimplemented_count() == 0U, "l'instruction reste valide");
        check(machine.cp15().unknown_access_count() == 1U, "mais le registre visé n'existe pas");
        check(machine.reg(0) == 0U, "et la lecture rend zéro");
    }
    {   // Lire vers R15 ne branche pas : ce sont les indicateurs qui reçoivent
        // les quatre bits hauts.
        Machine machine;
        machine.run({coprocessor_transfer(true, 0U, 15U, 0U, 0U, 0U)});
        check(machine.reg(15) == program_base + 4U, "la lecture vers R15 ne branche pas");
        // 0x41059461 : bit 30 posé, les trois autres non.
        check(!machine.cpu.state().flag(psr::negative), "indicateur N");
        check(machine.cpu.state().flag(psr::zero), "indicateur Z");
        check(!machine.cpu.state().flag(psr::carry), "indicateur C");
        check(!machine.cpu.state().flag(psr::overflow), "indicateur V");
    }
}

void les_registres_de_protection_se_relisent() {
    {   // Huit régions, chacune indépendante, et les deux numéros d'opération
        // secondaire désignent la même.
        Machine machine;
        for (std::uint32_t region = 0; region < 8U; ++region) {
            machine.cp15().write(0U, 6U, region, 0U, window_of(0x0100'0000U * (region + 1U), region + 3U) | 1U);
        }
        for (std::uint32_t region = 0; region < 8U; ++region) {
            const auto attendu = window_of(0x0100'0000U * (region + 1U), region + 3U) | 1U;
            check(
                machine.cp15().read(0U, 6U, region, 0U) == attendu,
                "région " + std::to_string(region) + " relue par le numéro zéro"
            );
            check(
                machine.cp15().read(0U, 6U, region, 1U) == attendu,
                "région " + std::to_string(region) + " relue par le numéro un"
            );
        }
    }
    {   // La base est alignée sur quatre kilooctets et les bits sans emploi
        // tombent.
        Machine machine;
        machine.cp15().write(0U, 6U, 0U, 0U, 0xffff'ffffU);
        check(machine.cp15().read(0U, 6U, 0U, 0U) == 0xffff'f03fU, "masque d'un registre de région");
    }
    {   // Mémorisable et tamponnable : un bit par région.
        Machine machine;
        machine.cp15().write(0U, 2U, 0U, 0U, 0xffff'ff5aU);
        machine.cp15().write(0U, 2U, 0U, 1U, 0xffff'ffa5U);
        machine.cp15().write(0U, 3U, 0U, 0U, 0xffff'ff3cU);
        check(machine.cp15().read(0U, 2U, 0U, 0U) == 0x5aU, "données mémorisables");
        check(machine.cp15().read(0U, 2U, 0U, 1U) == 0xa5U, "instructions mémorisables");
        check(machine.cp15().read(0U, 3U, 0U, 0U) == 0x3cU, "tamponnable");
    }
    {   // La forme courte des permissions est une vue de la forme étendue, pas
        // un second registre.
        Machine machine;
        machine.cp15().write(0U, 5U, 0U, 2U, 0x3210'7654U);
        check(machine.cp15().read(0U, 5U, 0U, 2U) == 0x3210'7654U, "permissions étendues relues");
        // Deux bits bas de chaque champ de quatre : 4,5,6,7,0,1,2,3.
        check(machine.cp15().read(0U, 5U, 0U, 0U) == 0b11'10'01'00'11'10'01'00U, "vue courte des permissions");
    }
    {   // Écrire la forme courte étend chaque champ.
        Machine machine;
        machine.cp15().write(0U, 5U, 0U, 1U, 0xffffU);
        check(machine.cp15().read(0U, 5U, 0U, 3U) == 0x3333'3333U, "la forme courte s'étend sur quatre bits");
        check(machine.cp15().read(0U, 5U, 0U, 1U) == 0xffffU, "et se relit telle quelle");
    }
    {   // Données et instructions ne se mélangent pas.
        Machine machine;
        machine.cp15().write(0U, 5U, 0U, 2U, 0x1111'1111U);
        check(machine.cp15().read(0U, 5U, 0U, 3U) == 0U, "les permissions d'instruction restent vierges");
    }
}

void la_base_des_vecteurs_deplace_les_exceptions() {
    {
        Machine machine;
        machine.run({software_interrupt});
        check(machine.reg(15) == Arm9::software_interrupt_vector, "en position basse, le vecteur est à son adresse nue");
    }
    {   // La position haute déplace toute la table, pas seulement un vecteur.
        Machine machine;
        machine.cp15().write(0U, 1U, 0U, 0U, Cp15::high_vectors);
        machine.run({software_interrupt});
        check(Cp15::high_vector_base == 0xffff'0000U, "la table haute commence seize octets sous la fin de l'espace");
        check(
            machine.reg(15) == 0xffff'0008U,
            "en position haute, l'appel superviseur se déplace"
        );
    }
    {
        Machine machine;
        machine.cp15().write(0U, 1U, 0U, 0U, Cp15::high_vectors);
        machine.reg(15) = program_base;
        machine.cpu.set_irq_line(true);
        machine.cpu.step();
        check(
            machine.reg(15) == Cp15::high_vector_base + Arm9::irq_vector,
            "l'interruption se déplace aussi"
        );
    }
    {
        Machine machine;
        machine.cp15().write(0U, 1U, 0U, 0U, Cp15::high_vectors);
        machine.run({coprocessor_data_operation});
        check(
            machine.reg(15) == Cp15::high_vector_base + Arm9::undefined_vector,
            "l'instruction inconnue aussi"
        );
    }
    {   // Revenir en position basse ramène la table.
        Machine machine;
        machine.cp15().write(0U, 1U, 0U, 0U, Cp15::high_vectors);
        machine.cp15().write(0U, 1U, 0U, 0U, 0U);
        machine.run({software_interrupt});
        check(machine.reg(15) == Arm9::software_interrupt_vector, "la table revient en position basse");
    }
}

void les_memoires_locales_repondent_avant_le_bus() {
    constexpr std::uint32_t four_kilobytes = 3U;
    constexpr std::uint32_t sixteen_kilobytes = 5U;
    constexpr std::uint32_t thirty_two_kilobytes = 6U;
    constexpr std::uint32_t address = 0x0000'0400;

    {   // Éteintes, elles ne changent rien.
        Machine machine;
        machine.reg(0) = 0x1234'5678U;
        machine.reg(1) = address;
        machine.run({
            (always << 28U) | (1U << 26U) | (1U << 24U) | (1U << 23U) | (1U << 16U),   // STR r0, [r1]
        });
        check(machine.bus.read32(address) == 0x1234'5678U, "sans mémoire locale, l'écriture va au bus");
    }
    {   // Allumée, l'ITCM absorbe l'accès sans que le bus soit sollicité.
        Machine machine;
        machine.configure_tcm(
            Cp15::itcm_enable,
            window_of(0U, four_kilobytes),
            0U
        );
        machine.bus.poke32(address, 0xdead'beefU);
        const auto avant = machine.bus.writes;
        check(machine.cp15().store(address, 4U, 0x1111'2222U), "l'ITCM répond à l'écriture");
        check(machine.bus.writes == avant, "et le bus n'a pas été sollicité");
        check(machine.bus.read32(address) == 0xdead'beefU, "la mémoire extérieure est intacte");

        std::uint32_t relu = 0;
        check(machine.cp15().load(address, 4U, relu), "l'ITCM répond à la lecture");
        check(relu == 0x1111'2222U, "et rend ce qui y a été écrit");
    }
    {   // Elle est instruction autant que donnée : le processeur y cherche son
        // code. C'est le point décisif du lot.
        Machine machine;
        machine.configure_tcm(Cp15::itcm_enable, window_of(0U, four_kilobytes), 0U);
        // Deux programmes différents à la même adresse, l'un dehors, l'autre dedans.
        machine.bus.poke32(program_base, mov_immediate(0U, 0x11U));
        static_cast<void>(machine.cp15().store(program_base, 4U, mov_immediate(0U, 0x22U)));
        machine.reg(15) = program_base;
        machine.cpu.step();
        check(machine.reg(0) == 0x22U, "l'instruction vient de la mémoire locale, pas du bus");
    }
    {   // Le chemin d'exécution du cœur emprunte la mémoire locale, et non le
        // bus : c'est ce que voit un programme, et donc ce qui compte.
        // La DTCM, et non l'ITCM : la base de celle-ci étant câblée à zéro,
        // toute fenêtre qu'on lui donne recouvre le programme lui-même.
        constexpr std::uint32_t data = 0x0080'0400U;
        Machine machine;
        machine.configure_tcm(
            Cp15::dtcm_enable,
            0U,
            window_of(0x0080'0000U, sixteen_kilobytes)
        );
        machine.bus.poke32(data, 0xdead'beefU);
        static_cast<void>(machine.cp15().store(data, 4U, 0x1234'5678U));
        machine.reg(1) = data;
        machine.reg(0) = 0xaaaa'aaaaU;
        const auto ecritures = machine.bus.writes;
        machine.run({
            transfer(true, false, 1U, 2U),        // LDR r2, [r1]
            transfer(true, true, 1U, 3U),         // LDRB r3, [r1]
            transfer(false, false, 1U, 0U),       // STR r0, [r1]
        });
        check(machine.reg(2) == 0x1234'5678U, "la lecture de mot vient de la mémoire locale");
        check(machine.reg(3) == 0x78U, "la lecture d'octet n'en ramène qu'un");
        check(machine.bus.writes == ecritures, "l'écriture n'a pas touché le bus");
        check(machine.bus.read32(data) == 0xdead'beefU, "la mémoire extérieure est intacte");
        std::uint32_t relu = 0;
        check(machine.cp15().load(data, 4U, relu) && relu == 0xaaaa'aaaaU, "et la locale a bien reçu l'écriture");
    }
    {   // Une fenêtre posée en haut de l'espace ne doit pas se replier sur le bas.
        Machine machine;
        machine.configure_tcm(
            Cp15::dtcm_enable,
            0U,
            window_of(0xffff'f000U, 4U)
        );
        std::uint32_t relu = 0;
        check(!machine.cp15().load(0x0000'0100U, 4U, relu), "une adresse basse reste hors de la fenêtre haute");
        check(machine.cp15().load(0xffff'f100U, 4U, relu), "et la fenêtre répond bien chez elle");
    }
    {   // Le décalage dans la mémoire se compte depuis la base de la fenêtre, et
        // non depuis zéro. La base est choisie hors d'un multiple de la taille
        // physique, sans quoi les deux calculs donneraient le même résultat.
        Machine machine;
        machine.configure_tcm(
            Cp15::dtcm_enable,
            0U,
            window_of(0x0080'1000U, sixteen_kilobytes)
        );
        static_cast<void>(machine.cp15().store(0x0080'1000U, 4U, 0x1111'1111U));
        static_cast<void>(machine.cp15().store(0x0080'2100U, 4U, 0x2222'2222U));
        std::uint32_t premier = 0;
        std::uint32_t second = 0;
        static_cast<void>(machine.cp15().load(0x0080'1000U, 4U, premier));
        static_cast<void>(machine.cp15().load(0x0080'2100U, 4U, second));
        check(premier == 0x1111'1111U, "le début de la fenêtre est le début de la mémoire");
        check(second == 0x2222'2222U, "et les deux écritures ne se recouvrent pas");
    }
    {   // Hors de la fenêtre, rien ne change.
        Machine machine;
        machine.configure_tcm(Cp15::itcm_enable, window_of(0U, four_kilobytes), 0U);
        std::uint32_t relu = 0;
        check(!machine.cp15().load(0x0000'2000U, 4U, relu), "au-delà de la fenêtre, la mémoire locale se tait");
        check(!machine.cp15().store(0x0000'2000U, 4U, 1U), "en écriture aussi");
    }
    {   // La fenêtre suit le champ de taille.
        Machine machine;
        machine.configure_tcm(Cp15::itcm_enable, window_of(0U, sixteen_kilobytes), 0U);
        std::uint32_t relu = 0;
        check(machine.cp15().load(0x0000'3fffU, 1U, relu), "seize kilooctets couvrent jusqu'à 0x3fff");
        check(!machine.cp15().load(0x0000'4000U, 1U, relu), "et pas au-delà");
    }
    {   // La base de l'ITCM est câblée à zéro : le champ d'adresse est sans effet.
        Machine machine;
        machine.configure_tcm(Cp15::itcm_enable, window_of(0x0080'0000U, four_kilobytes), 0U);
        std::uint32_t relu = 0;
        check(machine.cp15().load(0U, 4U, relu), "l'ITCM répond toujours depuis zéro");
        check(!machine.cp15().load(0x0080'0000U, 4U, relu), "et pas à l'adresse demandée");
    }
    {   // La DTCM, elle, se place où on le dit.
        Machine machine;
        machine.configure_tcm(
            Cp15::dtcm_enable,
            0U,
            window_of(0x0080'0000U, sixteen_kilobytes)
        );
        check(machine.cp15().store(0x0080'0100U, 4U, 0xcafe'0000U), "la DTCM répond à sa base");
        std::uint32_t relu = 0;
        check(machine.cp15().load(0x0080'0100U, 4U, relu), "et se relit");
        check(relu == 0xcafe'0000U, "avec la bonne valeur");
        check(!machine.cp15().load(0x0000'0100U, 4U, relu), "sans répondre ailleurs");
    }
    {   // La DTCM n'est pas visible à la lecture d'instruction.
        Machine machine;
        machine.configure_tcm(
            Cp15::dtcm_enable,
            0U,
            window_of(0x0000'0000U, four_kilobytes)
        );
        static_cast<void>(machine.cp15().store(program_base, 4U, 0x1234'5678U));
        std::uint32_t relu = 0;
        check(!machine.cp15().fetch(program_base, 4U, relu), "la DTCM ne sert pas d'instructions");
        check(machine.cp15().load(program_base, 4U, relu), "mais bien des données");
    }
    {   // Quand les deux se recouvrent, la DTCM passe devant pour les données,
        // et l'ITCM reste seule pour les instructions.
        Machine machine;
        machine.configure_tcm(Cp15::itcm_enable, window_of(0U, four_kilobytes), 0U);
        static_cast<void>(machine.cp15().store(address, 4U, 0x1111'1111U));

        machine.cp15().write(0U, 9U, 1U, 0U, window_of(0U, four_kilobytes));
        machine.cp15().write(0U, 1U, 0U, 0U, Cp15::dtcm_enable);
        static_cast<void>(machine.cp15().store(address, 4U, 0x2222'2222U));

        machine.cp15().write(0U, 1U, 0U, 0U, Cp15::itcm_enable | Cp15::dtcm_enable);
        std::uint32_t donnee = 0;
        std::uint32_t instruction = 0;
        check(machine.cp15().load(address, 4U, donnee), "une donnée trouve une mémoire locale");
        check(donnee == 0x2222'2222U, "et c'est la DTCM qui répond");
        check(machine.cp15().fetch(address, 4U, instruction), "une instruction en trouve une aussi");
        check(instruction == 0x1111'1111U, "et c'est l'ITCM qui répond");
    }
    {   // Une fenêtre plus grande que la mémoire la reflète.
        Machine machine;
        machine.configure_tcm(Cp15::itcm_enable, window_of(0U, thirty_two_kilobytes + 1U), 0U);
        static_cast<void>(machine.cp15().store(0x0000'0010U, 4U, 0x0bad'cafeU));
        std::uint32_t relu = 0;
        check(machine.cp15().load(Cp15::itcm_bytes + 0x10U, 4U, relu), "la fenêtre double répond");
        check(relu == 0x0bad'cafeU, "et reflète la première moitié");
    }
    {   // Les trois largeurs d'accès.
        Machine machine;
        machine.configure_tcm(Cp15::itcm_enable, window_of(0U, four_kilobytes), 0U);
        static_cast<void>(machine.cp15().store(address, 4U, 0x1234'5678U));
        std::uint32_t octet = 0;
        std::uint32_t demi = 0;
        std::uint32_t mot = 0;
        check(machine.cp15().load(address, 1U, octet) && octet == 0x78U, "lecture d'un octet");
        check(machine.cp15().load(address, 2U, demi) && demi == 0x5678U, "lecture d'un demi-mot");
        check(machine.cp15().load(address, 4U, mot) && mot == 0x1234'5678U, "lecture d'un mot");
        static_cast<void>(machine.cp15().store(address, 1U, 0xffU));
        static_cast<void>(machine.cp15().load(address, 4U, mot));
        check(mot == 0x1234'56ffU, "une écriture d'octet ne touche que le sien");
    }
}

void le_mode_chargement_ouvre_la_memoire_locale_en_ecriture_seule() {
    constexpr std::uint32_t four_kilobytes = 3U;
    constexpr std::uint32_t address = 0x0000'0400;

    {   // En mode chargement, les lectures repartent vers l'extérieur : c'est
        // ce qui permet de remplir la mémoire locale depuis le bus.
        Machine machine;
        machine.configure_tcm(Cp15::itcm_enable, window_of(0U, four_kilobytes), 0U);
        static_cast<void>(machine.cp15().store(address, 4U, 0x1111'1111U));

        machine.cp15().write(0U, 1U, 0U, 0U, Cp15::itcm_enable | Cp15::itcm_load_mode);
        std::uint32_t relu = 0;
        check(!machine.cp15().load(address, 4U, relu), "en mode chargement, la lecture repart au bus");
        check(!machine.cp15().fetch(address, 4U, relu), "la lecture d'instruction aussi");
        check(machine.cp15().store(address, 4U, 0x2222'2222U), "mais l'écriture reste absorbée");

        machine.cp15().write(0U, 1U, 0U, 0U, Cp15::itcm_enable);
        check(machine.cp15().load(address, 4U, relu), "hors du mode chargement, elle répond de nouveau");
        check(relu == 0x2222'2222U, "et rend ce qui y a été rangé pendant");
    }
    {   // Même règle pour la DTCM, et sans déborder sur l'ITCM.
        Machine machine;
        machine.configure_tcm(
            Cp15::dtcm_enable | Cp15::dtcm_load_mode,
            0U,
            window_of(0U, four_kilobytes)
        );
        std::uint32_t relu = 0;
        check(!machine.cp15().load(address, 4U, relu), "la DTCM en mode chargement ne lit plus");
        check(machine.cp15().store(address, 4U, 0x3333'3333U), "mais écrit toujours");
    }
}

void l_attente_d_interruption_arrete_le_coeur() {
    {   // Le cœur s'arrête, et n'avance plus.
        Machine machine;
        machine.load(program_base, {mov_immediate(0U, 0x42U)});
        machine.cp15().write(0U, 7U, 0U, 4U, 0U);
        check(machine.cp15().halted(), "l'attente est enregistrée");
        machine.reg(15) = program_base;
        machine.cpu.step();
        machine.cpu.step();
        check(machine.reg(15) == program_base, "le compteur n'avance pas");
        check(machine.reg(0) == 0U, "et rien ne s'exécute");
    }
    {   // Une ligne posée le réveille et l'interruption est prise.
        Machine machine;
        machine.cp15().write(0U, 7U, 0U, 4U, 0U);
        machine.reg(15) = program_base;
        machine.cpu.set_irq_line(true);
        machine.cpu.step();
        check(!machine.cp15().halted(), "l'attente est levée");
        check(machine.reg(15) == Arm9::irq_vector, "et l'interruption est prise");
    }
    {   // Une ligne masquée réveille quand même : c'est l'attente qui s'achève,
        // pas l'interruption qui s'impose.
        Machine machine;
        machine.cpu.state().cpsr =
            static_cast<std::uint32_t>(CpuMode::system) | psr::irq_disable;
        machine.load(program_base, {mov_immediate(0U, 0x42U)});
        machine.cp15().write(0U, 7U, 0U, 4U, 0U);
        machine.reg(15) = program_base;
        machine.cpu.set_irq_line(true);
        machine.cpu.step();
        check(!machine.cp15().halted(), "l'attente est levée malgré le masque");
        check(machine.reg(0) == 0x42U, "et l'instruction suivante s'exécute");
    }
    {   // La ligne rapide réveille aussi.
        Machine machine;
        machine.cp15().write(0U, 7U, 0U, 4U, 0U);
        machine.reg(15) = program_base;
        machine.cpu.set_fiq_line(true);
        machine.cpu.step();
        check(!machine.cp15().halted(), "la ligne rapide réveille");
    }
    {   // Les autres opérations de cache ne font rien, et surtout ne comptent
        // pas comme des registres inconnus : vider un cache absent n'est pas
        // une faute.
        Machine machine;
        for (std::uint32_t sub = 0; sub < 8U; ++sub) {
            if (sub == 4U) continue;
            machine.cp15().write(0U, 7U, 0U, sub, 0U);
            machine.cp15().write(0U, 7U, 5U, sub, 0U);
        }
        check(!machine.cp15().halted(), "aucune autre opération n'arrête le cœur");
        check(machine.cp15().unknown_access_count() == 0U, "et aucune n'est comptée comme inconnue");
    }
}

void les_registres_inconnus_sont_comptes() {
    {
        Machine machine;
        // c13 est le registre d'identifiant de processus : il n'est pas modélisé.
        static_cast<void>(machine.cp15().read(0U, 13U, 0U, 1U));
        check(machine.cp15().unknown_access_count() == 1U, "une lecture inconnue est comptée");
        check(machine.cp15().first_unknown_access() == 0x0d01U, "et le registre visé est retenu");

        machine.cp15().write(0U, 13U, 0U, 0U, 1U);
        check(machine.cp15().unknown_access_count() == 2U, "une écriture inconnue aussi");
        check(machine.cp15().first_unknown_access() == 0x0d01U, "sans effacer la première");
    }
    {   // Écrire un registre d'identité est un accès inconnu, pas une écriture.
        Machine machine;
        machine.cp15().write(0U, 0U, 0U, 0U, 0xffff'ffffU);
        check(machine.cp15().unknown_access_count() == 1U, "un registre en lecture seule ne s'écrit pas");
        check(machine.cp15().read(0U, 0U, 0U, 0U) == Cp15::main_id, "et garde sa valeur");
    }
    {   // Un numéro secondaire hors des huit régions n'existe pas.
        Machine machine;
        static_cast<void>(machine.cp15().read(0U, 6U, 8U, 0U));
        check(machine.cp15().unknown_access_count() == 1U, "il n'y a que huit régions");
    }
}

void la_remise_a_zero_efface_la_configuration() {
    Machine machine;
    machine.configure_tcm(
        Cp15::itcm_enable | Cp15::dtcm_enable | Cp15::high_vectors,
        window_of(0U, 3U),
        window_of(0x0080'0000U, 5U)
    );
    static_cast<void>(machine.cp15().store(0x0000'0100U, 4U, 0xdead'beefU));
    machine.cp15().write(0U, 6U, 0U, 0U, 0xffff'ffffU);
    machine.cp15().write(0U, 7U, 0U, 4U, 0U);
    static_cast<void>(machine.cp15().read(0U, 13U, 0U, 0U));

    machine.cpu.reset();

    check(machine.cp15().control() == Cp15::control_read_as_one, "le contrôle repart de sa valeur de mise sous tension");
    check(machine.cp15().exception_base() == 0U, "les vecteurs reviennent en position basse");
    check(!machine.cp15().halted(), "l'attente est levée");
    check(machine.cp15().unknown_access_count() == 0U, "le compte des registres inconnus repart de zéro");
    check(machine.cp15().first_unknown_access() == 0U, "et le premier est oublié");
    check(machine.cp15().read(0U, 6U, 0U, 0U) == 0U, "les régions sont vierges");
    check(machine.cp15().read(0U, 9U, 1U, 1U) == 0U, "la région de l'ITCM aussi");
    check(machine.cp15().read(0U, 9U, 1U, 0U) == 0U, "et celle de la DTCM");

    // Le contenu des mémoires locales est remis à zéro : deux exécutions du même
    // programme ne doivent pas pouvoir diverger sur de la mémoire jamais écrite.
    machine.cp15().write(0U, 9U, 1U, 1U, window_of(0U, 3U));
    machine.cp15().write(0U, 1U, 0U, 0U, Cp15::itcm_enable);
    std::uint32_t relu = 0xffff'ffffU;
    check(machine.cp15().load(0x0000'0100U, 4U, relu), "la mémoire locale répond de nouveau");
    check(relu == 0U, "et son contenu a été effacé");
}

} // namespace

} // namespace ravenemu::nds::testing

int main() {
    using namespace ravenemu::nds::testing;
    les_registres_d_identite_sont_figes();
    le_registre_de_controle_a_ses_bits_figes();
    les_instructions_de_coprocesseur_sont_filtrees();
    les_registres_de_protection_se_relisent();
    la_base_des_vecteurs_deplace_les_exceptions();
    les_memoires_locales_repondent_avant_le_bus();
    le_mode_chargement_ouvre_la_memoire_locale_en_ecriture_seule();
    l_attente_d_interruption_arrete_le_coeur();
    les_registres_inconnus_sont_comptes();
    la_remise_a_zero_efface_la_configuration();
    return 0;
}
