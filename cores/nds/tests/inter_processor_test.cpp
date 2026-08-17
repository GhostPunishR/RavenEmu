#include "cpu/arm_core.hpp"
#include "memory/arm7_memory_map.hpp"
#include "memory/arm9_memory_map.hpp"
#include "memory/system_memory.hpp"
#include "system/inter_processor.hpp"
#include "system/registers.hpp"

#include "check.hpp"

#include <cstdint>
#include <string>

/**
 * Communication entre les deux processeurs.
 *
 * Trois niveaux se succèdent. Le premier éprouve le mécanisme seul : ce que
 * chaque côté voit du registre de synchronisation, ce que les files acceptent
 * et refusent. Le deuxième éprouve **le destinataire de chaque interruption**,
 * qui est le point où une erreur donne deux processeurs qui s'attendent l'un
 * l'autre sans fin. Le troisième monte les deux processeurs sur leurs cartes et
 * fait passer un message de l'un à l'autre jusqu'à l'exécution du gestionnaire
 * d'interruption — la seule vérification qui dise si l'ensemble tient.
 */
namespace ravenemu::nds::testing {

using ravenemu::testing::check;

namespace {

constexpr std::uint32_t always = 0xeU;
constexpr std::uint32_t program_base = 0x0200'1000;

constexpr std::uint32_t mov_immediate(std::uint32_t rd, std::uint32_t value, std::uint32_t rotation = 0U) noexcept {
    return (always << 28U) | (1U << 25U) | (0xdU << 21U) | (rd << 12U) |
        (((rotation / 2U) << 8U) | value);
}

/** `cond 01 I P U B W L Rn Rd offset`, pré-indexé sans réécriture. */
constexpr std::uint32_t transfer(
    bool load,
    std::uint32_t rn,
    std::uint32_t rd,
    std::uint32_t offset = 0U
) noexcept {
    return (always << 28U) | (1U << 26U) | (1U << 24U) | (1U << 23U) |
        (load ? (1U << 20U) : 0U) | (rn << 16U) | (rd << 12U) | offset;
}

/** Les deux processeurs, leurs cartes et le matériel qu'ils partagent. */
struct Console {
    SystemMemory system{};
    InterruptController main_interrupts{};
    InterruptController secondary_interrupts{};
    InterProcessor link{main_interrupts, secondary_interrupts};
    VideoSystem video{main_interrupts, secondary_interrupts};
    Arm9MemoryMap main_map{system, video, link, main_interrupts};
    Arm7MemoryMap secondary_map{system, video, link, secondary_interrupts};

    Console() {
        system.reset();
        main_interrupts.reset();
        secondary_interrupts.reset();
        link.reset();
        video.reset();
        main_map.reset();
        secondary_map.reset();
    }

