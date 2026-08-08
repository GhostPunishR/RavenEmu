package com.ravenemu.nativebridge;

import java.lang.ref.PhantomReference;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Détient un cœur natif passif et le libère quand la session est abandonnée.
 *
 * <p>La libération normale est explicite : {@link #close()}, appelé par la
 * façade Kotlin et par le cycle de vie Android. Le filet de sécurité ci-dessous
 * ne sert qu'au cas où personne n'appelle {@code close()} — un cœur retient
 * plusieurs mégaoctets hors du tas Java, que le ramasse-miettes ne voit pas et
 * ne réclamera donc jamais de lui-même.
 *
 * <p>Ce filet reposait sur {@code finalize()}, déprécié pour suppression : sur
 * une machine virtuelle récente son appel n'est plus garanti et il est
 * désactivable par option, si bien que la garantie avait disparu tout en
 * gardant l'apparence d'exister. {@code java.lang.ref.Cleaner} serait le
 * remplacement idoine, mais Android ne l'expose qu'à partir de l'API 33 alors
 * que le projet vise l'API 26 : c'est donc le mécanisme portable qui le
 * sous-tend — {@link PhantomReference} et {@link ReferenceQueue} — qui est
 * utilisé directement.
 */
public final class NativeCoreHandle implements AutoCloseable {

    private static final ReferenceQueue<NativeCoreHandle> ABANDONED = new ReferenceQueue<>();

    /**
     * Garde les sentinelles vivantes.
     *
     * <p>Une {@link PhantomReference} que plus personne ne référence est
     * elle-même ramassée avant d'avoir pu être mise en file : sans cet ensemble,
     * le filet ne se déclencherait jamais.
     */
    private static final Set<Sentinel> SENTINELS = ConcurrentHashMap.newKeySet();

    static {
        Thread reaper = new Thread(NativeCoreHandle::reap, "ravenemu-native-reaper");
        reaper.setDaemon(true);
        reaper.start();
    }

    private final Sentinel sentinel;
    private long value;

    public NativeCoreHandle(int consoleStorageId, int forcedSaveType) {
        value = NativeCoreBridge.create(consoleStorageId, forcedSaveType);
        sentinel = new Sentinel(this, value);
        SENTINELS.add(sentinel);
    }

    public synchronized long value() {
        if (value == 0) throw new IllegalStateException("Le cœur natif RavenEmu est fermé");
        return value;
    }

    @Override
    public synchronized void close() {
        if (value == 0) return;
        NativeCoreBridge.destroy(value);
        value = 0;
        // Désarme le filet : le cœur est libéré, la sentinelle n'a plus d'objet.
        sentinel.disarm();
        SENTINELS.remove(sentinel);
    }

    /**
     * Libère les cœurs dont le porteur Java a été ramassé sans {@code close()}.
     *
     * <p>Le fil est démon : il ne retient jamais l'arrêt de l'application.
     */
    private static void reap() {
        while (true) {
            try {
                Sentinel abandoned = (Sentinel) ABANDONED.remove();
                SENTINELS.remove(abandoned);
                abandoned.release();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Poignée survivant à son porteur Java.
     *
     * <p>Elle ne retient que le pointeur natif, jamais le {@link
     * NativeCoreHandle} : une référence forte vers lui empêcherait à jamais sa
     * mise en file, et le filet ne servirait à rien.
     */
    private static final class Sentinel extends PhantomReference<NativeCoreHandle> {
        private volatile long value;

        Sentinel(NativeCoreHandle owner, long value) {
            super(owner, ABANDONED);
            this.value = value;
        }

        void disarm() {
            value = 0;
        }

        void release() {
            long abandoned = value;
            value = 0;
            if (abandoned != 0) NativeCoreBridge.destroy(abandoned);
        }
    }
}
