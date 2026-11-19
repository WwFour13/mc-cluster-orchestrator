import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Comparator;

public class TestDirectWorldEmpty {

    public static void main(String[] args) throws IOException {
        System.out.println("================================================================================");
        System.out.println("  [DIRECT TEST 4/5] ServerLauncher.isWorldEmpty() Boundary & Integrity Checks   ");
        System.out.println("================================================================================");

        int failures = 0;
        Path tempRoot = Paths.get("tests", ".tmp_world_detection_test");
        Files.createDirectories(tempRoot);

        try {
            // Case 1: Fresh directory with no world folder
            if (ServerLauncher.isWorldEmpty(tempRoot)) {
                System.out.println("  ✓ PASS: isWorldEmpty() returns TRUE when 'world' directory does not exist");
            } else {
                System.err.println("  ✗ FAIL: isWorldEmpty() returned false on non-existent world folder!");
                failures++;
            }

            // Case 2: Empty world directory
            Path worldDir = tempRoot.resolve("world");
            Files.createDirectories(worldDir);
            if (ServerLauncher.isWorldEmpty(tempRoot)) {
                System.out.println("  ✓ PASS: isWorldEmpty() returns TRUE for completely empty 'world/' folder");
            } else {
                System.err.println("  ✗ FAIL: isWorldEmpty() returned false on empty world folder!");
                failures++;
            }

            // Case 3: World directory containing only metadata / OS noise (.gitkeep, .DS_Store)
            Files.writeString(worldDir.resolve(".gitkeep"), "", StandardCharsets.UTF_8);
            Files.writeString(worldDir.resolve(".DS_Store"), "", StandardCharsets.UTF_8);
            if (ServerLauncher.isWorldEmpty(tempRoot)) {
                System.out.println("  ✓ PASS: isWorldEmpty() returns TRUE when only OS metadata files (.gitkeep) exist");
            } else {
                System.err.println("  ✗ FAIL: isWorldEmpty() falsely treated metadata files as active world!");
                failures++;
            }

            // Case 4: World directory containing empty subfolders (region, data)
            Files.createDirectories(worldDir.resolve("region"));
            Files.createDirectories(worldDir.resolve("data"));
            if (ServerLauncher.isWorldEmpty(tempRoot)) {
                System.out.println("  ✓ PASS: isWorldEmpty() returns TRUE when subdirectories are empty");
            } else {
                System.err.println("  ✗ FAIL: isWorldEmpty() treated empty subdirectories as active world!");
                failures++;
            }

            // Case 5: Real active world with level.dat
            Files.writeString(worldDir.resolve("level.dat"), "mock_level_binary_data", StandardCharsets.UTF_8);
            if (!ServerLauncher.isWorldEmpty(tempRoot)) {
                System.out.println("  ✓ PASS: isWorldEmpty() returns FALSE when level.dat is present (World Recognized)");
            } else {
                System.err.println("  ✗ FAIL: isWorldEmpty() failed to recognize active world with level.dat!");
                failures++;
            }

            // Case 6: Real active world with region chunk file
            Files.delete(worldDir.resolve("level.dat"));
            Files.writeString(worldDir.resolve("region").resolve("r.0.0.mca"), "mock_chunk_binary", StandardCharsets.UTF_8);
            if (!ServerLauncher.isWorldEmpty(tempRoot)) {
                System.out.println("  ✓ PASS: isWorldEmpty() returns FALSE when region/r.0.0.mca chunk exists");
            } else {
                System.err.println("  ✗ FAIL: isWorldEmpty() failed to recognize active chunks in region folder!");
                failures++;
            }

        } finally {
            if (Files.exists(tempRoot)) {
                Files.walk(tempRoot)
                        .sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
            }
        }

        System.out.println("--------------------------------------------------------------------------------");
        if (failures == 0) {
            System.out.println("[RESULT] ALL DIRECT WORLD DETECTION ASSERTIONS PASSED.\n");
            System.exit(0);
        } else {
            System.err.printf("[RESULT] %d ASSERTIONS FAILED.\n\n", failures);
            System.exit(1);
        }
    }
}