    /** Ouvre les files des deux côtés, ce que fait tout logiciel avant d'échanger. */
    void open_queues() {
        link.write_control(Processor::main, InterProcessor::queues_enabled);
        link.write_control(Processor::secondary, InterProcessor::queues_enabled);
    }
};

// --------------------------------------------------------------------------

void le_registre_de_synchronisation_croise_les_deux_champs() {
    {   // Ce que l'un écrit, l'autre le lit — mais pas à la même place.
        Console console;
        console.link.write_sync(Processor::main, 0x0500U);       // sortie 5
        check(
            (console.link.read_sync(Processor::secondary) & InterProcessor::sync_input_mask) == 5U,
            "le secondaire lit en entrée ce que le principal a mis en sortie"
        );
        check(
            (console.link.read_sync(Processor::main) & InterProcessor::sync_output_mask) == 0x0500U,
            "et le principal relit sa propre sortie à sa place"
        );
        check(
            (console.link.read_sync(Processor::main) & InterProcessor::sync_input_mask) == 0U,
            "sans rien voir en entrée tant que l'autre n'a rien écrit"
        );
    }
    {   // Et réciproquement, sans que les deux sorties se mélangent.
        Console console;
        console.link.write_sync(Processor::main, 0x0300U);
        console.link.write_sync(Processor::secondary, 0x0c00U);
        check((console.link.read_sync(Processor::main) & 0xfU) == 0xcU, "le principal voit la sortie du secondaire");
        check((console.link.read_sync(Processor::secondary) & 0xfU) == 0x3U, "et le secondaire celle du principal");
        check((console.link.read_sync(Processor::main) & 0x0f00U) == 0x0300U, "chacun garde la sienne");
        check((console.link.read_sync(Processor::secondary) & 0x0f00U) == 0x0c00U, "des deux côtés");
    }
    {   // Seuls quatre bits passent : le champ est étroit, et c'est ce qui fait
        // qu'on ouvre une file quand on a plus à dire.
        Console console;
        console.link.write_sync(Processor::main, 0xff00U);
        check(
            (console.link.read_sync(Processor::secondary) & InterProcessor::sync_input_mask) == 0xfU,
            "quatre bits, pas davantage"
        );
    }
}

void l_appel_par_synchronisation_va_au_bon_destinataire() {
    {   // Sans consentement du destinataire, l'appel ne réveille personne.
        Console console;
        console.link.write_sync(Processor::main, InterProcessor::sync_send_interrupt);
        check(console.secondary_interrupts.requested() == 0U, "le destinataire n'a pas dit accepter");
        check(console.main_interrupts.requested() == 0U, "et l'appelant ne se réveille pas lui-même");
    }
    {   // Avec son consentement, il est réveillé — et lui seul.
        Console console;
        console.link.write_sync(Processor::secondary, InterProcessor::sync_accept_interrupt);
        console.link.write_sync(Processor::main, InterProcessor::sync_send_interrupt);
        check(
            console.secondary_interrupts.requested() == InterruptController::ipc_sync,
            "le destinataire est réveillé"
        );
        check(console.main_interrupts.requested() == 0U, "l'appelant ne l'est pas");
    }
    {   // Le consentement se relit, et le bit d'appel ne se retient pas.
        Console console;
        console.link.write_sync(Processor::main, InterProcessor::sync_accept_interrupt);
        check(
            (console.link.read_sync(Processor::main) & InterProcessor::sync_accept_interrupt) != 0U,
            "le consentement se relit"
        );
        console.link.write_sync(
            Processor::main,
            InterProcessor::sync_accept_interrupt | InterProcessor::sync_send_interrupt
        );
        check(
            (console.link.read_sync(Processor::main) & InterProcessor::sync_send_interrupt) == 0U,
            "le bit d'appel déclenche et disparaît"
        );
    }
}

void les_files_transportent_dans_les_deux_sens() {
    {   // Éteintes, elles avalent tout sans rien rendre.
        Console console;
        console.link.send(Processor::main, 0x1234'5678U);
        check(
            (console.link.read_control(Processor::secondary) & InterProcessor::receive_queue_empty) != 0U,
            "rien n'entre dans une file éteinte"
        );
        check(console.link.receive(Processor::secondary) == 0U, "et rien n'en sort");
        check(
            (console.link.read_control(Processor::main) & InterProcessor::queue_error) == 0U,
            "sans que ce soit une erreur : le canal n'est pas ouvert"
        );
    }
    {   // Ouvertes, elles transportent dans les deux sens, dans l'ordre.
        Console console;
        console.open_queues();
        console.link.send(Processor::main, 0x1111'1111U);
        console.link.send(Processor::main, 0x2222'2222U);
        check(console.link.receive(Processor::secondary) == 0x1111'1111U, "premier mot déposé, premier retiré");
        check(console.link.receive(Processor::secondary) == 0x2222'2222U, "puis le second");

        console.link.send(Processor::secondary, 0xaaaa'aaaaU);
        check(console.link.receive(Processor::main) == 0xaaaa'aaaaU, "et dans l'autre sens");
    }
    {   // Les deux files sont distinctes : ce que l'un dépose ne revient pas à lui.
        Console console;
        console.open_queues();
        console.link.send(Processor::main, 0x1111'1111U);
        check(
            (console.link.read_control(Processor::main) & InterProcessor::receive_queue_empty) != 0U,
            "l'expéditeur ne reçoit pas ce qu'il envoie"
        );
        check(
            (console.link.read_control(Processor::main) & InterProcessor::send_queue_empty) == 0U,
            "mais sa file d'envoi n'est plus vide"
        );
        check(
            (console.link.read_control(Processor::secondary) & InterProcessor::receive_queue_empty) == 0U,
            "et celle de réception du destinataire non plus"
        );
    }
    {   // Seize mots exactement, et le dix-septième est une erreur.
        Console console;
        console.open_queues();
        for (std::uint32_t index = 0; index < InterProcessor::queue_depth; ++index) {
            console.link.send(Processor::main, index);
        }
        check(
            (console.link.read_control(Processor::main) & InterProcessor::send_queue_full) != 0U,
            "seize mots remplissent la file"
        );
        check(
            (console.link.read_control(Processor::main) & InterProcessor::queue_error) == 0U,
            "sans erreur jusque-là"
        );

        console.link.send(Processor::main, 0xffff'ffffU);
        check(
            (console.link.read_control(Processor::main) & InterProcessor::queue_error) != 0U,
            "le dix-septième déborde et inscrit une erreur"
        );

        // Le mot en trop est écarté, et non substitué au premier.
        for (std::uint32_t index = 0; index < InterProcessor::queue_depth; ++index) {
            check(
                console.link.receive(Processor::secondary) == index,
                "mot " + std::to_string(index) + " conservé dans l'ordre"
            );
        }
        check(
            (console.link.read_control(Processor::secondary) & InterProcessor::receive_queue_empty) != 0U,
            "et rien de plus"
        );
    }
    {   // Lire une file vide rend la dernière valeur lue, et inscrit l'erreur.
        Console console;
        console.open_queues();
        console.link.send(Processor::main, 0x0bad'cafeU);
        check(console.link.receive(Processor::secondary) == 0x0bad'cafeU, "le mot est lu");
        check(console.link.receive(Processor::secondary) == 0x0bad'cafeU, "la file vide rend le dernier mot");
        check(
            (console.link.read_control(Processor::secondary) & InterProcessor::queue_error) != 0U,
            "et l'erreur est inscrite"
        );
    }
    {   // L'erreur ne s'efface qu'à l'acquittement, comme une demande.
        Console console;
        console.open_queues();
        static_cast<void>(console.link.receive(Processor::main));
        check(
            (console.link.read_control(Processor::main) & InterProcessor::queue_error) != 0U,
            "l'erreur est là"
        );
        console.link.write_control(Processor::main, InterProcessor::queues_enabled);
        check(
            (console.link.read_control(Processor::main) & InterProcessor::queue_error) != 0U,
            "une écriture qui n'acquitte pas la laisse"
        );
        console.link.write_control(
            Processor::main,
            InterProcessor::queues_enabled | InterProcessor::queue_error
        );
        check(
            (console.link.read_control(Processor::main) & InterProcessor::queue_error) == 0U,
            "écrire un l'efface"
        );
    }
    {   // Vider sa file d'envoi la vide bien, et n'affecte que la sienne.
        Console console;
        console.open_queues();
        console.link.send(Processor::main, 1U);
        console.link.send(Processor::secondary, 2U);
        console.link.write_control(
            Processor::main,
            InterProcessor::queues_enabled | InterProcessor::send_queue_clear
        );
        check(
            (console.link.read_control(Processor::main) & InterProcessor::send_queue_empty) != 0U,
            "la file d'envoi du principal est vide"
        );
        check(
            (console.link.read_control(Processor::main) & InterProcessor::receive_queue_empty) == 0U,
            "sa file de réception ne l'est pas"
        );
        check(console.link.receive(Processor::main) == 2U, "et le mot du secondaire est toujours là");
    }
}

void chaque_interruption_va_au_bon_destinataire() {
    {   // La file qui se remplit réveille celui qui reçoit.
        Console console;
        console.open_queues();
        console.link.write_control(
            Processor::secondary,
            InterProcessor::queues_enabled | InterProcessor::receive_filled_interrupt
        );
        console.link.send(Processor::main, 1U);
        check(
            console.secondary_interrupts.requested() == InterruptController::ipc_receive_queue_filled,
            "le destinataire est réveillé"
        );
        check(console.main_interrupts.requested() == 0U, "et non l'expéditeur");
    }
    {   // Elle ne réveille qu'au passage du vide au plein : c'est un front.
        Console console;
        console.open_queues();
        console.link.write_control(
            Processor::secondary,
            InterProcessor::queues_enabled | InterProcessor::receive_filled_interrupt
        );
        console.link.send(Processor::main, 1U);
        console.secondary_interrupts.acknowledge(InterruptController::ipc_receive_queue_filled);
        console.link.send(Processor::main, 2U);
        check(
            console.secondary_interrupts.requested() == 0U,
            "un second mot sur une file déjà remplie ne réveille pas de nouveau"
        );
    }
    {   // La file qui se vide réveille celui qui envoie, non celui qui lit.
        Console console;
        console.open_queues();
        console.link.write_control(
            Processor::main,
            InterProcessor::queues_enabled | InterProcessor::send_empty_interrupt
        );
        console.link.send(Processor::main, 1U);
        console.link.send(Processor::main, 2U);

        static_cast<void>(console.link.receive(Processor::secondary));
        check(console.main_interrupts.requested() == 0U, "la file n'est pas encore vide");

        static_cast<void>(console.link.receive(Processor::secondary));
        check(
            console.main_interrupts.requested() == InterruptController::ipc_send_queue_empty,
            "l'expéditeur est réveillé quand sa file se vide"
        );
        check(console.secondary_interrupts.requested() == 0U, "et non celui qui vient de lire");
    }
    {   // Vider soi-même sa file d'envoi la rend vide, et vaut donc la même
        // demande que si le destinataire l'avait consommée.
        Console console;
        console.open_queues();
        console.link.write_control(
            Processor::main,
            InterProcessor::queues_enabled | InterProcessor::send_empty_interrupt
        );
        console.link.send(Processor::main, 1U);
        console.link.write_control(
            Processor::main,
            InterProcessor::queues_enabled | InterProcessor::send_empty_interrupt
                | InterProcessor::send_queue_clear
        );
        check(
            console.main_interrupts.requested() == InterruptController::ipc_send_queue_empty,
            "vider sa file la rend vide, et la demande est due"
        );
    }
    {   // Sans autorisation, rien ne réveille.
        Console console;
        console.open_queues();
        console.link.send(Processor::main, 1U);
        static_cast<void>(console.link.receive(Processor::secondary));
        check(console.main_interrupts.requested() == 0U, "aucune demande côté expéditeur");
        check(console.secondary_interrupts.requested() == 0U, "aucune côté destinataire");
    }
}

void le_controleur_d_interruptions_a_trois_verrous() {
    {   // Il en faut trois pour que la ligne se lève.
        Console console;
        console.main_interrupts.request(InterruptController::ipc_sync);
        check(!console.main_interrupts.line(), "une demande seule ne suffit pas");

        console.main_interrupts.set_enabled(InterruptController::ipc_sync);
        check(!console.main_interrupts.line(), "la source autorisée non plus");

        console.main_interrupts.set_master_enable(1U);
        check(console.main_interrupts.line(), "il faut aussi l'autorisation générale");
    }
    {   // Une source autorisée qui n'a rien demandé ne lève rien.
        Console console;
        console.main_interrupts.set_master_enable(1U);
        console.main_interrupts.set_enabled(0xffff'ffffU);
        check(!console.main_interrupts.line(), "sans demande, pas de ligne");
    }
    {   // Une demande non autorisée reste inscrite sans lever la ligne : c'est
        // ainsi qu'un logiciel scrute sans être interrompu.
        Console console;
        console.main_interrupts.set_master_enable(1U);
        console.main_interrupts.set_enabled(InterruptController::ipc_sync);
        console.main_interrupts.request(InterruptController::ipc_send_queue_empty);
        check(!console.main_interrupts.line(), "la source n'est pas autorisée");
        check(
            console.main_interrupts.requested() == InterruptController::ipc_send_queue_empty,
            "mais la demande est bien inscrite"
        );
    }
    {   // L'acquittement efface, et seulement ce qu'on lui désigne.
        Console console;
        console.main_interrupts.request(
            InterruptController::ipc_sync | InterruptController::ipc_send_queue_empty
        );
        console.main_interrupts.acknowledge(InterruptController::ipc_sync);
        check(
            console.main_interrupts.requested() == InterruptController::ipc_send_queue_empty,
            "l'autre demande survit"
        );
    }
    {   // Les deux processeurs ont chacun le leur.
        Console console;
        console.main_interrupts.request(InterruptController::ipc_sync);
        check(console.secondary_interrupts.requested() == 0U, "les demandes ne traversent pas");
    }
}

void les_registres_repondent_par_les_deux_cartes() {
    {   // Le registre de synchronisation, vu depuis les deux cartes.
        Console console;
        console.main_map.write16(registers::sync, 0x0700U);
        check(
            (console.secondary_map.read16(registers::sync) & 0xfU) == 7U,
            "le secondaire lit par sa carte ce que le principal a écrit par la sienne"
        );
        check(console.main_map.unimplemented_io_count() == 0U, "ces registres sont modélisés");
        check(console.secondary_map.unimplemented_io_count() == 0U, "des deux côtés");
    }
    {   // Un mot passe d'une carte à l'autre.
        Console console;
        console.main_map.write16(registers::queue_control, InterProcessor::queues_enabled);
        console.secondary_map.write16(registers::queue_control, InterProcessor::queues_enabled);
        console.main_map.write32(registers::queue_send, 0x0bad'cafeU);
        check(
            console.secondary_map.read32(registers::queue_receive) == 0x0bad'cafeU,
            "le mot traverse d'une carte à l'autre"
        );
    }
    {   // Un mot ne se retire pas en quatre morceaux d'un octet : l'accès est
        // indivisible, sans quoi la file avancerait quatre fois.
        Console console;
        console.main_map.write16(registers::queue_control, InterProcessor::queues_enabled);
        console.secondary_map.write16(registers::queue_control, InterProcessor::queues_enabled);
        console.main_map.write32(registers::queue_send, 0x1234'5678U);
        console.main_map.write32(registers::queue_send, 0xaabb'ccddU);
        check(console.secondary_map.read32(registers::queue_receive) == 0x1234'5678U, "premier mot");
        check(console.secondary_map.read32(registers::queue_receive) == 0xaabb'ccddU, "second mot");
    }
    {   // Chaque registre a son sens de circulation.
        Console console;
        static_cast<void>(console.main_map.read32(registers::queue_send));
        check(console.main_map.unimplemented_io_count() == 1U, "l'envoi ne se relit pas");
        console.main_map.write32(registers::queue_receive, 0U);
        check(console.main_map.unimplemented_io_count() == 2U, "et la réception ne s'écrit pas");
    }
    {   // Les registres d'interruption se lisent et s'écrivent par la carte, et
        // le registre des demandes s'acquitte en écrivant un.
        Console console;
        console.main_map.write32(registers::interrupt_enable, InterruptController::ipc_sync);
        check(
            console.main_map.read32(registers::interrupt_enable) == InterruptController::ipc_sync,
            "les sources autorisées se relisent"
        );
        console.main_map.write8(registers::interrupt_master, 1U);
        check(console.main_map.read8(registers::interrupt_master) == 1U, "l'autorisation générale aussi");

        console.main_interrupts.request(InterruptController::ipc_sync);
        check(
            console.main_map.read32(registers::interrupt_request) == InterruptController::ipc_sync,
            "la demande se lit"
        );
        console.main_map.write32(registers::interrupt_request, InterruptController::ipc_sync);
        check(console.main_map.read32(registers::interrupt_request) == 0U, "et écrire un l'efface");
    }
    {   // Écrire zéro dans le registre des demandes n'efface rien : c'est le
        // piège du registre, et un émulateur qui l'écrirait normalement ferait
        // se redéclencher les interruptions sans fin.
        Console console;
        console.main_interrupts.request(InterruptController::ipc_sync);
        console.main_map.write32(registers::interrupt_request, 0U);
        check(
            console.main_map.read32(registers::interrupt_request) == InterruptController::ipc_sync,
            "écrire zéro laisse la demande"
        );
    }
    {   // Chaque carte parle à son propre contrôleur.
        Console console;
        console.main_map.write32(registers::interrupt_enable, 0xffU);
        check(console.secondary_map.read32(registers::interrupt_enable) == 0U, "les contrôleurs sont séparés");
    }
}

/**
 * Les nombres du matériel, écrits en toutes lettres.
 *
 * Partout ailleurs les vérifications passent par les constantes du cœur, ce qui
 * les rend lisibles mais aveugles : une constante fausse rend le test faux de la
 * même façon, et il passe. Ici les adresses et les positions de bits sont
 * écrites littéralement, telles qu'un programme de la console les écrirait. Ce
 * n'est donc plus le cœur qui se compare à lui-même.
 */
void les_adresses_et_les_bits_sont_ceux_du_materiel() {
    {   // Le registre de synchronisation se lit d'un seul accès de seize bits :
        // le consentement loge dans l'octet haut, et une lecture par morceaux le
        // laisserait tomber.
        Console console;
        console.main_map.write16(0x0400'0180U, 0x4500U);
        check(
            console.main_map.read16(0x0400'0180U) == 0x4500U,
            "sortie 5 et consentement se relisent ensemble"
        );
        check(console.main_map.unimplemented_io_count() == 0U, "par un accès indivisible");
        check(
            (console.secondary_map.read16(0x0400'0180U) & 0x000fU) == 5U,
            "et l'autre côté voit la sortie en entrée"
        );
    }
    {   // Un mot traverse d'une carte à l'autre, aux adresses de la console.
        Console console;
        console.main_map.write16(0x0400'0184U, 0x8000U);
        console.secondary_map.write16(0x0400'0184U, 0x8000U);
        console.main_map.write32(0x0400'0188U, 0x0bad'cafeU);
        check(
            console.secondary_map.read32(0x0410'0000U) == 0x0bad'cafeU,
            "envoi en 0x04000188, réception en 0x04100000"
        );
        check(console.main_map.unimplemented_io_count() == 0U, "sans registre inconnu au passage");
        check(console.secondary_map.unimplemented_io_count() == 0U, "des deux côtés");
    }
    {   // Les trois registres d'interruption, à leurs adresses.
        Console console;
        console.main_map.write8(0x0400'0208U, 1U);
        check(console.main_map.read8(0x0400'0208U) == 1U, "autorisation générale en 0x04000208");
        console.main_map.write32(0x0400'0210U, 0x0001'0000U);
        check(console.main_map.read32(0x0400'0210U) == 0x0001'0000U, "sources autorisées en 0x04000210");
        console.main_interrupts.request(0x0001'0000U);
        check(console.main_map.read32(0x0400'0214U) == 0x0001'0000U, "demandes en 0x04000214");
        check(console.main_map.unimplemented_io_count() == 0U, "et aucune de ces adresses n'est inconnue");
    }
    {   // Écrire un octet remplace le sien, et ne fait pas qu'ajouter des bits.
        Console console;
        console.main_map.write32(0x0400'0210U, 0xffff'ffffU);
        console.main_map.write8(0x0400'0211U, 0x00U);
        check(
            console.main_map.read32(0x0400'0210U) == 0xffff'00ffU,
            "l'octet écrit remplace le sien, et lui seul"
        );
    }
    {   // La synchronisation est la source de rang 16.
        Console console;
        console.link.write_sync(Processor::main, 0x4000U);
        console.link.write_sync(Processor::secondary, 0x2000U);
        check(
            console.main_map.read32(0x0400'0214U) == 0x0001'0000U,
            "un appel de synchronisation demande le bit 16"
        );
    }
    {   // Une file qui se remplit est la source de rang 18, et elle réveille
        // celui qui reçoit.
        Console console;
        console.main_map.write16(0x0400'0184U, 0x8000U | 0x0400U);
        console.secondary_map.write16(0x0400'0184U, 0x8000U);
        console.secondary_map.write32(0x0400'0188U, 0x1U);
        check(
            console.main_map.read32(0x0400'0214U) == 0x0004'0000U,
            "une file remplie demande le bit 18"
        );
        check(console.secondary_map.read32(0x0400'0214U) == 0U, "et rien du côté qui a envoyé");
    }
    {   // Une file qui se vide est la source de rang 17, et elle réveille celui
        // qui envoie.
        Console console;
        console.main_map.write16(0x0400'0184U, 0x8000U | 0x0004U);
        console.secondary_map.write16(0x0400'0184U, 0x8000U);
        console.main_map.write32(0x0400'0188U, 0x1U);
        static_cast<void>(console.secondary_map.read32(0x0410'0000U));
        check(
            console.main_map.read32(0x0400'0214U) == 0x0002'0000U,
            "une file vidée demande le bit 17"
        );
        check(console.secondary_map.read32(0x0400'0214U) == 0U, "et rien du côté qui a lu");
    }
    {   // Seize mots, comptés un à un : la file n'est pleine qu'au seizième.
        Console console;
        console.open_queues();
        for (std::uint32_t index = 0; index < 16U; ++index) {
            console.link.send(Processor::main, index);
            const bool pleine = (console.link.read_control(Processor::main) & 0x0002U) != 0U;
            check(
                pleine == (index == 15U),
                "mot " + std::to_string(index + 1U) + " : pleine seulement au seizième"
            );
        }
        check(
            (console.link.read_control(Processor::secondary) & 0x0200U) != 0U,
            "et la file de réception du destinataire est pleine elle aussi"
        );
        check(
            (console.link.read_control(Processor::main) & 0x8000U) != 0U,
            "les files se relisent ouvertes"
        );
    }
}

/**
 * Ce que le lien fait quand rien ne se passe.
 *
 * Les cas où l'on attend qu'il ne fasse *rien* : un réveil qui ne doit pas
 * partir, une file éteinte qui ne doit pas avancer. Ils ne se remarquent pas à
 * l'usage, parce qu'un réveil de trop ressemble à un réveil légitime.
 */
void le_lien_ne_reveille_pas_sans_raison() {
    {   // Écrire sa sortie n'est pas appeler : sans le bit d'appel, le
        // destinataire consentant n'est pas réveillé pour autant.
        Console console;
        console.link.write_sync(Processor::secondary, InterProcessor::sync_accept_interrupt);
        console.link.write_sync(Processor::main, 0x0300U);
        check(console.secondary_interrupts.requested() == 0U, "poser sa sortie ne réveille personne");
    }
    {   // Vider une file déjà vide ne la change pas, donc ne réveille personne.
        Console console;
        console.open_queues();
        console.link.write_control(
            Processor::main,
            InterProcessor::queues_enabled | InterProcessor::send_empty_interrupt
        );
        console.link.write_control(
            Processor::main,
            InterProcessor::queues_enabled | InterProcessor::send_empty_interrupt |
                InterProcessor::send_queue_clear
        );
        check(console.main_interrupts.requested() == 0U, "vider le vide ne réveille personne");
    }
    {   // Une file éteinte du côté qui lit n'avance pas : le mot y reste, et le
        // retrouver après réouverture est ce qui le prouve.
        Console console;
        console.open_queues();
        console.link.send(Processor::main, 0x1234'5678U);
        console.link.write_control(Processor::secondary, 0U);
        check(console.link.receive(Processor::secondary) == 0U, "une file éteinte ne rend rien");
        check(
            (console.link.read_control(Processor::secondary) & InterProcessor::queue_error) == 0U,
            "et n'inscrit pas d'erreur : le canal est fermé, pas fautif"
        );
        console.link.write_control(Processor::secondary, InterProcessor::queues_enabled);
        check(console.link.receive(Processor::secondary) == 0x1234'5678U, "le mot n'avait pas bougé");
    }
    {   // La file tourne : au bout de plus d'un tour, chaque mot déposé est bien
        // celui qu'on retire, et non celui d'avant resté en place.
        Console console;
        console.open_queues();
        for (std::uint32_t index = 0; index < 3U * InterProcessor::queue_depth; ++index) {
            const std::uint32_t mot = 0x1000'0000U + index;
            console.link.send(Processor::main, mot);
            check(
                console.link.receive(Processor::secondary) == mot,
                "tour " + std::to_string(index) + " : le mot déposé est celui retiré"
            );
        }
    }
    {   // La remise à zéro d'un contrôleur coupe les trois verrous, et pas
        // seulement les demandes.
        Console console;
        console.main_interrupts.set_master_enable(1U);
        console.main_interrupts.set_enabled(0xffff'ffffU);
        console.main_interrupts.request(InterruptController::ipc_sync);
        console.main_interrupts.reset();
        check(console.main_interrupts.enabled() == 0U, "les sources autorisées sont coupées");
        check(console.main_interrupts.master_enable() == 0U, "l'autorisation générale aussi");
        check(console.main_interrupts.requested() == 0U, "et les demandes en attente");
        check(!console.main_interrupts.line(), "la ligne est retombée");
    }
}

/**
 * Un message qui va jusqu'au bout.
 *
 * C'est la vérification qui compte : le processeur secondaire dépose un mot, et
 * le processeur principal, occupé à tout autre chose, est interrompu, exécute
 * son gestionnaire, lit le mot et l'acquitte.
 */
void un_message_reveille_l_autre_processeur() {
    Console console;
    Arm9 cpu{console.main_map};
    cpu.reset();
    cpu.state().cpsr = static_cast<std::uint32_t>(CpuMode::system);

    // Le processeur principal ouvre le canal, demande à être prévenu, et
    // autorise l'interruption.
    console.main_map.write16(
        registers::queue_control,
        InterProcessor::queues_enabled | InterProcessor::receive_filled_interrupt
    );
    console.secondary_map.write16(registers::queue_control, InterProcessor::queues_enabled);
    console.main_map.write32(registers::interrupt_enable, InterruptController::ipc_receive_queue_filled);
    console.main_map.write8(registers::interrupt_master, 1U);

    // Programme principal : une boucle qui compte, pour montrer qu'il fait
    // autre chose au moment où l'interruption tombe.
    console.main_map.write32(program_base, mov_immediate(4U, 0x11U));
    console.main_map.write32(program_base + 4U, mov_immediate(4U, 0x22U));

    // La table des vecteurs vit en mémoire locale : la carte ne décode pas les
    // adresses basses, qui appartiennent au cœur. Le logiciel de la console
    // allume donc cette mémoire avant d'accepter la moindre interruption.
    cpu.cp15().write(0U, 9U, 1U, 1U, 5U << 1U);              // seize kilooctets
    cpu.cp15().write(0U, 1U, 0U, 0U, Cp15::itcm_enable);

    // Gestionnaire d'interruption, au vecteur : lit le mot reçu.
    constexpr std::uint32_t receive_high = registers::queue_receive >> 20U;
    static_cast<void>(cpu.cp15().store(ArmCore::irq_vector, 4U, mov_immediate(1U, receive_high, 12U)));
    static_cast<void>(cpu.cp15().store(ArmCore::irq_vector + 4U, 4U, transfer(true, 1U, 0U)));

    cpu.state().registers[15] = program_base;
    check(!console.main_interrupts.line(), "rien ne demande encore");
    cpu.step();
    check(cpu.state().registers[4] == 0x11U, "le processeur principal travaille");

    // Le secondaire dépose un mot.
    console.secondary_map.write32(registers::queue_send, 0x0000'0042U);
    check(console.main_interrupts.line(), "la ligne du principal se lève");

    // Le câblage de la ligne au processeur appartiendra à la boucle de la
    // machine ; ici le test le fait, ce qui suffit à montrer que le chemin est
    // complet de bout en bout.
    cpu.set_irq_line(console.main_interrupts.line());
    cpu.step();
    check(cpu.state().registers[15] == ArmCore::irq_vector, "il saute à son gestionnaire");
    check(cpu.state().mode() == CpuMode::irq, "en mode interruption");
    check(cpu.state().registers[14] == program_base + 8U, "en retenant où reprendre");

    cpu.step();                                              // adresse du registre
    cpu.step();                                              // lecture du mot
    check(cpu.state().registers[0] == 0x42U, "le gestionnaire lit le mot déposé");

    // Le mot consommé, la file est vide et la demande peut être acquittée.
    console.main_map.write32(registers::interrupt_request, InterruptController::ipc_receive_queue_filled);
    check(!console.main_interrupts.line(), "la ligne retombe après acquittement");
    check(cpu.unimplemented_count() == 0U, "aucune instruction inconnue");
    check(console.main_map.unmapped_count() == 0U, "aucune adresse inconnue");
}

/** Le va-et-vient complet : une commande, puis sa réponse. */
void les_deux_processeurs_se_repondent() {
    Console console;
    console.open_queues();

    // Le principal envoie une commande et demande à être prévenu de la réponse.
    console.link.write_control(
        Processor::main,
        InterProcessor::queues_enabled | InterProcessor::receive_filled_interrupt
    );
    console.link.write_control(
        Processor::secondary,
        InterProcessor::queues_enabled | InterProcessor::receive_filled_interrupt
    );

    console.link.send(Processor::main, 0x0000'0007U);
    check(
        console.secondary_interrupts.requested() == InterruptController::ipc_receive_queue_filled,
        "le secondaire est prévenu de la commande"
    );

    const auto command = console.link.receive(Processor::secondary);
    check(command == 7U, "il lit la commande");
    console.secondary_interrupts.acknowledge(InterruptController::ipc_receive_queue_filled);

    console.link.send(Processor::secondary, command * 6U);
    check(
        console.main_interrupts.requested() == InterruptController::ipc_receive_queue_filled,
        "le principal est prévenu de la réponse"
    );
    check(console.link.receive(Processor::main) == 42U, "et la lit");

    check(
        (console.link.read_control(Processor::main) & InterProcessor::queue_error) == 0U,
        "aucune erreur de part"
    );
    check(
        (console.link.read_control(Processor::secondary) & InterProcessor::queue_error) == 0U,
        "ni d'autre"
    );
}

void la_remise_a_zero_coupe_le_lien() {
    Console console;
    console.open_queues();
    console.link.write_sync(Processor::main, 0x0f00U | InterProcessor::sync_accept_interrupt);
    console.link.send(Processor::main, 0x1234U);
    static_cast<void>(console.link.receive(Processor::main));   // provoque une erreur
    console.main_interrupts.request(InterruptController::ipc_sync);

    console.link.reset();
    console.main_interrupts.reset();

    check(console.link.read_sync(Processor::main) == 0U, "la synchronisation est effacée");
    check(console.link.read_sync(Processor::secondary) == 0U, "des deux côtés");
    check(
        (console.link.read_control(Processor::main) & InterProcessor::queue_error) == 0U,
        "l'erreur est effacée"
    );
    check(
        (console.link.read_control(Processor::main) & InterProcessor::queues_enabled) == 0U,
        "les files sont refermées"
    );
    check(
        (console.link.read_control(Processor::secondary) & InterProcessor::receive_queue_empty) != 0U,
        "et vidées"
    );
    check(console.main_interrupts.requested() == 0U, "les demandes sont effacées");
    check(!console.main_interrupts.line(), "et la ligne retombe");
}

} // namespace

} // namespace ravenemu::nds::testing

int main() {
    using namespace ravenemu::nds::testing;
    le_registre_de_synchronisation_croise_les_deux_champs();
    l_appel_par_synchronisation_va_au_bon_destinataire();
    les_files_transportent_dans_les_deux_sens();
    chaque_interruption_va_au_bon_destinataire();
    le_controleur_d_interruptions_a_trois_verrous();
    les_registres_repondent_par_les_deux_cartes();
    les_adresses_et_les_bits_sont_ceux_du_materiel();
    le_lien_ne_reveille_pas_sans_raison();
    un_message_reveille_l_autre_processeur();
    les_deux_processeurs_se_repondent();
    la_remise_a_zero_coupe_le_lien();
    return 0;
}
