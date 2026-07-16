package com.example.dart.common.infra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * 파일 락으로 단일 인스턴스를 보장한다. 두 인스턴스가 동시에 돌면 각자 메모리 중복필터(SeenStore)와
 * 뉴스 버퍼를 따로 들고 있어 서로의 알림을 못 막고, 같은 기사를 인스턴스 수만큼 중복 반영하기 때문이다.
 *
 * <p>락 채널·핸들은 JVM 수명 동안 잡고 있어야 하므로 정적 참조로 유지한다
 * (지역 변수면 GC되어 채널이 닫히고 락이 풀린다).
 */
public final class SingleInstanceLock {

    private static final Logger log = LoggerFactory.getLogger(SingleInstanceLock.class);

    /** 락은 이 고정 오프셋의 1바이트에만 건다. 파일 앞쪽(메타데이터)은 잠기지 않아 경쟁 프로세스가 읽을 수 있다. */
    private static final long LOCK_SENTINEL_OFFSET = 8192L;

    private static FileChannel lockChannel;
    @SuppressWarnings("unused") // 락 유지 목적의 참조 — 직접 사용하진 않는다.
    private static FileLock instanceLock;

    private SingleInstanceLock() {}

    /**
     * 인스턴스가 하나만 뜨도록 파일 락을 건다. 이미 다른 프로세스가 잡고 있으면 즉시 종료한다.
     * 락 획득 자체가 IO 오류로 실패하면 단일 인스턴스 보장 없이 계속 진행한다(치명적이지 않음).
     */
    public static void acquire(Path lockFile) {
        try {
            lockChannel = FileChannel.open(lockFile,
                    StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            instanceLock = lockChannel.tryLock(LOCK_SENTINEL_OFFSET, 1, false);
            if (instanceLock == null) {
                log.error("이미 다른 인스턴스가 실행 중입니다 ({}). 중복 알림 방지를 위해 종료합니다 — "
                        + "기존 프로세스[{}]를 먼저 종료하세요.", lockFile.toAbsolutePath(), readHolderInfo(lockChannel));
                System.exit(1);
            }
            writeHolderInfo(lockChannel);
            log.info("단일 인스턴스 락 획득 ({}) pid={}", lockFile.toAbsolutePath(), ProcessHandle.current().pid());
        } catch (OverlappingFileLockException e) {
            log.error("이미 다른 인스턴스가 실행 중입니다 ({}). 중복 알림 방지를 위해 종료합니다.",
                    lockFile.toAbsolutePath());
            System.exit(1);
        } catch (IOException e) {
            log.error("인스턴스 락 획득 실패 ({}) — 단일 인스턴스 보장 없이 계속 진행합니다.", lockFile, e);
        }
    }

    /** 자기 PID·시작시각을 파일 앞쪽(잠기지 않은 영역)에 기록한다. 센티넬 바이트는 건드리지 않는다. */
    private static void writeHolderInfo(FileChannel ch) throws IOException {
        ProcessHandle.Info info = ProcessHandle.current().info();
        String started = info.startInstant().map(Instant::toString).orElse("?");
        String meta = "pid=" + ProcessHandle.current().pid() + " started=" + started + System.lineSeparator();
        byte[] bytes = meta.getBytes(StandardCharsets.UTF_8);
        ch.truncate(bytes.length);               // 이전 보유자의 메타 제거
        ch.write(ByteBuffer.wrap(bytes), 0);     // 앞쪽에 기록 (센티넬 오프셋보다 훨씬 작음)
        ch.force(true);
    }

    /** 락 보유자가 남긴 메타데이터를 읽는다. 실패하면 "알 수 없음". */
    private static String readHolderInfo(FileChannel ch) {
        try {
            long len = Math.min(ch.size(), LOCK_SENTINEL_OFFSET);   // 센티넬 영역은 읽지 않는다
            if (len <= 0) return "알 수 없음";
            ByteBuffer buf = ByteBuffer.allocate((int) len);
            ch.read(buf, 0);
            return new String(buf.array(), 0, buf.position(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "알 수 없음";
        }
    }
}
