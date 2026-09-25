package at.postkorb.store;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Prozessübergreifende Sperre über eine Datei. Verhindert, dass z. B. das Programm im Infobereich
 * und ein manueller Aufruf von postkorb.cmd gleichzeitig abholen. Wird beim Beenden des
 * Prozesses vom Betriebssystem automatisch freigegeben.
 */
public final class RunLock implements AutoCloseable {

    private final FileChannel channel;
    private final FileLock lock;

    private RunLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /** @return die Sperre oder {@code null}, wenn sie bereits von einem anderen Prozess/Thread gehalten wird */
    public static RunLock tryAcquire(Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock l = ch.tryLock();
            if (l == null) {
                ch.close();
                return null;
            }
            return new RunLock(ch, l);
        } catch (OverlappingFileLockException e) {
            ch.close();
            return null;
        }
    }

    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }
}
