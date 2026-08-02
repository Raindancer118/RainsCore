package de.raindancer.core.log;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * The logfile on disk, and the one thread allowed to write it.
 *
 * <h2>Why a queue and a thread of its own</h2>
 * A plugin logs from wherever it happens to be — a listener on a region thread, a store flushing
 * asynchronously, a scheduler task on Folia's global thread. Writing the file from those threads
 * means either a lock every caller waits on, or an interleaved file. Worse, on the main thread a
 * disk that is briefly busy becomes a server that is briefly frozen, and the whole point of writing
 * things down is that it happens when something is already going wrong.
 *
 * <p>So callers only ever hand a finished line to a bounded queue and return. One daemon thread
 * drains it. The queue is bounded on purpose: a plugin stuck in a loop logging an exception must
 * cost a few thousand dropped lines, not the server's heap. Drops are counted and reported, because
 * a logfile that quietly has holes in it is worse than one that says how many lines it lost.
 *
 * <h2>Why there is no poison pill</h2>
 * There was one, and it was a bug: a poison pill has to be {@code offer}ed onto the same bounded
 * queue, and the moment that queue is full — which is precisely the moment a server is in trouble
 * and shutting down — the pill is dropped and the drain thread waits forever on a queue nobody will
 * add to. Shutdown then costs the full join timeout, every time, in the one case where it matters.
 * Instead the drain polls with a timeout and checks a flag, so stopping needs nothing to fit
 * anywhere.
 *
 * <h2>Rotation</h2>
 * One file per day, {@code rainscore-YYYY-MM-DD.log}, rolled when the date changes rather than on a
 * timer — a server that runs for a month gets thirty readable files instead of one enormous one, and
 * "what happened on Tuesday" is answerable without a tool. Files older than the retention window are
 * deleted at startup and at each roll. A size cap exists as well, because one very bad day can
 * produce a file nobody can open; past it the day's log continues in {@code …-2.log}.
 */
public final class LogFile implements AutoCloseable {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final String PREFIX = "rainscore-";
    private static final String SUFFIX = ".log";

    /**
     * How many finished lines may wait to be written.
     *
     * <p>Generous enough that an ordinary burst — a plugin reporting every bad entry in a data file
     * it just loaded — never drops anything, small enough that the worst case costs a few megabytes.
     */
    private static final int QUEUE_CAPACITY = 8192;

    /** Past this the day's file is continued in a second part, so no single file becomes unopenable. */
    private static final long MAX_BYTES_PER_PART = 32L * 1024 * 1024;

    /** How long the drain waits for a line before looking at {@link #stopping} again. */
    private static final long POLL_MILLIS = 200;

    /** How long {@link #close()} waits for the backlog before giving up on it. */
    private static final long CLOSE_GRACE_MILLIS = 5_000;

    private final Path directory;
    private final int retentionDays;
    private final ZoneId zone;

    private final BlockingQueue<String> pending = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong dropped = new AtomicLong();
    /** Set by {@link #close()}; the drain finishes the backlog and then stops. */
    private final AtomicBoolean stopping = new AtomicBoolean();
    /** Set once nothing more will ever be written, so {@link #write} can return without queueing. */
    private final AtomicBoolean finished = new AtomicBoolean();
    private final Thread writer;

    /** Only ever touched by {@link #writer}. */
    private Writer open;
    private LocalDate openDay;
    private int openPart;
    private long openBytes;

    /**
     * The file being written, published for {@link #currentFile()}.
     *
     * <p>Volatile and a whole {@link Path} rather than the day and part separately: a reader on
     * another thread that saw a fresh day beside a stale part number would name a file that does not
     * exist, which is worse than being a moment out of date.
     */
    private volatile Path current;

    /**
     * @param directory     where the files live; created if missing
     * @param retentionDays how many days of logs to keep, at least one
     */
    public LogFile(Path directory, int retentionDays, ZoneId zone) {
        this.directory = directory;
        this.retentionDays = Math.max(1, retentionDays);
        this.zone = zone;
        this.writer = new Thread(this::drain, "RainsCore-log");
        this.writer.setDaemon(true);
        this.writer.start();
    }

    /**
     * Queues one finished line.
     *
     * <p>Never blocks and never throws: logging is what code does when it is already in trouble, and
     * a logger that can fail is a second failure on top of the first.
     */
    public void write(String line) {
        if (finished.get() || stopping.get() || line == null) {
            return;
        }
        if (!pending.offer(line)) {
            dropped.incrementAndGet();
        }
    }

    /** How many lines were thrown away because the queue was full. Reported at shutdown. */
    public long droppedLines() {
        return dropped.get();
    }

    /**
     * The file being written right now, for the "where are my logs" answer in-game.
     *
     * <p>Before the first line is written there is no file yet; the answer is then where the first
     * one would go, which is what somebody asking the question wants to know.
     */
    public Path currentFile() {
        Path published = current;
        return published != null ? published : fileFor(LocalDate.now(zone), 1);
    }

    // ------------------------------------------------------------------- the writer thread

