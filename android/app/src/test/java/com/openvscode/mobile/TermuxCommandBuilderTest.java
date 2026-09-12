package com.openvscode.mobile;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.io.File;
import java.util.concurrent.TimeUnit;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

public class TermuxCommandBuilderTest {
    private static final String TOKEN = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private static Map<String, byte[]> assets() {
        Map<String, byte[]> result = new LinkedHashMap<>();
        result.put("install.sh", "#!/bin/bash\nprintf 'installed\\n'\n".getBytes(StandardCharsets.UTF_8));
        result.put("start.sh", "#!/bin/bash\nexit 0\n".getBytes(StandardCharsets.UTF_8));
        result.put("setup.sh", "#!/bin/bash\nexit 0\n".getBytes(StandardCharsets.UTF_8));
        for (String path : new String[]{"scripts/runtime.sh", "scripts/status_server.py",
                "scripts/install_toolchain.sh", "scripts/install_extensions.sh", "scripts/install_jupyter_kernels.sh"}) {
            result.put(path, "# transport fixture\n".getBytes(StandardCharsets.UTF_8));
        }
        return result;
    }

    @Test public void scriptsTravelAsDataAndDoNotExpandWhileStaging() {
        Map<String, byte[]> files = assets();
        files.put("examples/unsafe.py", "$(touch /tmp/surprise)\n'`echo nope`\n".getBytes(StandardCharsets.UTF_8));
        String command = TermuxCommandBuilder.build(files, "install", TOKEN, true);
        assertFalse(command.contains("touch /tmp/surprise"));
        assertFalse(command.contains("`echo nope`"));
        assertTrue(command.contains(" --with-notebooks < /dev/null"));
        assertTrue(command.contains("tail -c 12000"));
        assertTrue(command.contains("exit \"$result\""));
    }

    @Test public void startDoesNotRequestNotebookInstallation() {
        String command = TermuxCommandBuilder.build(assets(), "start", TOKEN, true);
        assertFalse(command.contains("--with-notebooks"));
        assertTrue(command.contains("bash \"$runtime/start.sh\" --status-token '" + TOKEN + "'"));
    }

    @Test public void progressIdentifiesItsOwnOperationAndRejectsInjectedIdentity() {
        String requestId = "ab0b8d43-8a13-4017-a6c2-477c4709e3f9";
        String command = TermuxCommandBuilder.build(assets(), "install", TOKEN, false, requestId);
        assertTrue(command.contains("export OPENVSCODE_REQUEST_ID='" + requestId + "'\n"));
        assertThrows(IllegalArgumentException.class,
                () -> TermuxCommandBuilder.build(assets(), "install", TOKEN, false, "'; id; #"));
    }

    @Test public void probeChecksTheLinkWithoutStagingTheRuntime() {
        String requestId = "ab0b8d43-8a13-4017-a6c2-477c4709e3f9";
        String probe = TermuxCommandBuilder.buildProbe(requestId);
        assertTrue(probe.contains(TermuxCommandBuilder.PROBE_MARKER));
        assertFalse("A link check must not carry the installer with it", probe.contains("base64 -d"));
        assertThrows(IllegalArgumentException.class, () -> TermuxCommandBuilder.buildProbe("'; id; #"));
    }