    private void drain() {
        boolean interrupted = false;
        while (true) {
            String line;
            try {
                line = pending.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException stop) {
                // Cleared deliberately, and remembered instead. An interrupted thread cannot open or
                // write a file — NIO answers ClosedByInterruptException immediately — so leaving the
                // flag set here would throw away every line still queued, which are the last lines
                // before a shutdown and therefore the ones most worth having.
                interrupted = true;
                break;
            }
            if (line == null) {
                if (stopping.get() && pending.isEmpty()) {
                    break;
                }
                continue;
            }
            append(line);
        }

        // Whatever arrived while we were stopping still belongs in the file.
        List<String> remaining = new ArrayList<>();
        pending.drainTo(remaining);
        for (String line : remaining) {
            append(line);
        }
        closeQuietly();
        finished.set(true);
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void append(String line) {
        try {
            Writer target = writerFor(line.length());
            if (target == null) {
                return;
            }
            target.write(line);
            target.write(System.lineSeparator());
            // Flushed per line rather than buffered: the lines that matter most are the ones written
            // immediately before a crash takes the process down, and those are exactly the ones a
            // buffer loses.
            target.flush();
            openBytes += line.length() + 1L;
        } catch (IOException failure) {
            // Nowhere left to report this — the reporting channel is what just failed. Give up on the
            // file rather than spinning; the console half of the logger is unaffected.
            closeQuietly();
            stopping.set(true);
            finished.set(true);
        }
    }

    /** The writer for today, rolling the file when the day changes or the part grows too large. */
    private Writer writerFor(int incomingLength) throws IOException {
        LocalDate today = LocalDate.now(zone);
        boolean newDay = !today.equals(openDay);
        boolean tooBig = openBytes + incomingLength > MAX_BYTES_PER_PART;
        if (open != null && !newDay && !tooBig) {
            return open;
        }
        closeQuietly();
        Files.createDirectories(directory);
        if (newDay) {
            openDay = today;
            // Not part 1: this process may be the second one to run today, and the parts that
            // process wrote are still there. Appending one line to a full part 1, then rolling on
            // the next line, would scatter a startup sequence across every part of the day.
            openPart = resumablePart(today);
            prune();
        } else {
            openPart++;
        }
        Path file = fileFor(openDay, openPart);
        openBytes = Files.exists(file) ? Files.size(file) : 0L;
        open = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        current = file;
        return open;
    }

    /**
     * Which part of today's log a fresh process should continue in.
     *
     * <p>The highest one that exists and still has room, or the one after the highest when that is
     * already full. One, when the day has no log yet.
     */
    private int resumablePart(LocalDate day) {
        int highest = 1;
        for (int part = 1; part <= 1000; part++) {
            if (!Files.exists(fileFor(day, part))) {
                break;
            }
            highest = part;
        }
        Path candidate = fileFor(day, highest);
        try {
            if (Files.exists(candidate) && Files.size(candidate) >= MAX_BYTES_PER_PART) {
                return highest + 1;
            }
        } catch (IOException unreadable) {
            return highest + 1;
        }
        return highest;
    }

    private Path fileFor(LocalDate day, int part) {
        String name = PREFIX + DAY.format(day) + (part <= 1 ? "" : "-" + part) + SUFFIX;
        return directory.resolve(name);
    }

    /**
     * Deletes logs older than the retention window.
     *
     * <p>By file name rather than by modification time: a file copied out and back, or a directory
     * restored from a backup, keeps the date it is about, which is the date that matters.
     */
    private void prune() {
        LocalDate oldest = LocalDate.now(zone).minusDays(retentionDays - 1L);
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (Stream<Path> files = Files.list(directory)) {
            List<Path> expired = files
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith(PREFIX) && name.endsWith(SUFFIX);
                    })
                    .filter(path -> dayOf(path).map(day -> day.isBefore(oldest)).orElse(false))
                    .sorted(Comparator.comparing(Path::getFileName))
                    .toList();
            for (Path path : expired) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // A log that could not be tidied is not worth a second failure.
        }
    }

    private Optional<LocalDate> dayOf(Path file) {
        String name = file.getFileName().toString();
        String middle = name.substring(PREFIX.length(), name.length() - SUFFIX.length());
        // "2026-08-03" or "2026-08-03-2"; the date is always the first ten characters.
        if (middle.length() < 10) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(middle.substring(0, 10), DAY));
        } catch (RuntimeException notADate) {
            return Optional.empty();
        }
    }

    private void closeQuietly() {
        if (open == null) {
            return;
        }
        try {
            open.close();
        } catch (IOException ignored) {
            // Nothing useful to do; the handle is being dropped either way.
        }
        open = null;
        openBytes = 0L;
    }

    // ------------------------------------------------------------------------- shutdown

    /**
     * Stops the writer and waits, briefly, for the queue to be written out.
     *
     * <p>Bounded wait: a shutdown that hangs on a logger is a server that has to be killed, and the
     * lines still queued at that point are worth less than the shutdown finishing. Callers that must
     * not block at all — a settings reload on the main thread — use {@link #closeInBackground()}.
     */
    @Override
    public void close() {
        if (!stopping.compareAndSet(false, true)) {
            return;
        }
        try {
            writer.join(CLOSE_GRACE_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (writer.isAlive()) {
            writer.interrupt();
            try {
                writer.join(TimeUnit.SECONDS.toMillis(1));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Stops the writer without waiting for it.
     *
     * <p>For the one caller that runs on the server's main thread: re-configuring the logger while
     * the server is up. Waiting there would freeze the server for as long as the backlog takes to
     * write, which is exactly the trade this class exists to avoid — and unlike a shutdown, nothing
     * afterwards depends on the old file being finished.
     */
    public void closeInBackground() {
        if (stopping.get()) {
            return;
        }
        Thread closer = new Thread(this::close, "RainsCore-log-close");
        closer.setDaemon(true);
        closer.start();
    }
}