    @Test public void actualBashAnswersTheProbeWithItsMarker() throws Exception {
        File bash = new File("/bin/bash");
        if (!bash.isFile()) bash = new File("C:/Program Files/Git/bin/bash.exe");
        assumeTrue("Bash is needed for the probe integration test", bash.isFile());
        String requestId = "ab0b8d43-8a13-4017-a6c2-477c4709e3f9";
        ProcessBuilder processBuilder = new ProcessBuilder(bash.getAbsolutePath(), "-s");
        processBuilder.environment().put("HOME",
                temporary.newFolder("probe-home").getAbsolutePath().replace('\\', '/'));
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();
        try {
            process.getOutputStream().write(
                    TermuxCommandBuilder.buildProbe(requestId).getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();
            assertTrue("The link check must answer quickly", process.waitFor(15, TimeUnit.SECONDS));
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(output, 0, process.exitValue());
            assertTrue(output, output.contains(TermuxCommandBuilder.PROBE_MARKER));
            assertTrue(output, output.contains("request " + requestId));
            // The nested quoting has to survive Java, an intent extra and stdin;
            // an empty field would silently cost the check its diagnostic value.
            assertTrue(output, output.matches("(?s).*\\narch \\S+\\n.*"));
            assertTrue("free space must report a number: " + output,
                    output.matches("(?s).*\\nfree-kb \\d+\\n.*"));
        } finally {
            process.destroyForcibly();
        }
    }

    @Test public void rejectsTraversalAndShellSyntaxInPaths() {
        for (String path : new String[]{"../install.sh", "/tmp/a", "a/../b", "a/./b", "a//b", "a';id", "a$(id)", "a\\b"}) {
            Map<String, byte[]> files = assets();
            files.put(path, new byte[0]);
            assertThrows(path, IllegalArgumentException.class,
                    () -> TermuxCommandBuilder.build(files, "install", TOKEN, false));
        }
    }

    @Test public void rejectsMissingEntryPointsOrUntrustedArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> TermuxCommandBuilder.build(assets(), "install; id", TOKEN, false));
        assertThrows(IllegalArgumentException.class,
                () -> TermuxCommandBuilder.build(assets(), "install", "'$(id)", false));
        Map<String, byte[]> incomplete = assets();
        incomplete.remove("setup.sh");
        assertThrows(IllegalArgumentException.class,
                () -> TermuxCommandBuilder.build(incomplete, "start", TOKEN, false));
        Map<String, byte[]> noRuntime = assets();
        noRuntime.remove("scripts/runtime.sh");
        assertThrows("Missing bundled helpers must not trigger the standalone network installer",
                IllegalArgumentException.class,
                () -> TermuxCommandBuilder.build(noRuntime, "install", TOKEN, false));
    }

    @Test public void enforcesBinderBudgetAfterBase64Expansion() {
        Map<String, byte[]> files = assets();
        files.put("large.txt", new byte[TermuxCommandBuilder.MAX_SCRIPT_BYTES]);
        assertThrows(IllegalArgumentException.class,
                () -> TermuxCommandBuilder.build(files, "install", TOKEN, false));
    }

    @Test public void diagnosticOutputIsBoundedAndSecretFree() {
        assertEquals("hello [private token]\n", TermuxCommandBuilder.safeOutput(
                "\u001b[32mhello " + TOKEN + "\u001b[0m\r\n", TOKEN));
        String output = TermuxCommandBuilder.safeOutput("x".repeat(14000) + "last useful error", TOKEN);
        assertEquals(TermuxCommandBuilder.MAX_OUTPUT_CHARS, output.length());
        assertTrue(output.endsWith("last useful error"));
    }

    @Test public void actualBashStagesLiteralAssetsAndPreservesInstallerFailure() throws Exception {
        File bash = new File("/bin/bash");
        if (!bash.isFile()) bash = new File("C:/Program Files/Git/bin/bash.exe");
        assumeTrue("Bash is needed for the transport integration test", bash.isFile());
        File home = temporary.newFolder("termux-home");
        Map<String, byte[]> files = assets();
        files.put("install.sh", "#!/bin/bash\nprintf 'an actionable install error\\n'\nexit 37\n".getBytes(StandardCharsets.UTF_8));
        byte[] literal = "'$(touch do-not-create)'\n`echo not-executed`\n".getBytes(StandardCharsets.UTF_8);
        files.put("examples/literal.txt", literal);
        String script = TermuxCommandBuilder.build(files, "install", TOKEN, false);
        ProcessBuilder processBuilder = new ProcessBuilder(bash.getAbsolutePath(), "-s");
        processBuilder.environment().put("HOME", home.getAbsolutePath().replace('\\', '/'));
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();
        try {
            process.getOutputStream().write(script.getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();
            assertTrue("Bundled command must finish", process.waitFor(15, TimeUnit.SECONDS));
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(output, 37, process.exitValue());
            assertTrue(output.contains("an actionable install error"));
            assertArrayEquals(literal, Files.readAllBytes(new File(home,
                    ".local/share/openvscode/runtime/examples/literal.txt").toPath()));
        } finally {
            process.destroyForcibly();
        }
    }
}
